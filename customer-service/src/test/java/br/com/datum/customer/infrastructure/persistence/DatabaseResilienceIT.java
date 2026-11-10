package br.com.datum.customer.infrastructure.persistence;

import br.com.datum.customer.security.SecurityTestFixture;
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
@SecurityTestFixture
@SpringBootTest(properties={"customer.outbox.publisher-enabled=false", "customer.status-consumer.enabled=false",
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
                + POSTGRES.getMappedPort(5432) + "/customer_db");
        registry.add("spring.datasource.username", () -> "customer_app");
        registry.add("spring.datasource.password", () -> "customer_test_only");
    }
    @Autowired javax.sql.DataSource dataSource;
    HikariDataSource pool;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired TransactionTemplate transaction;

    @BeforeEach
    void clean() throws Exception {
        pool = dataSource.unwrap(HikariDataSource.class);
        jdbc.update("DELETE FROM customer_event_inbox");
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
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
            jdbc.update("INSERT INTO customers (name, cpf, email, status, created_at, updated_at) VALUES ('Timeout Test', '16899535009', 'timeout@example.test', 'ACTIVE', now(), now())");
            jdbc.execute("SELECT pg_sleep(5)");
        }));
        assertSqlState(failure, "57014");
        assertTrue(Duration.between(start, Instant.now()).toMillis() < 3000);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
    }

    @Test
    void shouldStopWaitingOnContendedLockWithoutChangingCustomer() throws Exception {
        jdbc.update("INSERT INTO customers (name, cpf, email, status, created_at, updated_at) VALUES ('Lock Test', '16899535009', 'lock@example.test', 'ACTIVE', now(), now())");
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/customer_db";
        try (var other = DriverManager.getConnection(url, "customer_app", "customer_test_only")) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) { statement.execute("SELECT id FROM customers FOR UPDATE"); }
            Instant start = Instant.now();
            var failure = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status ->
                    jdbc.update("UPDATE customers SET status = 'INACTIVE', version = version + 1")));
            assertSqlState(failure, "55P03");
            assertTrue(Duration.between(start, Instant.now()).toMillis() < 2000);
            other.rollback();
        }
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM customers", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT version FROM customers", Long.class));
    }

    private void assertSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && expected.equals(sql.getSQLState())) return;
        }
        fail("Falha nao corresponde ao timeout PostgreSQL esperado: " + expected);
    }

}
