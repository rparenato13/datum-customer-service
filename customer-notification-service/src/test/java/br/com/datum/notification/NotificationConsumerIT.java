package br.com.datum.notification;

import br.com.datum.notification.application.CustomerCreatedEvent;
import br.com.datum.notification.application.NotificationDecision;
import br.com.datum.notification.application.NotificationService;
import br.com.datum.notification.messaging.CustomerCreatedCodec;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
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

import static br.com.datum.notification.CreatedFixtures.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notification.consumer.recovery-delay-ms=1000", "spring.datasource.hikari.connection-timeout=2000"})
class NotificationConsumerIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("postgres").withUsername("datum_admin").withPassword("admin_test_only")
            .withEnv("CUSTOMER_DB_PASSWORD", "customer_test_only")
            .withEnv("NOTIFICATION_DB_PASSWORD", "notification_test_only")
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-init/01-create-databases.sh"),
                    "/docker-entrypoint-initdb.d/01-create-databases.sh");
    @Container
    static final GenericContainer<?> RABBIT = NotificationBrokerFixture.CONTAINER;

    @BeforeAll
    static void broker() throws Exception { NotificationBrokerFixture.configure(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/notification_db");
        registry.add("spring.datasource.password", () -> "notification_test_only");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.password", () -> "notification_test_only");
    }

    @Autowired NotificationService service;
    @Autowired CustomerCreatedCodec codec;
    @Autowired SimpleMessageListenerContainer createdListener;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired TestRestTemplate http;

    @BeforeEach
    void clean() throws Exception {
        createdListener.stop();
        NotificationBrokerFixture.purge();
        jdbc.update("DELETE FROM notification_records");
        jdbc.update("DELETE FROM notification_event_inbox");
    }

    @AfterEach
    void stop() { createdListener.stop(); }

    @Test
    void shouldUseOnlyItsOwnDatabaseAndLimitedRole() throws Exception {
        assertEquals("notification_db", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("notification_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertFalse(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class));
        assertFalse(jdbc.queryForObject("SELECT has_database_privilege(current_user, 'customer_db', 'CONNECT')", Boolean.class));
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/customer_db";
        assertThrows(java.sql.SQLException.class, () -> java.sql.DriverManager.getConnection(url, "notification_app", "notification_test_only"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'customers'", Integer.class));
    }

    @Test
    void shouldExposeOnlyOperationalHealthWithoutBusinessEndpoints() {
        var health = http.getForEntity("/actuator/health", JsonNode.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertEquals("UP", health.getBody().path("status").asText());
        assertEquals(1, health.getBody().size());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/customers/123", String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/notifications", String.class).getStatusCode());
    }

    @Test
    void shouldRegisterMinimalSimulationAndInboxAtomicallyWithoutCustomerLookup() {
        var event = event(Long.MAX_VALUE);
        assertEquals(NotificationDecision.NOTIFICATION_REGISTERED, service.process(event));
        assertEquals(1, records());
        assertEquals("NOTIFICATION_REGISTERED", outcome(event));
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT customer_id FROM notification_records", Long.class));
        assertEquals("CUSTOMER_CREATED_SIMULATION", jdbc.queryForObject("SELECT kind FROM notification_records", String.class));
        assertNotNull(jdbc.queryForObject("SELECT registered_at FROM notification_records", java.sql.Timestamp.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_event_inbox WHERE processed_at IS NULL", Integer.class));
    }

    @Test
    void shouldDeduplicateNormalizedJsonAndPreserveRecordAndTimestamps() {
        var event = event(123);
        service.process(event);
        var before = jdbc.queryForMap("SELECT * FROM notification_records");
        var inboxBefore = jdbc.queryForMap("SELECT * FROM notification_event_inbox");
        String equivalent = new String(body(event), StandardCharsets.UTF_8)
                .replace(".123Z", ".123000Z").replace("\"customerId\":123", "\"customerId\":123.0");
        assertEquals(NotificationDecision.DUPLICATE, service.process(codec.decode(equivalent.getBytes(StandardCharsets.UTF_8))));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM notification_records"));
        assertEquals(inboxBefore, jdbc.queryForMap("SELECT * FROM notification_event_inbox"));
    }

    @Test
    void shouldRejectReusedIdWithDifferentPayloadAndKeepOriginalRecord() {
        var event = event(123);
        service.process(event);
        var before = jdbc.queryForMap("SELECT * FROM notification_records");
        var conflict = new CustomerCreatedEvent(event.eventId(), event.occurredAt(), 124);
        assertEquals(NotificationDecision.EVENT_ID_CONFLICT, service.process(conflict));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM notification_records"));
        assertEquals(event.fingerprint(), jdbc.queryForObject("SELECT fingerprint FROM notification_event_inbox", String.class));
    }

    @Test
    void shouldTreatDifferentEventIdsAsDifferentOccurrences() {
        service.process(event(123));
        service.process(event(123));
        assertEquals(2, records());
        assertEquals(2, inboxCount());
    }

    @Test
    void shouldRollbackBothOnEnclosingTransactionRollback() {
        transaction.executeWithoutResult(status -> {
            assertEquals(NotificationDecision.NOTIFICATION_REGISTERED, service.process(event(123)));
            status.setRollbackOnly();
        });
        assertEquals(0, records());
        assertEquals(0, inboxCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"record", "finish"})
    void shouldRollbackInboxAndRecordIfEitherWriteFails(String point) {
        var event = event(123);
        String table = point.equals("record") ? "notification_records" : "notification_event_inbox";
        String condition = point.equals("record") ? "customer_id < 0" : "outcome <> 'NOTIFICATION_REGISTERED'";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT force_atomic_failure CHECK (" + condition + ")");
        try {
            assertThrows(RuntimeException.class, () -> service.process(event));
            assertEquals(0, records());
            assertEquals(0, inboxCount());
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT force_atomic_failure");
        }
        assertEquals(NotificationDecision.NOTIFICATION_REGISTERED, service.process(event));
    }

    @Test
    void shouldRegisterConcurrentDuplicatesOnlyOnce() throws Exception {
        var event = event(123);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                start.await(); return service.process(event);
            })).toList();
            start.countDown();
            var results = new java.util.ArrayList<NotificationDecision>();
            for (var call : calls) results.add(call.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(value -> value == NotificationDecision.NOTIFICATION_REGISTERED).count());
            assertEquals(7, results.stream().filter(value -> value == NotificationDecision.DUPLICATE).count());
        }
        assertEquals(1, records());
        assertEquals(1, inboxCount());
    }

    @Test
    void shouldConsumeFromBrokerAndAckRepeatedEventWithoutSecondRecord(CapturedOutput output) throws Exception {
        var event = event(123);
        createdListener.start();
        NotificationBrokerFixture.publish(event, body(event), 0);
        awaitRegistered(event);
        NotificationBrokerFixture.publish(event, body(event), 0);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertTrue(output.getAll().contains("code=DUPLICATE")));
        createdListener.stop();
        assertEquals(0, NotificationBrokerFixture.count(""));
        assertEquals(0, NotificationBrokerFixture.count(".dlq"));
        assertEquals(1, records());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "conflicting-id", "forged-metadata"})
    void shouldDeadLetterPermanentFailureWithoutRetryOrExposingBody(String failure, CapturedOutput output) throws Exception {
        var event = event(123);
        if (failure.equals("conflicting-id")) service.process(new CustomerCreatedEvent(event.eventId(), event.occurredAt(), 124));
        byte[] bytes = failure.equals("invalid") ? "{\"cpf\":\"SENSITIVE_SENTINEL\"}".getBytes(StandardCharsets.UTF_8) : body(event);
        createdListener.start();
        if (failure.equals("forged-metadata")) {
            // Headers internos falsificados pela entrada de dominio devem ser recusados.
            try (var connection = NotificationBrokerFixture.connection(); var channel = connection.createChannel()) {
                var props = new com.rabbitmq.client.AMQP.BasicProperties.Builder().messageId(event.eventId().toString())
                        .correlationId(event.eventId().toString()).type(CustomerCreatedEvent.TYPE).deliveryMode(2)
                        .contentType("application/json").contentEncoding("UTF-8").headers(java.util.Map.of("x-retry-attempt", 1)).build();
                channel.confirmSelect();
                channel.basicPublish("datum.customer.events", "customer.created.v1", true, props, bytes);
                channel.waitForConfirmsOrDie(5000);
            }
        } else NotificationBrokerFixture.publish(event, bytes, 0);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(1, NotificationBrokerFixture.count(".dlq")));
        createdListener.stop();
        assertArrayEquals(bytes, NotificationBrokerFixture.receiveDead().getBody());
        assertEquals(failure.equals("conflicting-id") ? 1 : 0, records());
        assertEquals(0, NotificationBrokerFixture.count(".retry.1"));
        assertFalse(output.getAll().contains("SENSITIVE_SENTINEL"));
    }

    @Test
    void shouldRecoverAfterRealRetryTtlAndNotCrossDeliverToStatusConsumer() throws Exception {
        var event = event(123);
        jdbc.execute("ALTER TABLE notification_records ADD CONSTRAINT force_retry CHECK (customer_id < 0)");
        createdListener.start();
        try {
            NotificationBrokerFixture.publish(event, body(event), 0);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(1, NotificationBrokerFixture.count(".retry.1")));
            assertEquals(0, records());
            assertEquals(0, inboxCount());
        } finally {
            jdbc.execute("ALTER TABLE notification_records DROP CONSTRAINT force_retry");
        }
        awaitRegistered(event);
        try (var connection = NotificationBrokerFixture.connection(); var channel = connection.createChannel()) {
            assertEquals(0, channel.messageCount("customer-service.status-change.v1"));
        }
    }

    @Test
    void shouldDeadLetterExhaustedTransientFailure() throws Exception {
        var event = event(123);
        jdbc.execute("ALTER TABLE notification_records ADD CONSTRAINT force_exhaustion CHECK (customer_id < 0)");
        createdListener.start();
        try {
            NotificationBrokerFixture.publish(event, body(event), 3);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(1, NotificationBrokerFixture.count(".dlq")));
            assertEquals(0, records());
            assertEquals(0, inboxCount());
        } finally {
            jdbc.execute("ALTER TABLE notification_records DROP CONSTRAINT force_exhaustion");
        }
    }

    @Test
    void shouldRedeliverAfterCommitBeforeAckWithoutRepeatingEffect() throws Exception {
        var event = event(123);
        NotificationBrokerFixture.publish(event, body(event), 0);
        try (var connection = NotificationBrokerFixture.connection(); var channel = connection.createChannel()) {
            var delivery = channel.basicGet(CustomerCreatedEvent.CONSUMER, false);
            assertNotNull(delivery);
            service.process(codec.decode(delivery.getBody()));
            // Fechar sem ACK reproduz perda da conexao apos commit duravel.
        }
        createdListener.start();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertEquals(0, NotificationBrokerFixture.count("")));
        createdListener.stop();
        assertEquals(1, records());
        assertEquals(1, inboxCount());
        assertEquals(0, NotificationBrokerFixture.count(".dlq"));
    }

    @Test
    void shouldRetainOriginalWhenRetryHasNoRouteAndRecover(CapturedOutput output) throws Exception {
        var event = event(123);
        String retry = CustomerCreatedEvent.CONSUMER + ".retry.1";
        jdbc.execute("ALTER TABLE notification_records ADD CONSTRAINT force_return CHECK (customer_id < 0)");
        try (var connection = NotificationBrokerFixture.connection(); var channel = connection.createChannel()) {
            channel.queueUnbind(retry, "datum.customer.retry", retry);
            try {
                createdListener.start();
                NotificationBrokerFixture.publish(event, body(event), 0);
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertTrue(output.getAll().contains("RETRY_HANDOFF_FAILED")));
                assertEquals(0, records());
                assertEquals(0, inboxCount());
            } finally {
                channel.queueBind(retry, "datum.customer.retry", retry);
                jdbc.execute("ALTER TABLE notification_records DROP CONSTRAINT force_return");
            }
        }
        awaitRegistered(event);
        assertEquals(0, NotificationBrokerFixture.count(".dlq"));
    }

    @Test
    void shouldRecoverWhenOwnDatabaseConnectionsAreUnavailable() throws Exception {
        var event = event(123);
        try (var admin = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "datum_admin", "admin_test_only");
             var statement = admin.createStatement()) {
            statement.execute("ALTER ROLE notification_app NOLOGIN");
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'notification_app'");
            try {
                createdListener.start();
                NotificationBrokerFixture.publish(event, body(event), 0);
                await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> assertEquals(1, NotificationBrokerFixture.count(".retry.1")));
            } finally {
                statement.execute("ALTER ROLE notification_app LOGIN");
            }
        }
        awaitRegistered(event);
        assertEquals(1, inboxCount());
    }

    @Test
    void shouldKeepBrokerAccountRestrictedToNotificationConsumptionAndRetry() throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername("notification_app");
        factory.setPassword("notification_test_only");
        factory.setVirtualHost("datum");
        factory.setAutomaticRecoveryEnabled(false);
        try (var connection = factory.newConnection()) {
            var readChannel = connection.createChannel();
            var deniedRead = assertThrows(java.io.IOException.class,
                    () -> readChannel.basicGet("customer-service.status-change.v1", true));
            assertDenied(deniedRead);
            assertFalse(readChannel.isOpen());
            var channel = connection.createChannel();
            try {
                channel.confirmSelect();
                var deniedWrite = assertThrows(Exception.class, () -> {
                    channel.basicPublish("datum.customer.events", "customer.created.v1", null, body(event(123)));
                    channel.waitForConfirmsOrDie(5000);
                });
                assertDenied(deniedWrite);
            } finally {
                if (channel.isOpen()) channel.abort();
            }
            assertTrue(connection.isOpen());
        }
    }

    private void assertDenied(Throwable error) {
        while (error != null && !(error instanceof com.rabbitmq.client.ShutdownSignalException)) error = error.getCause();
        assertInstanceOf(com.rabbitmq.client.ShutdownSignalException.class, error);
        var close = assertInstanceOf(com.rabbitmq.client.AMQP.Channel.Close.class,
                ((com.rabbitmq.client.ShutdownSignalException) error).getReason());
        assertEquals(403, close.getReplyCode());
    }

    private void awaitRegistered(CustomerCreatedEvent event) {
        await().atMost(Duration.ofSeconds(12)).ignoreExceptions().untilAsserted(() -> {
            assertEquals("NOTIFICATION_REGISTERED", outcome(event));
            assertEquals(1, records());
        });
    }
    private int records() { return jdbc.queryForObject("SELECT count(*) FROM notification_records", Integer.class); }
    private int inboxCount() { return jdbc.queryForObject("SELECT count(*) FROM notification_event_inbox", Integer.class); }
    private String outcome(CustomerCreatedEvent event) {
        var results = jdbc.queryForList("SELECT outcome FROM notification_event_inbox WHERE event_id = ?", String.class, event.eventId());
        return results.isEmpty() ? null : results.getFirst();
    }
}
