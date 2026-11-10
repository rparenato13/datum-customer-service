package br.com.datum.customer;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.security.SecurityTestFixture;
import br.com.datum.customer.security.TestJwtSupport;
import org.junit.jupiter.api.BeforeEach;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
@ExtendWith(OutputCaptureExtension.class)
@SecurityTestFixture
class CustomerServiceApplicationTest {

    @MockitoBean
    private CustomerService customerService;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void authenticate() {
        TestJwtSupport.authenticateAsAdmin(restTemplate);
    }

    @Test
    void shouldKeepMetricEndpointsUnexposedWithoutObservabilityProfile() {
        assertEquals(HttpStatus.NOT_FOUND, restTemplate.getForEntity("/actuator/metrics", JsonNode.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, restTemplate.getForEntity("/actuator/prometheus", JsonNode.class).getStatusCode());
    }

    @Test
    void shouldStartAndReportHealthy() {
        var response = restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().path("status").asText());
    }

    @Test
    void shouldHideUnexpectedFailureDetails(CapturedOutput output) {
        when(customerService.findById(1L)).thenThrow(new IllegalStateException("52998224725 dado sensível"));

        var response = restTemplate.getForEntity("/customers/1", JsonNode.class);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INTERNAL_ERROR", response.getBody().path("code").asText());
        assertFalse(response.getBody().toString().contains("52998224725"));
        assertFalse(response.getBody().has("trace"));
        assertFalse(output.getAll().contains("52998224725"));
    }
}
