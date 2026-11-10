package br.com.datum.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public record CustomerCreatedEvent(UUID eventId, Instant occurredAt, long customerId) {
    public static final String TYPE = "CUSTOMER_CREATED";
    public static final String CONSUMER = "customer-notification-service.created.v1";

    public String fingerprint() {
        // Todos os escalares validados sao livres de quebra de linha. Numeros
        // inteiros e Instant normalizados evitam perda de precisao ou dependencia
        // da ordem/whitespace do JSON recebido.
        var canonical = String.join("\n", CONSUMER, eventId.toString(), TYPE, "1", occurredAt.toString(),
                Long.toString(customerId));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponivel.");
        }
    }
}
