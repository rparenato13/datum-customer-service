package br.com.datum.customer.observability;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SecurityTestFixture
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles({"smoke", "observability"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class MetricsSecurityTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @MockitoBean CustomerService service;

    @Test void shouldRequireAdminAndExposeNoConfiguration() {
        for (String path : new String[]{"/actuator/metrics", "/actuator/prometheus", "/actuator/metrics/jvm.memory.used"}) {
            assertEquals(401, get(path, null).getStatusCode().value());
            assertEquals(403, get(path, "USER").getStatusCode().value());
            var response = get(path, "ADMIN");
            assertEquals(200, response.getStatusCode().value());
            assertNull(response.getHeaders().getFirst("Set-Cookie"));
        }
        assertEquals(403, get("/actuator/env", "ADMIN").getStatusCode().value());
        assertEquals(200, get("/actuator/health", null).getStatusCode().value());
    }

    @Test void shouldCorrelateSanitizedProblemAndJsonCompletion(CapturedOutput output) throws Exception {
        String id = UUID.randomUUID().toString();
        var headers = new HttpHeaders();
        headers.set("X-Correlation-Id", id);
        headers.setBearerAuth("PRIVATE_TOKEN_SENTINEL");
        var response = http.exchange("/customers/123?email=private@example.test", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(401, response.getStatusCode().value());
        assertEquals(id, response.getHeaders().getFirst("X-Correlation-Id"));
        assertEquals(id, mapper.readTree(response.getBody()).path("correlationId").asText());
        var completion = output.getAll().lines().filter(line -> line.startsWith("{") && line.contains(id))
                .map(line -> { try { return mapper.readTree(line); } catch (Exception error) { throw new AssertionError(error); } })
                .filter(node -> node.path("operation").asText().equals("http.request")).findFirst().orElseThrow();
        assertEquals("/customers/{id}", completion.path("route").asText());
        assertTrue(completion.path("timestamp").asText().endsWith("Z"));
        assertTrue(completion.has("durationMs"));
        assertFalse(output.getAll().contains("PRIVATE_TOKEN_SENTINEL"));
        assertFalse(output.getAll().contains("private@example.test"));
    }

    private ResponseEntity<String> get(String path, String role) {
        var headers = new HttpHeaders();
        if (role != null) headers.setBearerAuth(TestJwtSupport.token(role));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
