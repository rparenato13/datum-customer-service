package br.com.datum.customer.api;

import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Notifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SecurityTestFixture
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "customer.outbox.publisher-enabled=false", "customer.status-consumer.enabled=false", "customer.score.read-timeout-ms=100"})
class OpenApiContractIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("postgres").withUsername("datum_admin").withPassword("admin_test_only")
            .withEnv("CUSTOMER_DB_PASSWORD", "customer_test_only")
            .withEnv("NOTIFICATION_DB_PASSWORD", "notification_test_only")
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-init/01-create-databases.sh"),
                    "/docker-entrypoint-initdb.d/01-create-databases.sh");
    static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort()
            .usingFilesUnderClasspath("score-provider").notifier(new Notifier() {
                public void info(String message) { }
                public void error(String message) { }
                public void error(String message, Throwable cause) { }
            }));

    @BeforeAll static void start() { PROVIDER.start(); }
    @AfterAll static void stop() { PROVIDER.stop(); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/customer_db");
        registry.add("spring.datasource.username", () -> "customer_app");
        registry.add("spring.datasource.password", () -> "customer_test_only");
        registry.add("customer.score.base-url", PROVIDER::baseUrl);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    JsonNode document;

    @BeforeEach
    void cleanDisposableDataAndReadSpecification() {
        http.getRestTemplate().setInterceptors(List.of());
        jdbc.update("DELETE FROM customer_event_inbox");
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
        PROVIDER.resetToDefaultMappings();
        PROVIDER.resetRequests();
        document = http.getForObject("/v3/api-docs", JsonNode.class);
        assertNotNull(document);
    }

    @Test
    void shouldExecuteDocumentedExamplesThroughRealDatabaseSecurityAndProvider() {
        var created = call("/customers", "post", "ADMIN", body("/customers", "post"));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertFieldsMatchExample(created.getBody(), success("/customers", "post", "201"), "id", "createdAt", "updatedAt");
        String location = created.getHeaders().getLocation().toString();
        long id = created.getBody().path("id").asLong();
        assertEquals("/customers/" + id, location);
        assertProblem(call("/customers", "post", "ADMIN", body("/customers", "post")), "/customers", "post", "409", "CPF_ALREADY_REGISTERED");
        var found = call(location, "get", "USER", null);
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertEquals(created.getBody(), found.getBody());
        var page = call("/customers", "get", "USER", null);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        assertEquals(1, page.getBody().path("totalElements").asInt());
        var search = call("/customers/search?name=DEMONSTRACAO", "get", "ADMIN", null);
        assertEquals(HttpStatus.OK, search.getStatusCode());
        assertEquals(created.getBody(), search.getBody().path("content").get(0));
        var score = call(location + "/score", "get", "USER", null);
        assertEquals(HttpStatus.OK, score.getStatusCode());
        assertFieldsMatchExample(score.getBody(), success("/customers/{id}/score", "get", "200"), "customerId");
        assertEquals("no-store", score.getHeaders().getCacheControl());
        var updated = call(location, "put", "ADMIN", body("/customers/{id}", "put"));
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertFieldsMatchExample(updated.getBody(), success("/customers/{id}", "put", "200"), "id", "createdAt", "updatedAt");
        assertEquals(updated.getBody(), call(location, "put", "ADMIN", body("/customers/{id}", "put")).getBody());
        var deleted = call(location, "delete", "ADMIN", null);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        assertNull(deleted.getBody());
        assertProblem(call(location, "delete", "ADMIN", null), "/customers/{id}", "delete", "404", "CUSTOMER_NOT_FOUND");
        assertProblem(call(location, "get", "USER", null), "/customers/{id}", "get", "404", "CUSTOMER_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings={"createCustomer", "replaceCustomer", "deleteCustomer", "findCustomer", "listCustomers", "searchCustomers", "findCustomerScore"})
    void shouldMatchDocumentedAuthenticationAndRoleErrorsForEveryOperation(String operationId) {
        document.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(method -> {
            if (method.getValue().path("operationId").asText().equals(operationId)) {
                String template = path.getKey();
                String target = template.replace("{id}", "123");
                JsonNode request = method.getValue().path("requestBody").path("content").path("application/json").at("/examples/valid/value");
                if (request.isMissingNode()) request = null;
                assertProblem(call(target, method.getKey(), null, request), template, method.getKey(), "401", "AUTHENTICATION_REQUIRED");
                String role = method.getKey().equals("get") ? "OTHER_ROLE" : "USER";
                assertProblem(call(target, method.getKey(), role, request), template, method.getKey(), "403", "ACCESS_DENIED");
            }
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @Test
    void shouldMatchDocumentedValidationAndInvalidJsonExamples() {
        var invalid = body("/customers", "post").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("name", "");
        assertProblem(call("/customers", "post", "ADMIN", invalid), "/customers", "post", "400", "VALIDATION_ERROR");
        var unknown = body("/customers", "post").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("version", 0);
        assertProblem(call("/customers", "post", "ADMIN", unknown), "/customers", "post", "400", "INVALID_JSON");
        assertProblem(call("/customers/0", "get", "USER", null), "/customers/{id}", "get", "400", "INVALID_REQUEST");
        assertProblem(call("/customers?size=101", "get", "USER", null), "/customers", "get", "400", "INVALID_REQUEST");
        assertProblem(call("/customers/search", "get", "USER", null), "/customers/search", "get", "400", "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(ints={404, 502, 503, 504})
    void shouldMatchDocumentedScoreFailureExamples(int status) {
        var created = call("/customers", "post", "ADMIN", body("/customers", "post"));
        String location = created.getHeaders().getLocation().toString();
        if (status == 404) PROVIDER.stubFor(get(urlPathEqualTo("/scores/16899535009")).willReturn(aResponse().withStatus(404)));
        if (status == 502) PROVIDER.stubFor(get(urlPathEqualTo("/scores/16899535009")).willReturn(okJson("{}")));
        if (status == 503) PROVIDER.stubFor(get(urlPathEqualTo("/scores/16899535009")).willReturn(aResponse().withStatus(503)));
        if (status == 504) PROVIDER.stubFor(get(urlPathEqualTo("/scores/16899535009")).willReturn(okJson("{}").withFixedDelay(300)));
        String code = switch (status) {
            case 404 -> "SCORE_NOT_FOUND";
            case 502 -> "SCORE_INVALID_RESPONSE";
            case 503 -> "SCORE_UNAVAILABLE";
            default -> "SCORE_TIMEOUT";
        };
        assertProblem(call(location + "/score", "get", "USER", null), "/customers/{id}/score", "get", Integer.toString(status), code);
    }

    private JsonNode body(String path, String method) {
        return document.path("paths").path(path).path(method).path("requestBody").path("content").path("application/json").at("/examples/valid/value");
    }
    private JsonNode success(String path, String method, String status) {
        return document.path("paths").path(path).path(method).path("responses").path(status).path("content").path("application/json").at("/examples/success/value");
    }
    private ResponseEntity<JsonNode> call(String path, String method, String role, JsonNode body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (role != null) headers.setBearerAuth(TestJwtSupport.token(role));
        return http.exchange(path, HttpMethod.valueOf(method.toUpperCase(java.util.Locale.ROOT)), new HttpEntity<>(body, headers), JsonNode.class);
    }
    private void assertProblem(ResponseEntity<JsonNode> actual, String path, String method, String status, String code) {
        var expected = document.path("paths").path(path).path(method).path("responses").path(status).path("content")
                .path("application/problem+json").path("examples").path(code).path("value");
        assertFalse(expected.isMissingNode());
        assertEquals(Integer.parseInt(status), actual.getStatusCode().value());
        assertTrue(actual.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertFieldsMatchExample(actual.getBody(), expected, "instance");
    }
    private void assertFieldsMatchExample(JsonNode actual, JsonNode expected, String... variable) {
        var skipped = java.util.Set.of(variable);
        expected.fields().forEachRemaining(field -> {
            if (!skipped.contains(field.getKey())) assertEquals(field.getValue(), actual.path(field.getKey()), field.getKey());
        });
    }
}
