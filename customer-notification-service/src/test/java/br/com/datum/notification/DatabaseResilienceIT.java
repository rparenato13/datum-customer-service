package br.com.datum.notification;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"notification.consumer.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=500", "spring.datasource.hikari.validation-timeout=250",
        "spring.datasource.hikari.data-source-properties.options=-c statement_timeout=500 -c lock_timeout=200 -c idle_in_transaction_session_timeout=15000"})
class DatabaseResilienceIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("postgres").withUsername("datum_admin").withPassword("admin_test_only")
            .withEnv("CUSTOMER_DB_PASSWORD", "customer_test_only")
            .withEnv("NOTIFICATION_DB_PASSWORD", "notification_test_only")
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-init/01-create-databases.sh"),
                    "/docker-entrypoint-initdb.d/01-create-databases.sh");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/notification_db");
        registry.add("spring.datasource.username", () -> "notification_app");
        registry.add("spring.datasource.password", () -> "notification_test_only");
    }
    @Autowired javax.sql.DataSource dataSource;
    HikariDataSource pool;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired TransactionTemplate transaction;

    @BeforeEach
    void clean() throws Exception {
        pool = dataSource.unwrap(HikariDataSource.class);
        jdbc.update("DELETE FROM notification_records");
        jdbc.update("DELETE FROM notification_event_inbox");
    }

    @Test
    void shouldApplyDriverSessionAndTransactionLimitsToPhysicalConnections() {
        assertEquals("500ms", jdbc.queryForObject("SHOW statement_timeout", String.class));
        assertEquals("200ms", jdbc.queryForObject("SHOW lock_timeout", String.class));
        assertEquals("15s", jdbc.queryForObject("SHOW idle_in_transaction_session_timeout", String.class));
        assertEquals("2", String.valueOf(pool.getDataSourceProperties().get("connectTimeout")));
        assertEquals("15", String.valueOf(pool.getDataSourceProperties().get("socketTimeout")));
        assertEquals(10, ((AbstractPlatformTransactionManager) manager).getDefaultTimeout());
    }

    @Test
    void shouldBoundPoolWaitAndRecoverAfterConnectionIsReturned() throws Exception {
        try (var first = pool.getConnection(); var second = pool.getConnection()) {
            Instant start = Instant.now();
            assertThrows(java.sql.SQLTransientConnectionException.class, pool::getConnection);
            assertTrue(Duration.between(start, Instant.now()).toMillis() >= 400);
            assertTrue(Duration.between(start, Instant.now()).toMillis() < 3000);
        }
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
    }

    @Test
    void shouldCancelLongStatementRollbackWritesAndKeepPoolUsable() {
        Instant start = Instant.now();
        var failure = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO notification_event_inbox (consumer_id, event_id, customer_id, occurred_at, fingerprint, outcome) VALUES ('customer-notification-service.created.v1', 'c3df571a-63a9-44e5-b728-dda3a8d289c0', 123, now(), repeat('a', 64), 'PROCESSING')");
            jdbc.execute("SELECT pg_sleep(5)");
        }));
        assertSqlState(failure, "57014");
        assertTrue(Duration.between(start, Instant.now()).toMillis() < 3000);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_event_inbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
    }

    @Test
    void shouldStopWaitingOnContendedLockWithoutChangingInbox() throws Exception {
        jdbc.update("INSERT INTO notification_event_inbox (consumer_id, event_id, customer_id, occurred_at, fingerprint, outcome, processed_at) VALUES ('customer-notification-service.created.v1', 'c3df571a-63a9-44e5-b728-dda3a8d289c0', 123, now(), repeat('a', 64), 'NOTIFICATION_REGISTERED', now())");
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/notification_db";
        try (var other = DriverManager.getConnection(url, "notification_app", "notification_test_only")) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) { statement.execute("SELECT event_id FROM notification_event_inbox FOR UPDATE"); }
            Instant start = Instant.now();
            var failure = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status ->
                    jdbc.update("UPDATE notification_event_inbox SET customer_id = 124")));
            assertSqlState(failure, "55P03");
            assertTrue(Duration.between(start, Instant.now()).toMillis() < 2000);
            other.rollback();
        }
        assertEquals(123, jdbc.queryForObject("SELECT customer_id FROM notification_event_inbox", Long.class));
    }

    private void assertSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && expected.equals(sql.getSQLState())) return;
        }
        fail("Falha nao corresponde ao timeout PostgreSQL esperado: " + expected);
    }

}
