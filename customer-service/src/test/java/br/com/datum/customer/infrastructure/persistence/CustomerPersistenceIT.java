package br.com.datum.customer.infrastructure.persistence;

import br.com.datum.customer.domain.CustomerStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF",
                "customer.outbox.publisher-enabled=false", "customer.status-consumer.enabled=false"})
class CustomerPersistenceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("postgres")
            .withUsername("datum_admin")
            .withPassword("admin_test_only")
            .withEnv("CUSTOMER_DB_PASSWORD", "customer_test_only")
            .withEnv("NOTIFICATION_DB_PASSWORD", "notification_test_only")
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-init/01-create-databases.sh"),
                    "/docker-entrypoint-initdb.d/01-create-databases.sh");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/customer_db");
        registry.add("spring.datasource.username", () -> "customer_app");
        registry.add("spring.datasource.password", () -> "customer_test_only");
    }

    @Autowired
    CustomerRepository repository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    TestRestTemplate http;

    @Test
    void shouldUpgradePendingV3EventWithoutChangingItsIdentityOrPayload() {
        var legacy = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .schemas("t12_legacy").defaultSchema("t12_legacy").target("3").load();
        legacy.migrate();
        var event = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO t12_legacy.customers (id,name,cpf,email) VALUES (123,'Legacy','16899535009','legacy@example.test')");
        jdbc.update("INSERT INTO t12_legacy.customer_outbox (event_id,customer_id,event_type,schema_version,occurred_at,payload) "
                + "VALUES (?,123,'CUSTOMER_CREATED',1,now(),'original bytes')", event);
        var upgrade = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .schemas("t12_legacy").defaultSchema("t12_legacy").load();
        assertEquals(1, upgrade.migrate().migrationsExecuted);
        var row = jdbc.queryForMap("SELECT event_id, correlation_id, payload, state, attempt_count FROM t12_legacy.customer_outbox");
        assertEquals(event, row.get("event_id"));
        assertEquals(event, row.get("correlation_id"));
        assertEquals("original bytes", row.get("payload"));
        assertEquals("PENDING", row.get("state"));
        assertEquals(0L, row.get("attempt_count"));
        assertEquals(0, upgrade.migrate().migrationsExecuted);
    }

    @Test
    void shouldMigrateOnceAndUseNonAdministrativeCredentials() {
        assertEquals(4, flyway.info().applied().length);
        assertEquals("4", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("customer_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertFalse(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class));
        assertFalse(jdbc.queryForObject(
                "SELECT has_database_privilege(current_user, 'notification_db', 'CONNECT')", Boolean.class));
    }

    @Test
    void shouldReportHealthyWithTheRealDatabase() {
        var response = http.getForEntity("/actuator/health", JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().path("status").asText());
    }

    @Test
    @Transactional
    void shouldPersistAndReloadCustomerWithGeneratedFields() {
        var saved = repository.saveAndFlush(customer());
        var id = saved.getId();
        entityManager.clear();

        var reloaded = repository.findByIdAndDeletedAtIsNull(id).orElseThrow();
        assertNotNull(id);
        assertEquals("Cliente de Teste", reloaded.getName());
        assertEquals("cliente de teste", reloaded.getNameNormalized());
        assertEquals("52998224725", reloaded.getCpf());
        assertEquals("cliente@example.test", reloaded.getEmail());
        assertEquals(CustomerStatus.ACTIVE, reloaded.getStatus());
        assertNotNull(reloaded.getCreatedAt());
        assertEquals(reloaded.getCreatedAt(), reloaded.getUpdatedAt());
        assertNull(reloaded.getDeletedAt());
    }

    @Test
    @Transactional
    void shouldUpdateStatusAndKeepCreationTimestamp() {
        var saved = repository.saveAndFlush(customer());
        entityManager.refresh(saved);
        var createdAt = saved.getCreatedAt();
        var initialUpdatedAt = saved.getUpdatedAt();

        saved.updateDetails("Nome Alterado", saved.getCpf(), "outro@example.test");
        saved.changeStatus(CustomerStatus.INACTIVE);
        repository.saveAndFlush(saved);
        entityManager.clear();

        var reloaded = repository.findByIdAndDeletedAtIsNull(saved.getId()).orElseThrow();
        assertEquals("nome alterado", reloaded.getNameNormalized());
        assertEquals("outro@example.test", reloaded.getEmail());
        assertEquals(CustomerStatus.INACTIVE, reloaded.getStatus());
        assertEquals(createdAt, reloaded.getCreatedAt());
        assertTrue(reloaded.getUpdatedAt().isAfter(initialUpdatedAt));
    }

    @Test
    @Transactional
    void shouldRejectDuplicateCpfAtDatabaseLevel() {
        repository.saveAndFlush(customer());
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(customer()));
    }

    @Test
    @Transactional
    void shouldHideLogicallyDeletedCustomerAndKeepItsCpfReserved() {
        var saved = repository.saveAndFlush(customer());
        saved.markDeleted();
        repository.saveAndFlush(saved);
        entityManager.clear();

        assertTrue(repository.findByIdAndDeletedAtIsNull(saved.getId()).isEmpty());
        assertTrue(repository.findAllByDeletedAtIsNull(PageRequest.of(0, 10)).isEmpty());
        assertTrue(repository.existsByCpf(saved.getCpf()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM customers WHERE id = ? AND deleted_at IS NOT NULL AND status = 'ACTIVE'",
                Integer.class, saved.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(customer()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE customers SET name = NULL WHERE id = ?",
            "UPDATE customers SET name = '   ' WHERE id = ?",
            "UPDATE customers SET cpf = '123' WHERE id = ?",
            "UPDATE customers SET email = '   ' WHERE id = ?",
            "UPDATE customers SET status = 'BLOCKED' WHERE id = ?"
    })
    @Transactional
    void shouldEnforceSchemaConstraints(String sql) {
        var saved = repository.saveAndFlush(customer());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, saved.getId()));
    }

    @Test
    void shouldRollbackCustomerWhenTransactionIsNotCommitted() {
        var before = jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);
        transactionTemplate.executeWithoutResult(transaction -> {
            repository.saveAndFlush(customer());
            transaction.setRollbackOnly();
        });
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM customers", Long.class));
    }

    private CustomerEntity customer() {
        return new CustomerEntity("  Cliente de Teste  ", "52998224725", "cliente@example.test");
    }
}
