package br.com.datum.customer.infrastructure.outbox;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SecurityTestFixture
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"customer.outbox.publisher-enabled=false", "customer.status-consumer.enabled=false"})
class CustomerOutboxIT {
    static final String QUEUE = "customer-notification-service.created.v1";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("postgres").withUsername("datum_admin").withPassword("admin_test_only")
            .withEnv("CUSTOMER_DB_PASSWORD", "customer_test_only")
            .withEnv("NOTIFICATION_DB_PASSWORD", "notification_test_only")
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-init/01-create-databases.sh"),
                    "/docker-entrypoint-initdb.d/01-create-databases.sh");

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.4.0-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "test_admin").withEnv("RABBITMQ_DEFAULT_PASS", "admin_test_only")
            .withEnv("RABBITMQ_DEFAULT_VHOST", "datum").withExposedPorts(5672);

    @BeforeAll
    static void configureBroker() throws Exception {
        try (var connection = admin(); var channel = connection.createChannel()) {
            channel.exchangeDeclare(CustomerCreatedEventCodec.EXCHANGE, "topic", true);
            channel.queueDeclare(QUEUE, true, false, false, Map.of("x-queue-type", "quorum"));
            channel.queueBind(QUEUE, CustomerCreatedEventCodec.EXCHANGE, CustomerCreatedEventCodec.ROUTING_KEY);
        }
        assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "add_user", "customer_app", "customer_test_only").getExitCode());
        assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "set_permissions", "-p", "datum", "customer_app", "^$",
                "^datum\\.customer\\.events$", "^$").getExitCode());
        assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "set_topic_permissions", "-p", "datum", "customer_app",
                CustomerCreatedEventCodec.EXCHANGE, "^customer\\.created\\.v1$", "^$").getExitCode());
    }

    static Connection admin() throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername("test_admin");
        factory.setPassword("admin_test_only");
        factory.setVirtualHost("datum");
        factory.setAutomaticRecoveryEnabled(false);
        factory.setConnectionTimeout(2000);
        factory.setHandshakeTimeout(5000);
        factory.setChannelRpcTimeout(5000);
        return factory.newConnection();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/customer_db");
        registry.add("spring.datasource.username", () -> "customer_app");
        registry.add("spring.datasource.password", () -> "customer_test_only");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "customer_app");
        registry.add("spring.rabbitmq.password", () -> "customer_test_only");
    }

    @Autowired CustomerService customers;
    @Autowired OutboxStore store;
    @Autowired OutboxRelay relay;
    @Autowired EventPublisher publisher;
    @Autowired CustomerCreatedEventCodec codec;
    @Autowired OutboxProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    @Autowired TransactionTemplate transaction;
    @Autowired CachingConnectionFactory connections;

    @BeforeEach
    void cleanDisposableResources() throws Exception {
        jdbc.update("DELETE FROM customer_event_inbox");
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
        try (var connection = admin(); var channel = connection.createChannel()) {
            channel.queuePurge(QUEUE);
        }
        TestJwtSupport.authenticateAsAdmin(http);
    }

    @Test
    void shouldCommitCustomerAndOneMinimalEventInSameTransaction() throws Exception {
        var customer = create();
        var event = stored();
        assertEquals(customer.getId(), event.customerId());
        assertEquals(customer.getCreatedAt(), event.occurredAt());
        assertTrue(codec.isValid(event));
        assertEquals("PENDING", state());
        assertEquals(0, event.attemptCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customer_outbox", Integer.class));
        assertFalse(event.payload().contains(customer.getCpf()));
        assertFalse(event.payload().contains(customer.getEmail()));
        try (var connection = admin(); var channel = connection.createChannel()) {
            assertEquals(0, channel.messageCount(QUEUE));
        }
    }

    @Test
    void shouldRollbackBothWhenEnclosingTransactionRollsBack() {
        transaction.executeWithoutResult(status -> {
            create();
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customer_outbox", Integer.class));
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customer_outbox", Integer.class));
    }

    @Test
    void shouldRollbackCustomerIfOutboxInsertFails(CapturedOutput output) {
        jdbc.execute("ALTER TABLE customer_outbox ADD CONSTRAINT force_outbox_failure CHECK (customer_id < 0)");
        try {
            var response = http.postForEntity("/customers", payload(), JsonNode.class);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customer_outbox", Integer.class));
            assertFalse(output.getAll().contains("16899535009"));
            assertFalse(output.getAll().contains("outbox@example.test"));
        } finally {
            jdbc.execute("ALTER TABLE customer_outbox DROP CONSTRAINT force_outbox_failure");
        }
    }

    @Test
    void shouldRejectDuplicateCpfWithoutCreatingSecondEvent() {
        create();
        var response = http.postForEntity("/customers", payload(), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customer_outbox", Integer.class));
    }

    @Test
    void shouldPublishAfterCommitWithApprovedPropertiesAndKeepEventAudit() throws Exception {
        create();
        var before = stored();
        relay.runOnce();
        assertEquals("PUBLISHED", state());
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM customer_outbox", java.sql.Timestamp.class));
        var message = receive();
        assertArrayEquals(before.payload().getBytes(StandardCharsets.UTF_8), message.getBody());
        assertEquals(before.eventId().toString(), message.getProps().getMessageId());
        assertEquals(before.eventId().toString(), message.getProps().getCorrelationId());
        assertEquals("application/json", message.getProps().getContentType());
        assertEquals("UTF-8", message.getProps().getContentEncoding());
        assertEquals("CUSTOMER_CREATED", message.getProps().getType());
        assertEquals(2, message.getProps().getDeliveryMode());
        var headers = message.getProps().getHeaders();
        assertTrue(headers == null || headers.keySet().stream().allMatch(key -> key.startsWith("spring_")),
                "Somente correlacao tecnica Spring e permitida nos headers: " + (headers == null ? "[]" : headers.keySet()));
        assertTrue(headers == null || !headers.containsKey("__TypeId__"));
        assertEquals(1, stored().attemptCount());
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void shouldNotHoldDatabaseTransactionWhileWaitingForPublisher() {
        create();
        var probe = new OutboxRelay(store, event -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename='customer_app' "
                    + "AND state='idle in transaction'", Integer.class));
            return EventPublisher.Result.CONFIRMED;
        }, codec, properties);
        probe.runOnce();
        assertEquals("PUBLISHED", state());
    }

    @Test
    void shouldLeaveUnroutableMessagePendingAndRecoverBinding() throws Exception {
        create();
        try (var connection = admin(); var channel = connection.createChannel()) {
            channel.queueUnbind(QUEUE, CustomerCreatedEventCodec.EXCHANGE, CustomerCreatedEventCodec.ROUTING_KEY);
            try {
                relay.runOnce();
                assertEquals("PENDING", state());
                assertEquals("RETURNED", errorCode());
            } finally {
                channel.queueBind(QUEUE, CustomerCreatedEventCodec.EXCHANGE, CustomerCreatedEventCodec.ROUTING_KEY);
            }
        }
        makeDue();
        relay.runOnce();
        assertEquals("PUBLISHED", state());
        assertNotNull(receive());
    }

    @Test
    void shouldAcceptPostWhileBrokerIsStoppedAndRecoverSameEvent() throws Exception {
        assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode());
        try {
            connections.resetConnection();
            var response = http.postForEntity("/customers", payload(), JsonNode.class);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            var before = stored();
            relay.runOnce();
            assertEquals("PENDING", state());
            assertNotNull(errorCode());
            assertEquals(HttpStatus.OK, http.getForEntity("/actuator/health", JsonNode.class).getStatusCode());
            assertEquals(before.payload(), stored().payload());
        } finally {
            assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode());
            await().atMost(30, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
                try (var connection = admin()) { return connection.isOpen(); }
            });
            connections.resetConnection();
        }
        makeDue();
        relay.runOnce();
        assertEquals("PUBLISHED", state());
        assertArrayEquals(stored().payload().getBytes(StandardCharsets.UTF_8), receive().getBody());
    }

    @ParameterizedTest
    @EnumSource(value = EventPublisher.Result.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void shouldNeverMarkFailedOrAmbiguousPublicationAsPublished(EventPublisher.Result result) {
        create();
        new OutboxRelay(store, event -> result, codec, properties).runOnce();
        assertEquals("PENDING", state());
        assertEquals(result.name(), errorCode());
        assertEquals(1, stored().attemptCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 20})
    void shouldUsePersistedBoundedBackoffWithoutExhaustingOutbox(int attempt) {
        create();
        jdbc.update("UPDATE customer_outbox SET attempt_count = ?", attempt - 1);
        var claimed = store.claim().orElseThrow();
        store.failed(claimed, "BROKER_UNAVAILABLE", false);
        long expected = Math.min(30, 1L << Math.min(5, attempt - 1));
        var delay = jdbc.queryForObject("SELECT extract(epoch FROM next_attempt_at - last_attempt_at) FROM customer_outbox", Double.class);
        assertNotNull(delay);
        assertTrue(delay >= expected && delay < expected + 1);
        assertEquals("PENDING", state());
        assertTrue(store.claim().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "{\"cpf\":\"SENSITIVE_OUTBOX_PAYLOAD_ONLY\"}"})
    void shouldBlockInvalidEventWithoutPublishingOrLoggingPayload(String payload, CapturedOutput output) {
        create();
        jdbc.update("UPDATE customer_outbox SET payload = ?", payload);
        new OutboxRelay(store, event -> { fail("Evento invalido nao deve chegar ao broker."); return null; }, codec, properties).runOnce();
        assertEquals("BLOCKED", state());
        assertEquals("INVALID_EVENT", errorCode());
        assertTrue(store.claim().isEmpty());
        assertFalse(output.getAll().contains("SENSITIVE_OUTBOX_PAYLOAD_ONLY"));
    }

    @Test
    void shouldContinuePastBlockedEventAndPublishHealthyEvent() throws Exception {
        var invalid = create();
        jdbc.update("UPDATE customer_outbox SET payload = '{}' WHERE customer_id = ?", invalid.getId());
        var healthy = customers.create("Outro Cliente", "39053344705", "other@example.test", CustomerStatus.INACTIVE);
        relay.runOnce();
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT state FROM customer_outbox WHERE customer_id = ?", String.class, invalid.getId()));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT state FROM customer_outbox WHERE customer_id = ?", String.class, healthy.getId()));
        assertEquals(healthy.getId(), mapper.readTree(receive().getBody()).path("customerId").asLong());
    }

    @Test
    void shouldRecoverExpiredClaimAndFenceOffPreviousOwner() {
        create();
        var first = store.claim().orElseThrow();
        assertTrue(store.claim().isEmpty());
        jdbc.update("UPDATE customer_outbox SET locked_until = clock_timestamp() - interval '1 second'");
        var second = store.claim().orElseThrow();
        assertEquals(first.eventId(), second.eventId());
        assertEquals(first.payload(), second.payload());
        assertNotEquals(first.claimToken(), second.claimToken());
        assertFalse(store.published(first));
        assertFalse(store.failed(first, "OLD_OWNER", false));
        assertTrue(store.published(second));
        assertEquals("PUBLISHED", state());
    }

    @Test
    void shouldClaimConcurrentlyWithoutSharingRows() throws Exception {
        seedEvents(6);
        var latch = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<java.util.concurrent.Future<UUID>>();
            for (int index = 0; index < 6; index++) {
                futures.add(workers.submit(() -> { latch.await(); return store.claim().orElseThrow().eventId(); }));
            }
            latch.countDown();
            var ids = new HashSet<UUID>();
            for (var future : futures) { ids.add(future.get(10, TimeUnit.SECONDS)); }
            assertEquals(6, ids.size());
        }
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void shouldPublishConcurrentlyWithoutDuplicateClaims() throws Exception {
        seedEvents(6);
        var latch = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { latch.await(); relay.runOnce(); return null; });
            var second = workers.submit(() -> { latch.await(); relay.runOnce(); return null; });
            latch.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM customer_outbox WHERE state='PUBLISHED' AND attempt_count=1", Integer.class));
        try (var connection = admin(); var channel = connection.createChannel()) {
            assertEquals(6, channel.messageCount(QUEUE));
            var ids = new HashSet<String>();
            for (int index = 0; index < 6; index++) { ids.add(channel.basicGet(QUEUE, true).getProps().getMessageId()); }
            assertEquals(6, ids.size());
        }
    }

    private void seedEvents(int total) {
        for (int index = 1; index <= total; index++) {
            int number = index;
            transaction.executeWithoutResult(status -> {
                var id = jdbc.queryForObject("INSERT INTO customers(name,cpf,email) VALUES('Probe',?, 'probe@example.test') RETURNING id",
                        Long.class, String.format("%011d", number));
                store.record(id, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            });
        }
    }

    @Test
    void shouldTolerateDuplicateAfterConfirmAndCrashBeforeLocalMark() throws Exception {
        create();
        var first = store.claim().orElseThrow();
        assertEquals(EventPublisher.Result.CONFIRMED, publisher.publish(first));
        var sent = receive();
        // Simula queda depois do confirm: o marcador local nao foi gravado.
        jdbc.update("UPDATE customer_outbox SET locked_until = clock_timestamp() - interval '1 second'");
        var second = store.claim().orElseThrow();
        assertEquals(EventPublisher.Result.CONFIRMED, publisher.publish(second));
        assertTrue(store.published(second));
        var repeated = receive();
        assertArrayEquals(sent.getBody(), repeated.getBody());
        assertEquals(sent.getProps().getMessageId(), repeated.getProps().getMessageId());
        assertEquals(first.occurredAt(), second.occurredAt());
        assertEquals(2, stored().attemptCount());
    }

    @Test
    void shouldPublishAfterCustomerIsLogicallyDeleted() throws Exception {
        var customer = create();
        customers.delete(customer.getId());
        relay.runOnce();
        assertEquals("PUBLISHED", state());
        assertNotNull(receive());
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM customers", java.sql.Timestamp.class));
    }

    private br.com.datum.customer.infrastructure.persistence.CustomerEntity create() {
        return customers.create("Cliente Outbox", "16899535009", "outbox@example.test", CustomerStatus.ACTIVE);
    }

    private Map<String, Object> payload() {
        return Map.of("name", "Cliente Outbox", "cpf", "16899535009", "email", "outbox@example.test", "status", "ACTIVE");
    }

    private OutboxEvent stored() {
        return jdbc.queryForObject("SELECT * FROM customer_outbox", (rs, index) -> new OutboxEvent(
                rs.getObject("event_id", UUID.class), rs.getLong("customer_id"), rs.getString("event_type"),
                rs.getInt("schema_version"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("payload"),
                rs.getLong("attempt_count"), rs.getObject("claim_token", UUID.class)));
    }

    private String state() { return jdbc.queryForObject("SELECT state FROM customer_outbox", String.class); }
    private String errorCode() { return jdbc.queryForObject("SELECT last_error_code FROM customer_outbox", String.class); }
    private void makeDue() { jdbc.update("UPDATE customer_outbox SET next_attempt_at = clock_timestamp() - interval '1 second'"); }

    private GetResponse receive() throws Exception {
        try (var connection = admin(); var channel = connection.createChannel()) {
            var response = channel.basicGet(QUEUE, true);
            assertNotNull(response);
            return response;
        }
    }
}
