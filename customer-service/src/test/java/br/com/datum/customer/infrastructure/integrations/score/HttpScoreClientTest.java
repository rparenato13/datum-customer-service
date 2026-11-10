package br.com.datum.customer.infrastructure.integrations.score;

import br.com.datum.customer.application.score.ScoreClient;
import br.com.datum.customer.application.score.ScoreClientException;
import br.com.datum.customer.domain.ScoreClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Notifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static br.com.datum.customer.application.score.ScoreClientException.Reason.*;

@ExtendWith(OutputCaptureExtension.class)
class HttpScoreClientTest {

    static final String CPF = "16899535009";
    static final String PATH = "/scores/" + CPF;
    static final String VALID = "{\"cpf\":\"" + CPF + "\",\"score\":750,\"classification\":\"LOW_RISK\"}";
    static WireMockServer provider;
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    HttpClient transport;
    ScoreClient client;

    // Os testes precisam do journal para contar chamadas. Nao registrar URLs/headers.
    public static final Notifier QUIET = new Notifier() {
        public void info(String message) { }
        public void error(String message) { }
        public void error(String message, Throwable cause) { }
    };

    @BeforeAll
    static void startProvider() {
        provider = new WireMockServer(wireMockConfig().dynamicPort().notifier(QUIET));
        provider.start();
    }

    @AfterAll
    static void stopProvider() {
        provider.stop();
    }

    @BeforeEach
    void prepare() {
        provider.resetAll();
        configure(provider.baseUrl(), 250, 2, 10);
    }

    void configure(String url, int timeout, int attempts, int delay) {
        if (transport != null) {
            transport.close();
        }
        var properties = new ScoreClientProperties(url, 200, timeout, attempts, delay);
        var configuration = new ScoreClientConfiguration();
        transport = configuration.scoreHttpClient(properties);
        client = configuration.scoreClient(properties, new ObjectMapper(), transport, new br.com.datum.customer.observability.Telemetry(registry, 250));
    }

    @AfterEach
    void release() {
        transport.close();
    }

    @Test
    void shouldPreserveCorrelationAcrossRetriesWithoutSensitiveMetricLabels() {
        String correlation = java.util.UUID.randomUUID().toString();
        provider.stubFor(get(PATH).willReturn(aResponse().withStatus(503)));
        try (var scope = br.com.datum.customer.observability.CorrelationContext.open(correlation)) {
            assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF));
            assertEquals(correlation, br.com.datum.customer.observability.CorrelationContext.id());
        }
        provider.verify(2, getRequestedFor(urlEqualTo(PATH)).withHeader("X-Correlation-Id", equalTo(correlation))
                .withoutHeader("Authorization").withoutHeader("Cookie"));
        assertEquals(2, registry.find("datum.score.client.duration").timers().stream().mapToLong(timer -> timer.count()).sum());
        assertEquals(1, registry.get("datum.score.request.duration").tag("outcome", "unavailable").timer().count());
        assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains(CPF) || tag.getValue().contains(correlation)));
        assertNull(br.com.datum.customer.observability.CorrelationContext.id());
    }

    @ParameterizedTest
    @MethodSource("validScores")
    void shouldAcceptValidContractAndNeverForwardCredentials(int score, String classification) {
        provider.stubFor(get(PATH).willReturn(okJson("{\"cpf\":\"" + CPF + "\",\"score\":" + score
                + ",\"classification\":\"" + classification + "\",\"providerMetadata\":\"ignored\"}")));
        var result = client.findByCpf(CPF);
        assertEquals(score, result.score());
        assertEquals(ScoreClassification.valueOf(classification), result.classification());
        provider.verify(1, getRequestedFor(urlEqualTo(PATH)).withoutHeader("Authorization").withoutHeader("Cookie"));
    }

    static Stream<Arguments> validScores() {
        return Stream.of(Arguments.of(0, "HIGH_RISK"), Arguments.of(1000, "LOW_RISK"),
                Arguments.of(450, "MEDIUM_RISK"));
    }

    @ParameterizedTest
    @MethodSource("httpFailures")
    void shouldMapFailuresAndRetryOnlySelectedStatuses(int status, ScoreClientException.Reason reason, int calls,
            CapturedOutput output) {
        provider.stubFor(get(PATH).willReturn(aResponse().withStatus(status).withBody(CPF + " segredo externo")));
        var failure = assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF));
        assertEquals(reason, failure.reason());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(CPF));
        assertFalse(output.getAll().contains(CPF));
        provider.verify(calls, getRequestedFor(urlEqualTo(PATH)));
    }

    static Stream<Arguments> httpFailures() {
        return Stream.of(Arguments.of(404, NOT_FOUND, 1), Arguments.of(400, INVALID_RESPONSE, 1),
                Arguments.of(401, INVALID_RESPONSE, 1), Arguments.of(403, INVALID_RESPONSE, 1),
                Arguments.of(429, UNAVAILABLE, 1), Arguments.of(500, UNAVAILABLE, 2),
                Arguments.of(502, UNAVAILABLE, 2), Arguments.of(503, UNAVAILABLE, 2),
                Arguments.of(504, UNAVAILABLE, 2), Arguments.of(501, UNAVAILABLE, 1),
                Arguments.of(204, INVALID_RESPONSE, 1), Arguments.of(302, INVALID_RESPONSE, 1));
    }

    @Test
    void shouldRecoverOnSecondAttemptAfterConfiguredDelay() {
        configure(provider.baseUrl(), 1000, 2, 100);
        provider.stubFor(get(PATH).inScenario("recovery").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("healthy"));
        provider.stubFor(get(PATH).inScenario("recovery").whenScenarioStateIs("healthy").willReturn(okJson(VALID)));
        long start = System.nanoTime();
        assertEquals(750, client.findByCpf(CPF).score());
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 90);
        provider.verify(2, getRequestedFor(urlEqualTo(PATH)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void shouldRespectConfiguredMaximumAttempts(int attempts) {
        configure(provider.baseUrl(), 1000, attempts, 0);
        provider.stubFor(get(PATH).willReturn(aResponse().withStatus(503)));
        assertEquals(UNAVAILABLE, assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF)).reason());
        provider.verify(attempts, getRequestedFor(urlEqualTo(PATH)));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void shouldRejectInvalidContractsWithoutRetry(String body) {
        provider.stubFor(get(PATH).willReturn(okJson(body)));
        assertEquals(INVALID_RESPONSE, assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF)).reason());
        provider.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "{", "null", "[]", "{}", VALID + VALID,
                VALID.replace(CPF, "39053344705"), VALID.replace("\"" + CPF + "\"", "null"),
                VALID.replace("750", "null"), VALID.replace("750", "\"750\""), VALID.replace("750", "750.0"),
                VALID.replace("750", "-1"), VALID.replace("750", "1001"), VALID.replace("750", "999999999999999999"),
                VALID.replace("\"score\":750,", ""), VALID.replace("\"cpf\":\"" + CPF + "\",", ""),
                VALID.replace("LOW_RISK", "UNKNOWN"), VALID.replace("LOW_RISK", "low_risk"),
                VALID.replace("\"LOW_RISK\"", "null"), VALID.replace("\"LOW_RISK\"", "12"),
                VALID.replace("\"score\":750", "\"score\":750,\"score\":10"),
                "{\"cpf\":\"" + CPF + "\",\"score\":750}", " ".repeat(4097));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html", "invalid"})
    void shouldRejectUnexpectedContentType(String type) {
        provider.stubFor(get(PATH).willReturn(aResponse().withStatus(200).withHeader("Content-Type", type).withBody(VALID)));
        assertEquals(INVALID_RESPONSE, assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF)).reason());
        provider.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void shouldNotFollowProviderRedirect() {
        provider.stubFor(get(PATH).willReturn(temporaryRedirect("/stolen")));
        provider.stubFor(get("/stolen").willReturn(okJson(VALID)));
        assertEquals(INVALID_RESPONSE, assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF)).reason());
        provider.verify(0, getRequestedFor(urlEqualTo("/stolen")));
    }

    @Test
    void shouldTimeOutBeforeHeadersWithoutRetry() {
        provider.stubFor(get(PATH).willReturn(okJson(VALID).withFixedDelay(1200)));
        assertBoundedTimeout();
    }

    @Test
    void shouldBoundBodyReadEvenWhenHeadersAlreadyArrived() {
        provider.stubFor(get(PATH).willReturn(okJson(VALID).withChunkedDribbleDelay(6, 1200)));
        assertBoundedTimeout();
    }

    void assertBoundedTimeout() {
        long start = System.nanoTime();
        assertEquals(TIMEOUT, assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF)).reason());
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsed >= 180 && elapsed < 1100, "Timeout deve interromper antes do atraso completo.");
        provider.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void shouldMapConnectionRefusedToUnavailableWithoutLeakingUrl() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        configure("http://127.0.0.1:" + port, 500, 2, 10);
        var failure = assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF));
        assertEquals(UNAVAILABLE, failure.reason());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("127.0.0.1"));
    }

    @Test
    void shouldPreserveInterruptionAndAvoidCallingProvider() {
        Thread.currentThread().interrupt();
        try {
            assertEquals(UNAVAILABLE, assertThrows(ScoreClientException.class, () -> client.findByCpf(CPF)).reason());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, provider.getAllServeEvents().size());
        } finally {
            Thread.interrupted();
        }
    }
}
