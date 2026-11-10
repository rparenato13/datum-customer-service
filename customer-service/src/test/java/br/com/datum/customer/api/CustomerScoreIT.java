package br.com.datum.customer.api;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import br.com.datum.customer.infrastructure.persistence.CustomerRepository;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Notifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "customer.outbox.publisher-enabled=false")
@SecurityTestFixture
@ExtendWith(OutputCaptureExtension.class)
class CustomerScoreIT {

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

    @BeforeAll
    static void startProvider() {
        PROVIDER.start();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/customer_db");
        registry.add("spring.datasource.username", () -> "customer_app");
        registry.add("spring.datasource.password", () -> "customer_test_only");
        registry.add("customer.score.base-url", PROVIDER::baseUrl);
    }

    @Autowired TestRestTemplate http;
    @Autowired CustomerRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDisposableData() {
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
        http.getRestTemplate().setInterceptors(List.of());
        PROVIDER.resetToDefaultMappings();
        PROVIDER.resetRequests();
    }

    @ParameterizedTest
    @MethodSource("readers")
    void shouldReadScoreForAvailableCustomerWithEitherRoleAndBusinessStatus(String role, CustomerStatus status,
            CapturedOutput output) {
        var customer = save("16899535009", status);
        var before = jdbc.queryForMap("SELECT * FROM customers WHERE id = ?", customer.getId());
        var response = get(customer.getId().toString(), TestJwtSupport.token(role));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(3, response.getBody().size());
        assertEquals(customer.getId(), response.getBody().path("customerId").asLong());
        assertEquals(750, response.getBody().path("score").asInt());
        assertEquals("LOW_RISK", response.getBody().path("classification").asText());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertFalse(response.getBody().toString().contains(customer.getCpf()));
        assertFalse(output.getAll().contains(customer.getCpf()));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM customers WHERE id = ?", customer.getId()));
        PROVIDER.verify(1, getRequestedFor(urlEqualTo("/scores/" + customer.getCpf()))
                .withoutHeader("Authorization").withoutHeader("Cookie"));
    }

    static Stream<Arguments> readers() {
        return Stream.of("USER", "ADMIN").flatMap(role -> Stream.of(CustomerStatus.values())
                .map(status -> Arguments.of(role, status)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt"})
    void shouldRejectMissingOrInvalidTokenBeforeAnyExternalCall(String token) {
        var customer = save("16899535009", CustomerStatus.ACTIVE);
        assertProblem(get(customer.getId().toString(), token), HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        assertEquals(0, PROVIDER.getAllServeEvents().size());
    }

    @Test
    void shouldRejectTokenWithoutAllowedRoleBeforeCallingProvider() {
        var customer = save("16899535009", CustomerStatus.ACTIVE);
        assertProblem(get(customer.getId().toString(), TestJwtSupport.token("OTHER")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertEquals(0, PROVIDER.getAllServeEvents().size());
    }

    @Test
    void shouldReturnCustomerNotFoundWithoutCallingProvider() {
        assertProblem(get(Long.toString(Long.MAX_VALUE), TestJwtSupport.token("USER")), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertEquals(0, PROVIDER.getAllServeEvents().size());
    }

    @Test
    void shouldNotConsultScoreForLogicallyDeletedCustomer() {
        var customer = save("16899535009", CustomerStatus.ACTIVE);
        customer.markDeleted();
        repository.saveAndFlush(customer);
        assertProblem(get(customer.getId().toString(), TestJwtSupport.token("USER")), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertEquals(0, PROVIDER.getAllServeEvents().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void shouldValidateIdBeforeCallingProvider(String id) {
        assertProblem(get(id, TestJwtSupport.token("USER")), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertEquals(0, PROVIDER.getAllServeEvents().size());
    }

    @ParameterizedTest
    @MethodSource("fixtureFailures")
    void shouldTranslateRealProviderFixturesWithoutLeakingDetails(String cpf, HttpStatus status, String code, int calls,
            CapturedOutput output) {
        var customer = save(cpf, CustomerStatus.ACTIVE);
        var token = TestJwtSupport.token("USER");
        long start = System.nanoTime();
        var response = get(customer.getId().toString(), token);
        assertProblem(response, status, code);
        if (status == HttpStatus.GATEWAY_TIMEOUT) {
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2800, "API deve interromper antes dos 3s do provedor.");
        }
        assertFalse(response.getBody().toString().contains(cpf));
        assertFalse(response.getBody().toString().contains("/scores/"));
        assertFalse(response.getBody().toString().contains("localhost"));
        assertFalse(output.getAll().contains(cpf));
        assertFalse(output.getAll().contains(token));
        PROVIDER.verify(calls, getRequestedFor(urlEqualTo("/scores/" + cpf)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    static Stream<Arguments> fixtureFailures() {
        return Stream.of(Arguments.of("52998224725", HttpStatus.NOT_FOUND, "SCORE_NOT_FOUND", 1),
                Arguments.of("11144477735", HttpStatus.SERVICE_UNAVAILABLE, "SCORE_UNAVAILABLE", 2),
                Arguments.of("12345678909", HttpStatus.SERVICE_UNAVAILABLE, "SCORE_UNAVAILABLE", 2),
                Arguments.of("98765432100", HttpStatus.BAD_GATEWAY, "SCORE_INVALID_RESPONSE", 1),
                Arguments.of("01234567890", HttpStatus.GATEWAY_TIMEOUT, "SCORE_TIMEOUT", 1));
    }

    @Test
    void shouldReleaseDatabaseConnectionBeforeWaitingForProvider() {
        var customer = save("16899535009", CustomerStatus.ACTIVE);
        // O provedor observa o PostgreSQL durante a chamada HTTP.
        var transactions = new AtomicInteger(-1);
        PROVIDER.addMockServiceRequestListener((request, response) -> transactions.set(
                jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename = 'customer_app' "
                        + "AND state = 'idle in transaction'", Integer.class)));
        assertEquals(HttpStatus.OK, get(customer.getId().toString(), TestJwtSupport.token("USER")).getStatusCode());
        assertEquals(0, transactions.get(), "Provedor deve observar ausencia de transacao aberta no banco.");
    }

    private CustomerEntity save(String cpf, CustomerStatus status) {
        var customer = new CustomerEntity("Cliente Score", cpf, "score@example.test");
        customer.changeStatus(status);
        return repository.saveAndFlush(customer);
    }

    private ResponseEntity<JsonNode> get(String id, String token) {
        var headers = new HttpHeaders();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return http.exchange("/customers/{id}/score", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class, id);
    }

    private void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        assertEquals(status.value(), response.getBody().path("status").asInt());
        assertEquals(code, response.getBody().path("code").asText());
        assertFalse(response.getBody().has("trace"));
    }
}
