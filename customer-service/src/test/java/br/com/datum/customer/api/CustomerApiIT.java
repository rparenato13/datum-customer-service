package br.com.datum.customer.api;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import br.com.datum.customer.infrastructure.persistence.CustomerRepository;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "customer.outbox.publisher-enabled=false")
@SecurityTestFixture
class CustomerApiIT {

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
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CustomerRepository repository;

    @Autowired
    CustomerService service;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearDisposableDatabase() {
        TestJwtSupport.authenticateAsAdmin(http);
        // Este banco pertence exclusivamente ao container de teste desta classe.
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE"})
    void shouldCreateAndReadCommittedCustomer(String status) {
        var payload = validPayload();
        payload.put("status", status);
        var created = http.postForEntity("/customers", payload, JsonNode.class);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        var body = created.getBody();
        assertNotNull(body);
        assertTrue(body.path("id").asLong() > 0);
        assertEquals("Cliente de Teste", body.path("name").asText());
        assertEquals("52998224725", body.path("cpf").asText());
        assertEquals("cliente@example.test", body.path("email").asText());
        assertEquals(status, body.path("status").asText());
        assertEquals(Instant.parse(body.path("createdAt").asText()), Instant.parse(body.path("updatedAt").asText()));
        assertFalse(body.has("nameNormalized"));
        assertFalse(body.has("deletedAt"));
        assertEquals("/customers/" + body.path("id").asLong(), created.getHeaders().getLocation().toString());

        var fetched = http.getForEntity(created.getHeaders().getLocation().toString(), JsonNode.class);
        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        assertEquals(body, fetched.getBody());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @Test
    void shouldReturnNotFoundForMissingCustomer() {
        assertProblem(http.getForEntity("/customers/9223372036854775807", JsonNode.class),
                HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
    }

    @Test
    void shouldNotReturnLogicallyDeletedCustomer() {
        var customer = repository.saveAndFlush(new CustomerEntity("Cliente de Teste", "52998224725", "cliente@example.test"));
        customer.markDeleted();
        repository.saveAndFlush(customer);

        assertProblem(http.getForEntity("/customers/" + customer.getId(), JsonNode.class),
                HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectCpfAlreadyRegisteredIncludingDeletedCustomers(boolean deleted, CapturedOutput output) {
        var customer = repository.saveAndFlush(new CustomerEntity("Cliente de Teste", "52998224725", "cliente@example.test"));
        if (deleted) {
            customer.markDeleted();
            repository.saveAndFlush(customer);
        }

        var response = http.postForEntity("/customers", validPayload(), JsonNode.class);

        assertProblem(response, HttpStatus.CONFLICT, "CPF_ALREADY_REGISTERED");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        assertFalse(response.getBody().toString().contains("52998224725"));
        assertFalse(response.getBody().toString().contains("uk_customers_cpf"));
        assertFalse(output.getAll().contains("52998224725"));
        assertFalse(output.getAll().contains("cliente@example.test"));
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void shouldRejectInvalidFieldsWithoutWritingOrEchoingValues(Map<String, Object> payload, CapturedOutput output) {
        var response = http.postForEntity("/customers", payload, JsonNode.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertTrue(response.getBody().path("errors").isArray());
        for (var error : response.getBody().path("errors")) {
            assertTrue(error.has("field"));
            assertTrue(error.has("message"));
            assertFalse(error.has("rejectedValue"));
        }
        assertFalse(response.getBody().toString().contains("52998224725"));
        assertFalse(output.getAll().contains("52998224725"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    static Stream<Map<String, Object>> invalidPayloads() {
        return Stream.of(
                changed("name", null), changed("name", "   "), changed("name", "a".repeat(151)),
                changed("cpf", null), changed("cpf", "52998224724"), changed("cpf", "00000000000"),
                changed("cpf", "529.982.247-25"), changed("cpf", "123"),
                changed("email", null), changed("email", ""), changed("email", "email-invalido"),
                changed("email", "a".repeat(245) + "@example.test"), changed("status", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "", "{\"status\":\"BLOCKED\"}", "{\"id\":1}"})
    void shouldRejectMalformedJsonAndUnknownProperties(String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertProblem(http.postForEntity("/customers", new HttpEntity<>(json, headers), JsonNode.class),
                HttpStatus.BAD_REQUEST, "INVALID_JSON");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void shouldRejectInvalidIds(String id) {
        assertProblem(http.getForEntity("/customers/" + id, JsonNode.class),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @Test
    void shouldCreateOnlyOneCustomerWhenSameCpfArrivesConcurrently() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return http.postForEntity("/customers", validPayload(), JsonNode.class);
            });
            var second = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return http.postForEntity("/customers", validPayload(), JsonNode.class);
            });
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(response -> response.getStatusCode() == HttpStatus.CREATED).count());
            assertEquals(1, responses.stream().filter(response -> response.getStatusCode() == HttpStatus.CONFLICT).count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE"})
    void shouldReplaceCustomerDetailsAndPreserveIdentity(String status) {
        var customer = seedCustomer();
        var before = http.getForEntity("/customers/" + customer.getId(), JsonNode.class).getBody();
        var payload = changed("cpf", "11144477735");
        payload.put("name", "  Nome Atualizado  ");
        payload.put("email", "atualizado@example.test");
        payload.put("status", status);

        var response = put(customer.getId(), payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body);
        assertEquals(customer.getId().longValue(), body.path("id").asLong());
        assertEquals("Nome Atualizado", body.path("name").asText());
        assertEquals("11144477735", body.path("cpf").asText());
        assertEquals("atualizado@example.test", body.path("email").asText());
        assertEquals(status, body.path("status").asText());
        assertEquals(before.path("createdAt"), body.path("createdAt"));
        assertTrue(Instant.parse(body.path("updatedAt").asText()).isAfter(Instant.parse(before.path("updatedAt").asText())));
        assertEquals(body, http.getForEntity("/customers/" + customer.getId(), JsonNode.class).getBody());
        assertEquals("nome atualizado", jdbc.queryForObject("SELECT name_normalized FROM customers WHERE id = ?", String.class, customer.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @Test
    void shouldKeepSameStateWhenPutIsRepeated() {
        var customer = seedCustomer();
        var first = put(customer.getId(), changed("name", "Nome Atualizado"));
        var second = put(customer.getId(), changed("name", "Nome Atualizado"));

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals(first.getBody(), second.getBody());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRollbackAllUpdatedFieldsWhenCpfIsReserved(boolean deleted, CapturedOutput output) {
        var target = seedCustomer();
        var owner = repository.saveAndFlush(new CustomerEntity("Outro Cliente", "11144477735", "outro@example.test"));
        if (deleted) {
            owner.markDeleted();
            repository.saveAndFlush(owner);
        }
        var before = http.getForEntity("/customers/" + target.getId(), JsonNode.class).getBody();
        var payload = changed("cpf", "11144477735");
        payload.put("name", "Nome que não deve persistir");
        payload.put("email", "nao-persiste@example.test");
        payload.put("status", "INACTIVE");

        var response = put(target.getId(), payload);

        assertProblem(response, HttpStatus.CONFLICT, "CPF_ALREADY_REGISTERED");
        assertEquals(before, http.getForEntity("/customers/" + target.getId(), JsonNode.class).getBody());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        assertFalse(response.getBody().toString().contains("11144477735"));
        assertFalse(output.getAll().contains("11144477735"));
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void shouldRejectInvalidUpdateWithoutChangingCustomer(Map<String, Object> payload) {
        var customer = seedCustomer();
        var before = http.getForEntity("/customers/" + customer.getId(), JsonNode.class).getBody();

        var response = put(customer.getId(), payload);

        assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertTrue(response.getBody().path("errors").isArray());
        assertFalse(response.getBody().toString().contains("52998224725"));
        assertEquals(before, http.getForEntity("/customers/" + customer.getId(), JsonNode.class).getBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "", "{\"status\":\"BLOCKED\"}", "{\"id\":1}"})
    void shouldRejectMalformedUpdateWithoutChangingCustomer(String json) {
        var customer = seedCustomer();
        var before = http.getForEntity("/customers/" + customer.getId(), JsonNode.class).getBody();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = http.exchange("/customers/" + customer.getId(), HttpMethod.PUT,
                new HttpEntity<>(json, headers), JsonNode.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "INVALID_JSON");
        assertEquals(before, http.getForEntity("/customers/" + customer.getId(), JsonNode.class).getBody());
    }

    @ParameterizedTest
    @MethodSource("invalidMutationIds")
    void shouldRejectInvalidMutationIds(HttpMethod method, String id) {
        assertProblem(http.exchange("/customers/" + id, method,
                new HttpEntity<>(validPayload()), JsonNode.class), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    static Stream<Arguments> invalidMutationIds() {
        return Stream.of(HttpMethod.PUT, HttpMethod.DELETE).flatMap(method ->
                Stream.of("0", "-1", "abc", "9223372036854775808").map(id -> Arguments.of(method, id)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNotUpdateMissingOrDeletedCustomer(boolean deleted) {
        var id = deleted ? seedDeletedCustomer().getId() : Long.MAX_VALUE;
        assertProblem(put(id, validPayload()), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertEquals(deleted ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNotDeleteMissingOrAlreadyDeletedCustomer(boolean deleted) {
        var id = deleted ? seedDeletedCustomer().getId() : Long.MAX_VALUE;
        assertProblem(http.exchange("/customers/" + id, HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class),
                HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertEquals(deleted ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE"})
    void shouldLogicallyDeleteWithoutChangingStatusOrFreeingCpf(String status) {
        var customer = seedCustomer();
        customer.changeStatus(CustomerStatus.valueOf(status));
        repository.saveAndFlush(customer);

        var response = http.exchange("/customers/" + customer.getId(), HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ? AND deleted_at IS NOT NULL AND status = ?",
                Integer.class, customer.getId(), status));
        var deletedAt = jdbc.queryForObject("SELECT deleted_at::text FROM customers WHERE id = ?", String.class, customer.getId());
        assertProblem(http.getForEntity("/customers/" + customer.getId(), JsonNode.class), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertProblem(put(customer.getId(), validPayload()), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertProblem(http.postForEntity("/customers", validPayload(), JsonNode.class), HttpStatus.CONFLICT, "CPF_ALREADY_REGISTERED");
        assertProblem(http.exchange("/customers/" + customer.getId(), HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class),
                HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertEquals(deletedAt, jdbc.queryForObject("SELECT deleted_at::text FROM customers WHERE id = ?", String.class, customer.getId()));
    }

    @Test
    void shouldWaitForConcurrentDeletionAndNeverRestoreTheCustomer() throws Exception {
        var customer = seedCustomer();
        var deletionFlushed = new CountDownLatch(1);
        var commitDeletion = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> transactionTemplate.executeWithoutResult(transaction -> {
                service.delete(customer.getId());
                deletionFlushed.countDown();
                try {
                    if (!commitDeletion.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Tempo esgotado aguardando liberação da transação de teste");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            try {
                assertTrue(deletionFlushed.await(10, TimeUnit.SECONDS));
                var update = executor.submit(() -> put(customer.getId(), changed("name", "Não restaurar")));
                // Observa o SELECT de alteração aguardando o lock real do PostgreSQL.
                await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertTrue(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND usename = current_user AND wait_event_type = 'Lock' AND query ILIKE '%for%update%'",
                        Integer.class) > 0));
                commitDeletion.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                assertProblem(update.get(10, TimeUnit.SECONDS), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
            } finally {
                commitDeletion.countDown();
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers WHERE deleted_at IS NOT NULL", Integer.class));
        assertProblem(http.getForEntity("/customers/" + customer.getId(), JsonNode.class), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
    }

    @Test
    void shouldDeleteOnlyOnceWhenTwoDeletesArriveConcurrently() throws Exception {
        var customer = seedCustomer();
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return http.exchange("/customers/" + customer.getId(), HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class);
            });
            var second = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return http.exchange("/customers/" + customer.getId(), HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class);
            });
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(response -> response.getStatusCode() == HttpStatus.NO_CONTENT).count());
            assertEquals(1, responses.stream().filter(response -> response.getStatusCode() == HttpStatus.NOT_FOUND).count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers WHERE deleted_at IS NOT NULL", Integer.class));
        }
    }

    private ResponseEntity<JsonNode> put(Long id, Map<String, Object> payload) {
        return http.exchange("/customers/" + id, HttpMethod.PUT, new HttpEntity<>(payload), JsonNode.class);
    }

    private CustomerEntity seedCustomer() {
        return repository.saveAndFlush(new CustomerEntity("Cliente de Teste", "52998224725", "cliente@example.test"));
    }

    private CustomerEntity seedDeletedCustomer() {
        var customer = seedCustomer();
        customer.markDeleted();
        return repository.saveAndFlush(customer);
    }

    private static Map<String, Object> validPayload() {
        var payload = new HashMap<String, Object>();
        payload.put("name", "  Cliente de Teste  ");
        payload.put("cpf", "52998224725");
        payload.put("email", "cliente@example.test");
        payload.put("status", "ACTIVE");
        return payload;
    }

    private static Map<String, Object> changed(String field, Object value) {
        var payload = validPayload();
        payload.put(field, value);
        return payload;
    }

    private void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        assertNotNull(response.getBody());
        assertEquals(status.value(), response.getBody().path("status").asInt());
        assertEquals(code, response.getBody().path("code").asText());
        assertFalse(response.getBody().has("trace"));
        assertFalse(response.getBody().has("exception"));
    }
}
