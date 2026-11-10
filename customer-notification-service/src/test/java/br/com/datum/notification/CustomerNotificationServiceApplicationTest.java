package br.com.datum.notification;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
class CustomerNotificationServiceApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldKeepMetricEndpointsUnexposedWithoutObservabilityProfile() {
        var monitor = restTemplate.withBasicAuth("notification_monitor", "notification_monitor_local_only");
        assertEquals(HttpStatus.NOT_FOUND, monitor.getForEntity("/actuator/metrics", JsonNode.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, monitor.getForEntity("/actuator/prometheus", JsonNode.class).getStatusCode());
    }

    @Test
    void shouldStartAndReportHealthy() {
        var response = restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().path("status").asText());
    }
}
