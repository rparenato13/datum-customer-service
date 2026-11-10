package br.com.datum.customer.infrastructure.integrations.score;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties("customer.score")
public record ScoreClientProperties(@NotBlank String baseUrl,
                                    @Min(1) @Max(5000) int connectTimeoutMs,
                                    @Min(1) @Max(10000) int readTimeoutMs,
                                    @Min(1) @Max(3) int maxAttempts,
                                    @Min(0) @Max(500) int retryDelayMs) {

    @AssertTrue(message = "Base URL deve ser uma origem HTTP/HTTPS, sem credenciais, caminho, query ou fragmento.")
    public boolean isBaseUrlValid() {
        try {
            var uri = URI.create(baseUrl);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }
}
