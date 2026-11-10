package br.com.datum.notification.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationContextTest {
    @Test void shouldRestoreNestedContextAndCountersEvenAfterException() {
        assertNull(CorrelationContext.id());
        String outer = UUID.randomUUID().toString();
        try (var scope = CorrelationContext.open(outer)) {
            CorrelationContext.database(10);
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = CorrelationContext.open(null)) {
                    assertNotEquals(outer, CorrelationContext.id());
                    CorrelationContext.database(90);
                    throw new IllegalStateException();
                }
            });
            assertEquals(outer, MDC.get("correlationId"));
            assertEquals(10, CorrelationContext.databaseMs());
            assertEquals(1, CorrelationContext.queries());
        }
        assertNull(CorrelationContext.id());
        assertNull(MDC.get("correlationId"));
    }

    @Test void shouldReplaceUntrustedHeaderAndNeverUseQueryOrIdentifiersAsMetricLabels() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new HttpObservationFilter(new Telemetry(registry, 250));
        var request = new MockHttpServletRequest("GET", "/customers/123/score");
        request.setQueryString("cpf=16899535009&email=private@example.test");
        request.addHeader("X-Correlation-Id", "private@example.test");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertTrue(CorrelationContext.valid(CorrelationContext.id())));
        assertTrue(CorrelationContext.valid(response.getHeader("X-Correlation-Id")));
        assertEquals(1, registry.get("datum.http.request.duration").tag("route", "/customers/{id}/score").timer().count());
        assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(t -> t.getValue().contains("123") || t.getValue().contains("16899535009") || t.getValue().contains("@")));
        assertNull(CorrelationContext.id());
    }

    @Test void shouldAcceptOnlyOneCanonicalUuidAndClearThreadOnFailure() {
        var registry = new SimpleMeterRegistry();
        var filter = new HttpObservationFilter(new Telemetry(registry, 250));
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        String incoming = UUID.randomUUID().toString();
        request.addHeader("X-Correlation-Id", incoming);
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, (req, res) -> {
            assertEquals(incoming, CorrelationContext.id());
            throw new IllegalStateException("private cause");
        }));
        assertEquals(incoming, response.getHeader("X-Correlation-Id"));
        assertNull(CorrelationContext.id());
        assertNull(MDC.get("correlationId"));
        assertEquals(1, registry.get("datum.http.request.duration").tag("status", "500").timer().count());
    }

    @Test void shouldRejectDuplicateCorrelationHeaders() throws Exception {
        var filter = new HttpObservationFilter(Telemetry.standalone());
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        String incoming = UUID.randomUUID().toString();
        request.addHeader("X-Correlation-Id", incoming);
        request.addHeader("X-Correlation-Id", incoming);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertNotEquals(incoming, response.getHeader("X-Correlation-Id"));
    }
}
