package br.com.datum.customer.api;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import br.com.datum.customer.infrastructure.persistence.CustomerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

    @BeforeEach
    void clearDisposableDatabase() {
        // Este banco pertence exclusivamente ao container de teste desta classe.
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
