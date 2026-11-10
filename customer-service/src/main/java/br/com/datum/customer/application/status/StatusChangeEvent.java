package br.com.datum.customer.application.status;

import br.com.datum.customer.domain.CustomerStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public record StatusChangeEvent(UUID eventId, Instant occurredAt, long customerId,
                                CustomerStatus status, long expectedVersion) {
    public static final String TYPE = "CUSTOMER_STATUS_CHANGE";
    public static final String CONSUMER = "customer-service.status-change.v1";

    public String fingerprint() {
        // Todos os escalares validados sao livres de quebra de linha. Numeros
        // inteiros e Instant normalizados evitam perda de precisao ou dependencia
        // da ordem/whitespace do JSON recebido.
        var canonical = String.join("\n", CONSUMER, eventId.toString(), TYPE, "1", occurredAt.toString(),
                Long.toString(customerId), status.name(), Long.toString(expectedVersion));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponivel.");
        }
    }
}
