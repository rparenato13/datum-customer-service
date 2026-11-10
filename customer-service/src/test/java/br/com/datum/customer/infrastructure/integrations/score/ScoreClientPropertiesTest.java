package br.com.datum.customer.infrastructure.integrations.score;

import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScoreClientPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:18082", "http://wiremock:8080/", "https://score.example.test"})
    void shouldAcceptHttpOrigins(String baseUrl) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(new ScoreClientProperties(baseUrl, 500, 1000, 2, 100)).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a URL", "file:///tmp/score", "ftp://score.test", "http://user:secret@score.test",
            "http://score.test/path", "http://score.test?cpf=secret", "http://score.test#fragment", "http://score.test:99999"})
    void shouldRejectUnsafeOrAmbiguousBaseUrl(String baseUrl) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(new ScoreClientProperties(baseUrl, 500, 1000, 2, 100)).isEmpty());
        }
    }

    @Test
    void shouldRejectInfiniteTimeoutsOrUnboundedRetries() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var properties : new ScoreClientProperties[]{
                    new ScoreClientProperties("http://localhost", 0, 1000, 2, 100),
                    new ScoreClientProperties("http://localhost", 5001, 1000, 2, 100),
                    new ScoreClientProperties("http://localhost", 500, 0, 2, 100),
                    new ScoreClientProperties("http://localhost", 500, 10001, 2, 100),
                    new ScoreClientProperties("http://localhost", 500, 1000, 0, 100),
                    new ScoreClientProperties("http://localhost", 500, 1000, 4, 100),
                    new ScoreClientProperties("http://localhost", 500, 1000, 2, -1),
                    new ScoreClientProperties("http://localhost", 500, 1000, 2, 501)}) {
                assertFalse(validator.validate(properties).isEmpty());
            }
        }
    }
}
