package br.com.datum.customer.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("customer.security")
public record JwtSecurityProperties(@NotBlank String issuerUri, @NotBlank String jwkSetUri,
                                    @NotBlank String audience) {
}
