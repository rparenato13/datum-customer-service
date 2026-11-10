package br.com.datum.customer.api;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
@SecurityTestFixture
class OpenApiDocumentationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired Validator validator;
    @MockitoBean CustomerService service;

    @BeforeEach
    void anonymousHttpClient() { http.getRestTemplate().setInterceptors(List.of()); }

    JsonNode spec() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    @Test
    void shouldPublishExactlySevenOperationsWithExplicitResponseMatrixAndSecurity() {
        var document = spec();
        assertTrue(document.path("openapi").asText().startsWith("3.0."));
        assertEquals("Datum Customer API", document.at("/info/title").asText());
        assertEquals("/", document.at("/servers/0/url").asText());
        var scheme = document.at("/components/securitySchemes/bearerAuth");
        assertEquals("http", scheme.path("type").asText());
        assertEquals("bearer", scheme.path("scheme").asText());
        assertEquals("JWT", scheme.path("bearerFormat").asText());
        var matrix = Map.of(
                "createCustomer", Set.of("201", "400", "401", "403", "409", "500"),
                "replaceCustomer", Set.of("200", "400", "401", "403", "404", "409", "500"),
                "deleteCustomer", Set.of("204", "400", "401", "403", "404", "500"),
                "findCustomer", Set.of("200", "400", "401", "403", "404", "500"),
                "listCustomers", Set.of("200", "400", "401", "403", "500"),
                "searchCustomers", Set.of("200", "400", "401", "403", "500"),
                "findCustomerScore", Set.of("200", "400", "401", "403", "404", "500", "502", "503", "504"));
        var found = new HashSet<String>();
        document.path("paths").fields().forEachRemaining(path -> {
            assertTrue(path.getKey().startsWith("/customers"));
            path.getValue().fields().forEachRemaining(method -> {
                var operation = method.getValue();
                String id = operation.path("operationId").asText();
                assertTrue(found.add(id));
                assertTrue(matrix.containsKey(id));
                assertEquals(matrix.get(id), names(operation.path("responses")), id);
                assertFalse(operation.path("summary").asText().isBlank());
                assertFalse(operation.path("description").asText().isBlank());
                assertTrue(operation.at("/security/0/bearerAuth").isArray());
                var expectedRoles = Set.of("createCustomer", "replaceCustomer", "deleteCustomer").contains(id)
                        ? List.of("ADMIN") : List.of("USER", "ADMIN");
                assertEquals(mapper.valueToTree(expectedRoles), operation.path("x-required-roles"));
                operation.path("responses").fields().forEachRemaining(response -> {
                    assertFalse(response.getValue().path("description").asText().isBlank());
                    if (response.getKey().equals("204")) assertFalse(response.getValue().has("content"));
                    else {
                        String media = Integer.parseInt(response.getKey()) >= 400 ? "application/problem+json" : "application/json";
                        var content = response.getValue().path("content").path(media);
                        assertTrue(content.path("schema").path("$ref").asText().startsWith("#/components/schemas/"));
                        assertFalse(content.path("examples").isEmpty());
                        if (Integer.parseInt(response.getKey()) >= 400) content.path("examples").forEach(example -> {
                            var value = example.path("value");
                            assertEquals(Integer.parseInt(response.getKey()), value.path("status").asInt());
                            assertTrue(value.hasNonNull("code"));
                            assertFalse(value.has("traceId"));
                        });
                    }
                });
            });
        });
        assertEquals(matrix.keySet(), found);
        assertFalse(document.toString().contains("CustomerEntity"));
        assertFalse(document.toString().contains("customer_outbox"));
        verifyNoInteractions(service);
    }

    @Test
    void shouldResolveEveryLocalReferenceAndExposeClosedInputsAndReadonlyVersion() {
        var document = spec();
        checkRefs(document, document);
        for (String name : List.of("CreateCustomerRequest", "UpdateCustomerRequest")) {
            var schema = document.path("components").path("schemas").path(name);
            assertEquals(Set.of("name", "cpf", "email", "status"), names(schema.path("properties")));
            assertEquals(4, schema.path("required").size());
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertEquals(150, schema.at("/properties/name/maxLength").asInt());
            assertEquals(11, schema.at("/properties/cpf/minLength").asInt());
            assertEquals("[0-9]{11}", schema.at("/properties/cpf/pattern").asText());
            assertEquals(254, schema.at("/properties/email/maxLength").asInt());
            assertEquals(Set.of("ACTIVE", "INACTIVE"), values(schema.at("/properties/status/enum")));
        }
        var version = document.at("/components/schemas/CustomerResponse/properties/version");
        assertEquals("integer", version.path("type").asText());
        assertEquals("int64", version.path("format").asText());
        assertTrue(version.path("readOnly").asBoolean());
        assertEquals(0, version.path("minimum").asLong());
        assertEquals(1000, document.at("/components/schemas/CustomerScoreResponse/properties/score/maximum").asInt());
        assertEquals(Set.of("LOW_RISK", "MEDIUM_RISK", "HIGH_RISK"), values(document.at("/components/schemas/CustomerScoreResponse/properties/classification/enum")));
    }

    @Test
    void shouldDocumentInputExamplesAcceptedByRealBeanValidation() throws Exception {
        var document = spec();
        var create = document.path("paths").path("/customers").path("post").path("requestBody");
        var update = document.path("paths").path("/customers/{id}").path("put").path("requestBody");
        assertTrue(create.path("required").asBoolean());
        assertTrue(update.path("required").asBoolean());
        var createDto = mapper.treeToValue(create.path("content").path("application/json").at("/examples/valid/value"), CreateCustomerRequest.class);
        var updateDto = mapper.treeToValue(update.path("content").path("application/json").at("/examples/valid/value"), UpdateCustomerRequest.class);
        assertTrue(validator.validate(createDto).isEmpty());
        assertTrue(validator.validate(updateDto).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings={"/swagger-ui/index.html", "/swagger-ui/swagger-ui.css", "/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-ui-standalone-preset.js"})
    void shouldServeUiResourcesWithoutAuthentication(String path) {
        var response = http.getForEntity(path, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().isBlank());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    @Test
    void shouldConfigureOnlyLocalSpecAndAvoidPersistingAuthorizationOrExternalValidator() {
        var config = http.getForObject("/v3/api-docs/swagger-config", JsonNode.class);
        assertEquals("/v3/api-docs", config.path("url").asText());
        assertFalse(config.path("persistAuthorization").asBoolean());
        assertTrue(config.path("validatorUrl").isMissingNode() || config.path("validatorUrl").asText().isEmpty());
        assertFalse(config.toString().contains("petstore"));
        assertFalse(config.toString().contains("validator.swagger.io"));
    }

    @Test
    void shouldKeepBusinessProtectedAndDocumentationReadonly() {
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/customers", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, http.postForEntity("/v3/api-docs", "{}", String.class).getStatusCode());
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token("USER"));
        assertEquals(HttpStatus.FORBIDDEN, http.exchange("/customers", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/v3/api-docs/arbitrary", String.class).getStatusCode());
        verifyNoInteractions(service);
    }

    private void checkRefs(JsonNode node, JsonNode document) {
        if (node.isObject() && node.has("$ref")) {
            String ref = node.path("$ref").asText();
            assertTrue(ref.startsWith("#/"));
            assertFalse(document.at(ref.substring(1)).isMissingNode(), ref);
        }
        node.forEach(child -> checkRefs(child, document));
    }
    private Set<String> names(JsonNode node) { var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names; }
    private Set<String> values(JsonNode node) { var values = new HashSet<String>(); node.forEach(value -> values.add(value.asText())); return values; }
}
