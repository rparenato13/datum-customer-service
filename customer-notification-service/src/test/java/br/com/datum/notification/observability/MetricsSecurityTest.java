package br.com.datum.notification.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles({"smoke", "observability"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "NOTIFICATION_MANAGEMENT_PASSWORD=monitor_test_only")
class MetricsSecurityTest {
    @Autowired TestRestTemplate http;
    @Test void shouldProtectOnlyReadOnlyMetricsWithDedicatedAccount() {
        for (String path : new String[]{"/actuator/metrics", "/actuator/prometheus", "/actuator/metrics/jvm.memory.used"}) {
            assertEquals(401, http.getForEntity(path, String.class).getStatusCode().value());
            assertEquals(401, http.withBasicAuth("notification_monitor", "wrong").getForEntity(path, String.class).getStatusCode().value());
            var response = http.withBasicAuth("notification_monitor", "monitor_test_only").getForEntity(path, String.class);
            assertEquals(200, response.getStatusCode().value());
            assertNull(response.getHeaders().getFirst("Set-Cookie"));
        }
        assertEquals(403, http.withBasicAuth("notification_monitor", "monitor_test_only")
                .postForEntity("/actuator/metrics", "{}", String.class).getStatusCode().value());
        assertEquals(403, http.withBasicAuth("notification_monitor", "monitor_test_only")
                .getForEntity("/actuator/env", String.class).getStatusCode().value());
        assertEquals(200, http.getForEntity("/actuator/health", String.class).getStatusCode().value());
        assertEquals(404, http.getForEntity("/customers", String.class).getStatusCode().value());
    }
}
