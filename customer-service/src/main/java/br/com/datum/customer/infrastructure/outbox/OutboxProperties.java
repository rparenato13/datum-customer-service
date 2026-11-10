package br.com.datum.customer.infrastructure.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("customer.outbox")
public record OutboxProperties(boolean publisherEnabled, @Min(100) @Max(10000) int pollDelayMs,
                               @Min(1) @Max(100) int batchSize,
                               @Min(100) @Max(5000) int confirmTimeoutMs,
                               @Min(20000) @Max(120000) int leaseMs) {
    @AssertTrue(message = "Lease deve exceder confirm timeout em pelo menos 15 segundos.")
    public boolean isLeaseLongEnough() {
        return leaseMs >= confirmTimeoutMs + 15000;
    }
}
