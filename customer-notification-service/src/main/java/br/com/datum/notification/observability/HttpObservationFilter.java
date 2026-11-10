package br.com.datum.notification.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpObservationFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(HttpObservationFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH");
    private final Telemetry telemetry;

    public HttpObservationFilter(Telemetry telemetry) { this.telemetry = telemetry; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders(CorrelationContext.HTTP_HEADER));
        String incoming = headers.size() == 1 && CorrelationContext.valid(headers.getFirst()) ? headers.getFirst() : null;
        String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
        String route = route(request.getRequestURI());
        long start = System.nanoTime();
        boolean failed = false;
        try (var scope = CorrelationContext.open(incoming)) {
            response.setHeader(CorrelationContext.HTTP_HEADER, CorrelationContext.id());
            telemetry.registry().counter("datum.correlation.incoming", "source", incoming == null ? "generated" : "accepted").increment();
            try {
                chain.doFilter(request, response);
            } catch (ServletException | IOException | RuntimeException error) {
                failed = true;
                throw error;
            } finally {
                int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
                String outcome = status >= 500 ? "server_error" : status >= 400 ? "client_error" : "success";
                long elapsed = System.nanoTime() - start;
                telemetry.duration("datum.http.request.duration", elapsed, "method", method, "route", route, "status", Integer.toString(status));
                telemetry.event(LOG, status >= 500 ? Level.WARN : Level.INFO, "http.request", outcome, elapsed)
                        .addKeyValue("method", method).addKeyValue("route", route).addKeyValue("status", status)
                        .log("Requisicao HTTP concluida.");
            }
        }
    }

    public static String route(String path) {
        if ("/customers".equals(path) || "/customers/search".equals(path) || "/actuator/health".equals(path)) return path;
        if (path.matches("/customers/[^/]+/score")) return "/customers/{id}/score";
        if (path.matches("/customers/[^/]+")) return "/customers/{id}";
        if (path.equals("/actuator/metrics") || path.equals("/actuator/prometheus")) return path;
        if (path.startsWith("/actuator/metrics/")) return "/actuator/metrics/{name}";
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) return "documentation";
        return "UNMATCHED";
    }
}
