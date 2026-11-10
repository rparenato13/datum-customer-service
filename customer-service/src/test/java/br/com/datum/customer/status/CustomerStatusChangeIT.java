package br.com.datum.customer.status;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.application.status.StatusChangeService;
import br.com.datum.customer.application.status.StatusDecision;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.messaging.status.StatusChangeCodec;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static br.com.datum.customer.status.StatusFixtures.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SecurityTestFixture
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "customer.outbox.publisher-enabled=false", "customer.status-consumer.enabled=true",
        "customer.status-consumer.recovery-delay-ms=1000", "spring.datasource.hikari.connection-timeout=2000"})
class CustomerStatusChangeIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("postgres").withUsername("datum_admin").withPassword("admin_test_only")
            .withEnv("CUSTOMER_DB_PASSWORD", "customer_test_only")
            .withEnv("NOTIFICATION_DB_PASSWORD", "notification_test_only")
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-init/01-create-databases.sh"),
                    "/docker-entrypoint-initdb.d/01-create-databases.sh");
    @Container
    static final GenericContainer<?> RABBIT = StatusBrokerFixture.CONTAINER;

    @BeforeAll
    static void broker() throws Exception { StatusBrokerFixture.configure(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/customer_db");
        registry.add("spring.datasource.username", () -> "customer_app");
        registry.add("spring.datasource.password", () -> "customer_test_only");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "customer_app");
        registry.add("spring.rabbitmq.password", () -> "customer_test_only");
    }

    @Autowired CustomerService customers;
    @Autowired StatusChangeService service;
    @Autowired StatusChangeCodec codec;
    @Autowired SimpleMessageListenerContainer statusChangeListener;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired TestRestTemplate http;

    @BeforeEach
    void clean() throws Exception {
        statusChangeListener.stop();
        StatusBrokerFixture.purge();
        jdbc.update("DELETE FROM customer_event_inbox");
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
        TestJwtSupport.authenticateAsAdmin(http);
    }

    @AfterEach
    void stopListener() { statusChangeListener.stop(); }

    CustomerEntity create() {
        return customers.create("Cliente Status", "16899535009", "status@example.test", CustomerStatus.ACTIVE);
    }

    @Test
    void shouldCommitStatusVersionAndInboxTogetherAndExposeReadOnlyVersion() {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        assertEquals(StatusDecision.APPLIED, service.process(event));
        var after = customers.findById(customer.getId());
        assertEquals(CustomerStatus.INACTIVE, after.getStatus());
        assertEquals(1, after.getVersion());
        assertTrue(after.getUpdatedAt().isAfter(customer.getUpdatedAt()));
        assertEquals("APPLIED", outcome(event));
        var response = http.getForEntity("/customers/" + customer.getId(), JsonNode.class);
        assertEquals(1, response.getBody().path("version").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customer_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customer_event_inbox WHERE processed_at IS NULL", Integer.class));
    }

    @Test
    void shouldAdvanceVersionForStatusRequestEvenWhenStatusDoesNotChange() {
        var customer = create();
        assertEquals(StatusDecision.APPLIED, service.process(event(customer.getId(), 0, CustomerStatus.ACTIVE)));
        assertEquals(1, customers.findById(customer.getId()).getVersion());
    }

    @Test
    void shouldDeduplicateBeforeVersionCheckAndNormalizeEquivalentJson() {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        service.process(event);
        var after = customers.findById(customer.getId());
        String formatted = new String(body(event), StandardCharsets.UTF_8)
                .replace("\"expectedVersion\":0", "\"expectedVersion\":0.0").replace(".123Z", ".123000Z");
        assertEquals(StatusDecision.DUPLICATE, service.process(codec.decode(formatted.getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, customers.findById(customer.getId()).getVersion());
        assertEquals(after.getUpdatedAt(), customers.findById(customer.getId()).getUpdatedAt());
        assertEquals(1, inboxCount());
    }

    @Test
    void shouldRejectIdReusedWithDifferentBodyWithoutReplacingFirstDecision() {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        service.process(event);
        var conflict = new StatusChangeEvent(event.eventId(), event.occurredAt(), event.customerId(), CustomerStatus.ACTIVE, 0);
        assertEquals(StatusDecision.EVENT_ID_CONFLICT, service.process(conflict));
        assertEquals(event.fingerprint(), jdbc.queryForObject("SELECT fingerprint FROM customer_event_inbox", String.class));
        assertEquals("APPLIED", outcome(event));
        assertEquals(CustomerStatus.INACTIVE, customers.findById(customer.getId()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 2, Long.MAX_VALUE - 1})
    void shouldRejectStaleOrFutureVersion(long expected) {
        var customer = create();
        customers.update(customer.getId(), "Nome Modificado", customer.getCpf(), customer.getEmail(), CustomerStatus.ACTIVE);
        assertEquals(1, customers.findById(customer.getId()).getVersion());
        var event = event(customer.getId(), expected, CustomerStatus.INACTIVE);
        assertEquals(StatusDecision.VERSION_CONFLICT, service.process(event));
        assertEquals("REJECTED", outcome(event));
        assertEquals(StatusDecision.VERSION_CONFLICT, service.process(event));
        assertEquals(1, customers.findById(customer.getId()).getVersion());
    }

    @Test
    void shouldKeepRejectedDecisionEvenIfVersionLaterMatches() {
        var customer = create();
        var event = event(customer.getId(), 1, CustomerStatus.INACTIVE);
        assertEquals(StatusDecision.VERSION_CONFLICT, service.process(event));
        service.process(event(customer.getId(), 0, CustomerStatus.ACTIVE));
        assertEquals(StatusDecision.VERSION_CONFLICT, service.process(event));
        assertEquals(CustomerStatus.ACTIVE, customers.findById(customer.getId()).getStatus());
        assertEquals(1, customers.findById(customer.getId()).getVersion());
    }

    @Test
    void shouldRejectMissingAndDeletedCustomersDurably() {
        var customer = create();
        customers.delete(customer.getId());
        for (long id : List.of(customer.getId(), Long.MAX_VALUE)) {
            var event = event(id, 0, CustomerStatus.INACTIVE);
            assertEquals(StatusDecision.CUSTOMER_NOT_FOUND, service.process(event));
            assertEquals(StatusDecision.CUSTOMER_NOT_FOUND, service.process(event));
            assertEquals("REJECTED", outcome(event));
        }
        assertEquals(1, jdbc.queryForObject("SELECT version FROM customers", Long.class));
        assertEquals(2, inboxCount());
    }

    @Test
    void shouldRollbackBothWhenTransactionOrInboxCompletionFails() {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        transaction.executeWithoutResult(status -> {
            assertEquals(StatusDecision.APPLIED, service.process(event));
            status.setRollbackOnly();
        });
        assertEquals(0, inboxCount());
        assertEquals(0, customers.findById(customer.getId()).getVersion());
        jdbc.execute("ALTER TABLE customer_event_inbox ADD CONSTRAINT force_finish_failure CHECK (outcome <> 'APPLIED')");
        try {
            assertThrows(RuntimeException.class, () -> service.process(event));
            assertEquals(0, inboxCount());
            assertEquals(CustomerStatus.ACTIVE, customers.findById(customer.getId()).getStatus());
            assertEquals(0, customers.findById(customer.getId()).getVersion());
        } finally {
            jdbc.execute("ALTER TABLE customer_event_inbox DROP CONSTRAINT force_finish_failure");
        }
        assertEquals(StatusDecision.APPLIED, service.process(event));
    }

    @Test
    void shouldApplyConcurrentDuplicatesOnlyOnce() throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                start.await();
                return service.process(event);
            })).toList();
            start.countDown();
            var results = new java.util.ArrayList<StatusDecision>();
            for (var call : calls) results.add(call.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(value -> value == StatusDecision.APPLIED).count());
            assertEquals(7, results.stream().filter(value -> value == StatusDecision.DUPLICATE).count());
        }
        assertEquals(1, customers.findById(customer.getId()).getVersion());
        assertEquals(1, inboxCount());
    }

    @Test
    void shouldSerializeDifferentEventsCompetingForSameVersion() throws Exception {
        var customer = create();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return service.process(event(customer.getId(), 0, CustomerStatus.INACTIVE)); });
            var second = executor.submit(() -> { start.await(); return service.process(event(customer.getId(), 0, CustomerStatus.ACTIVE)); });
            start.countDown();
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertTrue(results.contains(StatusDecision.APPLIED));
            assertTrue(results.contains(StatusDecision.VERSION_CONFLICT));
        }
        assertEquals(1, customers.findById(customer.getId()).getVersion());
        assertEquals(2, inboxCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRespectLockAndVersionOfConcurrentRestMutation(boolean delete) throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var finished = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<StatusDecision>>();
            transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT version FROM customers WHERE id = ? FOR UPDATE", Long.class, customer.getId());
                finished.set(executor.submit(() -> service.process(event)));
                // A mutacao REST usa a mesma trava da atualizacao por evento.
                if (delete) customers.delete(customer.getId());
                else customers.update(customer.getId(), "Rest Modificado", customer.getCpf(), customer.getEmail(), CustomerStatus.ACTIVE);
                assertFalse(finished.get().isDone());
            });
            assertEquals(delete ? StatusDecision.CUSTOMER_NOT_FOUND : StatusDecision.VERSION_CONFLICT,
                    finished.get().get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldConsumeRealBrokerAndAckDuplicatesAfterCommit() throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        statusChangeListener.start();
        StatusBrokerFixture.publish(event, body(event), 0);
        awaitApplied(event);
        StatusBrokerFixture.publish(event, body(event), 0);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(0, StatusBrokerFixture.count("")));
        // Parar aguarda a entrega em curso terminar, incluindo ACK.
        statusChangeListener.stop();
        assertEquals(0, StatusBrokerFixture.count(""));
        assertEquals(0, StatusBrokerFixture.count(".dlq"));
        assertEquals(1, inboxCount());
        assertEquals(1, customers.findById(customer.getId()).getVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale", "future", "missing", "invalid", "identity-conflict"})
    void shouldDeadLetterPermanentFailuresWithoutRetry(String kind, CapturedOutput output) throws Exception {
        var customer = create();
        var event = event(customer.getId(), kind.equals("future") ? 3 : 0, CustomerStatus.INACTIVE);
        if (kind.equals("stale")) customers.update(customer.getId(), "Rest", customer.getCpf(), customer.getEmail(), CustomerStatus.ACTIVE);
        if (kind.equals("missing")) event = event(Long.MAX_VALUE, 0, CustomerStatus.INACTIVE);
        if (kind.equals("identity-conflict")) {
            service.process(new StatusChangeEvent(event.eventId(), event.occurredAt(), event.customerId(), CustomerStatus.ACTIVE, 0));
        }
        byte[] bytes = kind.equals("invalid") ? "{\"cpf\":\"SENSITIVE_SENTINEL\"}".getBytes(StandardCharsets.UTF_8) : body(event);
        statusChangeListener.start();
        StatusBrokerFixture.publish(event, bytes, 0);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(1, StatusBrokerFixture.count(".dlq")));
        statusChangeListener.stop();
        var dead = StatusBrokerFixture.receiveDead();
        assertArrayEquals(bytes, dead.getBody());
        assertEquals(event.eventId().toString(), dead.getProps().getMessageId());
        assertEquals(0, StatusBrokerFixture.count(".retry.1"));
        assertEquals(kind.equals("invalid") ? 0 : 1, inboxCount());
        assertFalse(output.getAll().contains("SENSITIVE_SENTINEL"));
        assertFalse(output.getAll().contains(customer.getCpf()));
        assertFalse(output.getAll().contains(customer.getEmail()));
    }

    @Test
    void shouldTransferTransientFailureToConfirmedRetryAndRecoverAfterRealTtl() throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        jdbc.execute("ALTER TABLE customer_event_inbox ADD CONSTRAINT force_transient_failure CHECK (customer_id < 0)");
        statusChangeListener.start();
        try {
            StatusBrokerFixture.publish(event, body(event), 0);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(1, StatusBrokerFixture.count(".retry.1")));
            assertEquals(0, StatusBrokerFixture.count(""));
            assertEquals(0, inboxCount());
            assertEquals(0, customers.findById(customer.getId()).getVersion());
        } finally {
            jdbc.execute("ALTER TABLE customer_event_inbox DROP CONSTRAINT force_transient_failure");
        }
        awaitApplied(event);
        assertEquals(0, StatusBrokerFixture.count(".retry.1"));
        assertEquals(0, StatusBrokerFixture.count(".dlq"));
    }

    @Test
    void shouldDeadLetterAfterLastLogicalRetryRatherThanLoop() throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        jdbc.execute("ALTER TABLE customer_event_inbox ADD CONSTRAINT force_exhaustion CHECK (customer_id < 0)");
        statusChangeListener.start();
        try {
            StatusBrokerFixture.publish(event, body(event), 3);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(1, StatusBrokerFixture.count(".dlq")));
            assertEquals(0, inboxCount());
            assertEquals(0, customers.findById(customer.getId()).getVersion());
            assertEquals(0, StatusBrokerFixture.count(".retry.1"));
            assertEquals(0, StatusBrokerFixture.count(".retry.3"));
        } finally {
            jdbc.execute("ALTER TABLE customer_event_inbox DROP CONSTRAINT force_exhaustion");
        }
    }

    @Test
    void shouldRedeliverWithoutRepeatingEffectAfterCommitBeforeAck() throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        StatusBrokerFixture.publish(event, body(event), 0);
        try (var connection = StatusBrokerFixture.connection(); var channel = connection.createChannel()) {
            var delivery = channel.basicGet(StatusChangeEvent.CONSUMER, false);
            assertNotNull(delivery);
            assertEquals(StatusDecision.APPLIED, service.process(codec.decode(delivery.getBody())));
            // Fechar sem ACK reproduz a janela entre commit e confirmacao AMQP.
        }
        statusChangeListener.start();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(0, StatusBrokerFixture.count("")));
        statusChangeListener.stop();
        assertEquals(1, customers.findById(customer.getId()).getVersion());
        assertEquals(1, inboxCount());
        assertEquals(0, StatusBrokerFixture.count(".dlq"));
    }

    @Test
    void shouldRetainOriginalWhenRetryIsUnroutableAndRecoverWithoutLoss(CapturedOutput output) throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        String retry = StatusChangeEvent.CONSUMER + ".retry.1";
        jdbc.execute("ALTER TABLE customer_event_inbox ADD CONSTRAINT force_return CHECK (customer_id < 0)");
        try (var connection = StatusBrokerFixture.connection(); var channel = connection.createChannel()) {
            channel.queueUnbind(retry, "datum.customer.retry", retry);
            try {
                statusChangeListener.start();
                StatusBrokerFixture.publish(event, body(event), 0);
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertTrue(output.getAll().contains("RETRY_HANDOFF_FAILED")));
                assertEquals(0, StatusBrokerFixture.count(".retry.1"));
                assertEquals(0, inboxCount());
                assertEquals(0, customers.findById(customer.getId()).getVersion());
            } finally {
                channel.queueBind(retry, "datum.customer.retry", retry);
                jdbc.execute("ALTER TABLE customer_event_inbox DROP CONSTRAINT force_return");
            }
        }
        awaitApplied(event);
        assertEquals(0, StatusBrokerFixture.count(".dlq"));
    }

    @Test
    void shouldRecoverThroughRetryWhenDatabaseConnectionsAreUnavailable() throws Exception {
        var customer = create();
        var event = event(customer.getId(), 0, CustomerStatus.INACTIVE);
        // Bloquear novas conexoes e encerrar as existentes apenas neste Postgres descartavel.
        try (var admin = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "datum_admin", "admin_test_only");
             var statement = admin.createStatement()) {
            statement.execute("ALTER ROLE customer_app NOLOGIN");
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'customer_app'");
            try {
                statusChangeListener.start();
                StatusBrokerFixture.publish(event, body(event), 0);
                await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> assertEquals(1, StatusBrokerFixture.count(".retry.1")));
            } finally {
                statement.execute("ALTER ROLE customer_app LOGIN");
            }
        }
        awaitApplied(event);
        assertEquals(1, inboxCount());
        assertEquals(0, StatusBrokerFixture.count(".dlq"));
    }

    private void awaitApplied(StatusChangeEvent event) {
        await().atMost(Duration.ofSeconds(12)).ignoreExceptions().untilAsserted(() -> {
            assertEquals("APPLIED", outcome(event));
            assertEquals(1, customers.findById(event.customerId()).getVersion());
        });
    }

    private int inboxCount() { return jdbc.queryForObject("SELECT count(*) FROM customer_event_inbox", Integer.class); }
    private String outcome(StatusChangeEvent event) {
        var results = jdbc.queryForList("SELECT outcome FROM customer_event_inbox WHERE event_id = ?", String.class, event.eventId());
        return results.isEmpty() ? null : results.getFirst();
    }
}
