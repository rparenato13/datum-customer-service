package br.com.datum.notification.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("notification.consumer")
public record NotificationConsumerProperties(boolean enabled, @Min(1) @Max(100) int prefetch,
                                        @Min(1000) @Max(30000) int recoveryDelayMs,
                                        @Min(100) @Max(5000) int confirmTimeoutMs) {
}
