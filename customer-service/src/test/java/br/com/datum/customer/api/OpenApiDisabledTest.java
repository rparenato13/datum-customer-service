package br.com.datum.customer.api;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="customer.documentation.enabled=false")
@ActiveProfiles("smoke")
@SecurityTestFixture
class OpenApiDisabledTest {
    @Autowired TestRestTemplate http;
    @MockitoBean CustomerService service;

    @ParameterizedTest
    @ValueSource(strings={"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-ui-bundle.js"})
    void shouldNotExposeDocumentationEvenToAdministratorWhenDisabled(String path) {
        var anonymous = http.getForEntity(path, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode());
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token("ADMIN"));
        var authenticated = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, authenticated.getStatusCode());
        assertEquals("ACCESS_DENIED", authenticated.getBody().path("code").asText());
        assertFalse(authenticated.getBody().has("openapi"));
    }
}
