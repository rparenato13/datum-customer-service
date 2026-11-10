package br.com.datum.customer.api;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import br.com.datum.customer.infrastructure.persistence.CustomerRepository;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"customer.outbox.publisher-enabled=false", "customer.status-consumer.enabled=false"})
@SecurityTestFixture
class CustomerQueryIT {

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
    CustomerRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private static final List<String> CPFS = List.of("52998224725", "11144477735", "12345678909",
            "16899535009", "39053344705", "93541134780", "15350946056");

    private List<CustomerEntity> available;

    @BeforeEach
    void prepareDisposableDatabase() {
        TestJwtSupport.authenticateAsAdmin(http);
        jdbc.update("DELETE FROM customer_event_inbox");
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
        available = List.of(
                save("Ana Silva", 0, CustomerStatus.ACTIVE, false),
                save("Bruno Lima", 1, CustomerStatus.INACTIVE, false),
                save("Ana Souza", 2, CustomerStatus.INACTIVE, false),
                save("ALICE Silveira", 3, CustomerStatus.ACTIVE, false));
        save("Ana Excluida", 4, CustomerStatus.ACTIVE, true);
        save("Bruno Excluido", 5, CustomerStatus.INACTIVE, true);
    }

    @Test
    void shouldListWithDefaultsAndExcludeDeletedCustomersFromContentAndTotals() {
        var body = assertPage(http.getForEntity("/customers", JsonNode.class), 0, 20, ids(available), 4, 1);
        assertTrue(body.path("first").asBoolean());
        assertTrue(body.path("last").asBoolean());
        assertEquals("Ana Silva", body.path("content").get(0).path("name").asText());
        assertFalse(body.path("content").get(0).has("deletedAt"));
        assertFalse(body.path("content").get(0).has("nameNormalized"));
        assertFalse(body.has("pageable"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void shouldPageInIdOrderAndReturnEmptyContentPastLastPage(int page) {
        var expected = page == 2 ? List.<Long>of() : ids(available.subList(page * 2, page * 2 + 2));
        var body = assertPage(http.getForEntity("/customers?page={page}&size=2", JsonNode.class, page), page, 2, expected, 4, 2);
        assertEquals(page == 0, body.path("first").asBoolean());
        assertEquals(page >= 1, body.path("last").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE"})
    void shouldFilterAndPaginateByStatus(String status) {
        var expected = available.stream().filter(customer -> customer.getStatus().name().equals(status)).toList();
        assertPage(http.getForEntity("/customers?status={status}", JsonNode.class, status), 0, 20, ids(expected), 2, 1);
        var body = assertPage(http.getForEntity("/customers?status={status}&page=1&size=1", JsonNode.class, status),
                1, 1, List.of(expected.get(1).getId()), 2, 2);
        assertFalse(body.path("first").asBoolean());
        assertTrue(body.path("last").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana", "ANA", "aNa", " Ana ", "sil"})
    void shouldSearchBySubstringIgnoringCaseAndTrimmingEdges(String name) {
        var expected = name.equals("sil") ? List.of(available.get(0), available.get(3))
                : List.of(available.get(0), available.get(2));
        assertPage(http.getForEntity("/customers/search?name={name}", JsonNode.class, name), 0, 20, ids(expected), 2, 1);
    }

    @Test
    void shouldPaginateSearchResults() {
        var body = assertPage(http.getForEntity("/customers/search?name=a&page=1&size=2", JsonNode.class),
                1, 2, ids(available.subList(2, 4)), 4, 2);
        assertTrue(body.path("last").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE"})
    void shouldCombineNameAndStatusFilters(String status) {
        var expected = available.stream().filter(customer -> customer.getName().startsWith("Ana")
                && customer.getStatus().name().equals(status)).toList();
        assertPage(http.getForEntity("/customers/search?name=ANA&status={status}", JsonNode.class, status),
                0, 20, ids(expected), 1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "\\", "' OR 1=1 --"})
    void shouldSearchSpecialCharactersLiterally(String term) {
        var special = save("Cliente " + term + " Especial", 6, CustomerStatus.ACTIVE, false);
        assertPage(http.getForEntity("/customers/search?name={name}", JsonNode.class, term),
                0, 20, List.of(special.getId()), 1, 1);
    }

    @Test
    void shouldPreserveAccentsWhileIgnoringCase() {
        var accented = save("Ágata Lima", 6, CustomerStatus.ACTIVE, false);
        assertPage(http.getForEntity("/customers/search?name={name}", JsonNode.class, "ÁGATA"),
                0, 20, List.of(accented.getId()), 1, 1);
        assertPage(http.getForEntity("/customers/search?name=agata", JsonNode.class),
                0, 20, List.of(), 0, 0);
    }

    @Test
    void shouldReturn200AndEmptyPageWhenNothingMatches() {
        var body = assertPage(http.getForEntity("/customers/search?name=naoexiste", JsonNode.class),
                0, 20, List.of(), 0, 0);
        assertTrue(body.path("first").asBoolean());
        assertTrue(body.path("last").asBoolean());
    }

    @Test
    void shouldReturnEmptyPageWhenOnlyDeletedCustomersMatchStatus() {
        jdbc.update("UPDATE customers SET deleted_at = CURRENT_TIMESTAMP WHERE status = 'ACTIVE'");
        assertPage(http.getForEntity("/customers?status=ACTIVE", JsonNode.class), 0, 20, List.of(), 0, 0);
        assertPage(http.getForEntity("/customers/search?name=ana&status=ACTIVE", JsonNode.class),
                0, 20, List.of(), 0, 0);
    }

    @Test
    void shouldReturnEmptyPageForEmptyDatabase() {
        jdbc.update("DELETE FROM customer_event_inbox");
        jdbc.update("DELETE FROM customer_outbox");
        jdbc.update("DELETE FROM customers");
        assertPage(http.getForEntity("/customers", JsonNode.class), 0, 20, List.of(), 0, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void shouldAcceptPageSizeBoundaries(int size) {
        var count = Math.min(4, size);
        assertPage(http.getForEntity("/customers?size={size}", JsonNode.class, size),
                0, size, ids(available.subList(0, count)), 4, (4 + size - 1) / size);
    }

    @Test
    void shouldAcceptLargestSupportedOffsetWithoutOverflowingPageMetadata() {
        var body = assertPage(http.getForEntity("/customers?page=2147483647&size=1", JsonNode.class),
                Integer.MAX_VALUE, 1, List.of(), 4, 4);
        assertFalse(body.path("first").asBoolean());
        assertTrue(body.path("last").asBoolean());
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void shouldRejectInvalidPaginationAndStatusOnBothEndpoints(String endpoint, String query) {
        assertInvalid(http.getForEntity(endpoint + query, JsonNode.class));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    static Stream<Arguments> invalidQueries() {
        return Stream.of("/customers?", "/customers/search?name=ana&").flatMap(endpoint -> Stream.of(
                "page=-1", "size=0", "size=-1", "size=101", "page=abc", "size=abc",
                "page=2147483648", "size=2147483648", "page=2147483647&size=100",
                "status=BLOCKED", "status=active", "status=").map(query -> Arguments.of(endpoint, query)));
    }

    @Test
    void shouldRequireSearchName() {
        var body = assertInvalid(http.getForEntity("/customers/search", JsonNode.class));
        assertEquals("name", body.path("errors").get(0).path("field").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\u2003"})
    void shouldRejectBlankSearchName(String name) {
        assertInvalid(http.getForEntity("/customers/search?name={name}", JsonNode.class, name));
    }

    @Test
    void shouldEnforceSearchNameLength() {
        assertPage(http.getForEntity("/customers/search?name={name}", JsonNode.class, "z".repeat(150)),
                0, 20, List.of(), 0, 0);
        var body = assertInvalid(http.getForEntity("/customers/search?name={name}", JsonNode.class, "z".repeat(151)));
        assertEquals("name", body.path("errors").get(0).path("field").asText());
    }

    private CustomerEntity save(String name, int cpfIndex, CustomerStatus status, boolean deleted) {
        var customer = new CustomerEntity(name, CPFS.get(cpfIndex), "cliente" + cpfIndex + "@example.test");
        customer.changeStatus(status);
        customer = repository.saveAndFlush(customer);
        if (deleted) {
            customer.markDeleted();
            customer = repository.saveAndFlush(customer);
        }
        return customer;
    }

    private List<Long> ids(List<CustomerEntity> customers) {
        return customers.stream().map(CustomerEntity::getId).toList();
    }

    private JsonNode assertPage(ResponseEntity<JsonNode> response, int page, int size, List<Long> expectedIds,
            long totalElements, int totalPages) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body);
        assertTrue(body.path("content").isArray());
        var actualIds = StreamSupport.stream(body.path("content").spliterator(), false)
                .map(customer -> customer.path("id").asLong()).toList();
        assertEquals(expectedIds, actualIds);
        assertEquals(page, body.path("page").asInt());
        assertEquals(size, body.path("size").asInt());
        assertEquals(totalElements, body.path("totalElements").asLong());
        assertEquals(totalPages, body.path("totalPages").asInt());
        return body;
    }

    private JsonNode assertInvalid(ResponseEntity<JsonNode> response) {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        assertNotNull(response.getBody());
        assertEquals("INVALID_REQUEST", response.getBody().path("code").asText());
        assertFalse(response.getBody().has("trace"));
        assertFalse(response.getBody().toString().contains("52998224725"));
        return response.getBody();
    }
}
