package br.com.datum.customer.infrastructure.integrations.score;

import br.com.datum.customer.application.score.ScoreClient;
import br.com.datum.customer.application.score.ScoreClientException;
import br.com.datum.customer.domain.Score;
import br.com.datum.customer.domain.ScoreClassification;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import br.com.datum.customer.observability.CorrelationContext;
import br.com.datum.customer.observability.Telemetry;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;

import static br.com.datum.customer.application.score.ScoreClientException.Reason.*;

public class HttpScoreClient implements ScoreClient {

    private static final int MAX_BODY_BYTES = 4096;
    private static final Set<Integer> RETRY_STATUSES = Set.of(500, 502, 503, 504);
    private final Telemetry telemetry;
    private final RestClient rest;
    private final ObjectReader json;
    private final ScoreClientProperties properties;

    public HttpScoreClient(RestClient rest, ObjectMapper mapper, ScoreClientProperties properties) {
        this(rest, mapper, properties, Telemetry.standalone());
    }

    public HttpScoreClient(RestClient rest, ObjectMapper mapper, ScoreClientProperties properties, Telemetry telemetry) {
        this.telemetry = telemetry;
        this.rest = rest;
        this.json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.properties = properties;
    }

    @Override
    public Score findByCpf(String cpf) {
        long start = System.nanoTime();
        String outcome = "success";
        try (var scope = CorrelationContext.open(CorrelationContext.id())) {
            try {
                return lookup(cpf);
            } catch (ScoreClientException ex) {
                outcome = ex.reason().name().toLowerCase(java.util.Locale.ROOT);
                throw ex;
            } finally {
                telemetry.duration("datum.score.request.duration", System.nanoTime() - start, "outcome", outcome);
            }
        }
    }

    private Score lookup(String cpf) {
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure(UNAVAILABLE, false);
            }
            try {
                return fetch(cpf, attempt);
            } catch (ScoreClientException ex) {
                if (!ex.retryable() || attempt >= properties.maxAttempts()) {
                    throw ex;
                }
                try {
                    Thread.sleep(properties.retryDelayMs());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure(UNAVAILABLE, false);
                }
            }
        }
    }

    private Score fetch(String cpf, int attempt) {
        long start = System.nanoTime();
        int[] httpStatus = {0};
        String outcome = "success";
        String exceptionClass = "none";
        try {
            return rest.get().uri("/scores/{cpf}", cpf).header(CorrelationContext.HTTP_HEADER, CorrelationContext.id()).accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        httpStatus[0] = status;
                        if (status == 404) {
                            throw failure(NOT_FOUND, false);
                        }
                        if (status == 429 || status >= 500) {
                            throw failure(UNAVAILABLE, RETRY_STATUSES.contains(status));
                        }
                        if (status != 200) {
                            throw failure(INVALID_RESPONSE, false);
                        }
                        var contentType = response.getHeaders().getContentType();
                        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                            throw failure(INVALID_RESPONSE, false);
                        }
                        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                        return parse(body, cpf);
                    });
        } catch (ScoreClientException ex) {
            outcome = ex.reason().name().toLowerCase(java.util.Locale.ROOT);
            exceptionClass = ex.getClass().getSimpleName();
            throw ex;
        } catch (ResourceAccessException ex) {
            exceptionClass = ex.getClass().getSimpleName();
            // O timeout do JDK/Spring abrange tambem a leitura do corpo. Se ele
            // fechar o stream apos os headers, a causa pode ser apenas IOException.
            boolean timedOut = (System.nanoTime() - start) / 1_000_000 >= properties.readTimeoutMs();
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                timedOut |= cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException;
            }
            outcome = timedOut ? "timeout" : "unavailable";
            if (Thread.currentThread().isInterrupted()) {
                outcome = "unavailable";
                throw failure(UNAVAILABLE, false);
            }
            throw failure(timedOut ? TIMEOUT : UNAVAILABLE, !timedOut);
        } catch (org.springframework.http.InvalidMediaTypeException ex) {
            outcome = "invalid_response";
            exceptionClass = ex.getClass().getSimpleName();
            throw failure(INVALID_RESPONSE, false);
        } finally {
            long elapsed = System.nanoTime() - start;
            telemetry.duration("datum.score.client.duration", elapsed, "outcome", outcome,
                    "attempt", Integer.toString(attempt), "status", httpStatus[0] == 0 ? "none" : httpStatus[0] / 100 + "xx");
            telemetry.event(LoggerFactory.getLogger(HttpScoreClient.class), "success".equals(outcome) ? Level.INFO : Level.WARN,
                            "score.request", outcome, elapsed)
                    .addKeyValue("dependency", "score-provider").addKeyValue("endpointPattern", "/scores/{cpf}")
                    .addKeyValue("attempt", attempt).addKeyValue("httpStatus", httpStatus[0])
                    .addKeyValue("timeout", "timeout".equals(outcome)).addKeyValue("exceptionClass", exceptionClass)
                    .log("Consulta ao provedor de Score concluida.");
        }
    }

    private Score parse(byte[] body, String cpf) {
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw failure(INVALID_RESPONSE, false);
        }
        try {
            var root = json.readTree(body);
            if (root == null || !root.isObject() || !root.path("cpf").isTextual()
                    || !cpf.equals(root.path("cpf").textValue()) || !root.path("score").isIntegralNumber()
                    || !root.path("score").canConvertToInt() || root.path("score").intValue() < 0
                    || root.path("score").intValue() > 1000 || !root.path("classification").isTextual()) {
                throw failure(INVALID_RESPONSE, false);
            }
            return new Score(root.path("score").intValue(),
                    ScoreClassification.valueOf(root.path("classification").textValue()));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw failure(INVALID_RESPONSE, false);
        } catch (IOException ex) {
            // O parser recebe somente bytes ja limitados em memoria.
            throw failure(INVALID_RESPONSE, false);
        }
    }

    private ScoreClientException failure(ScoreClientException.Reason reason, boolean retryable) {
        return new ScoreClientException(reason, retryable);
    }
}
