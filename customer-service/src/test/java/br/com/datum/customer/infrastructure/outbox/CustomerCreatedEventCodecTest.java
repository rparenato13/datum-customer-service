package br.com.datum.customer.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomerCreatedEventCodecTest {
    private final CustomerCreatedEventCodec codec = new CustomerCreatedEventCodec(new ObjectMapper());
    private final UUID id = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-11-10T03:00:00.123456Z");

    private OutboxEvent event(String payload) {
        return new OutboxEvent(id, 123L, "CUSTOMER_CREATED", 1, occurredAt, payload, 1, UUID.randomUUID());
    }

    @Test
    void shouldProduceExactMinimalContractWithFixedIdentityAndTime() throws Exception {
        var encoded = codec.encode(id, 123L, occurredAt);
        assertTrue(codec.isValid(event(encoded)));
        var body = new ObjectMapper().readTree(encoded);
        assertEquals(5, body.size());
        assertFalse(body.has("cpf"));
        assertFalse(body.has("email"));
        assertEquals(id.toString(), body.path("eventId").asText());
        assertEquals(occurredAt.toString(), body.path("occurredAt").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "{\"cpf\":\"private\"}", "{\"eventId\":1,\"eventId\":2}"})
    void shouldRejectInvalidPayloads(String payload) {
        assertFalse(codec.isValid(event(payload)));
    }

    @Test
    void shouldRejectMetadataMismatchAndAdditionalProperties() {
        var body = codec.encode(id, 123L, occurredAt);
        assertFalse(codec.isValid(event(body.replace("123,", "124," ).replace(":123}", ":124}"))));
        assertFalse(codec.isValid(event(body.replace("CUSTOMER_CREATED", "CUSTOMER_STATUS_CHANGE"))));
        assertFalse(codec.isValid(event(body.replace(id.toString(), UUID.randomUUID().toString()))));
        assertFalse(codec.isValid(event(body.substring(0, body.length() - 1) + ",\"cpf\":\"private\"}")));
        assertFalse(codec.isValid(event(body + body)));
        assertFalse(codec.isValid(event(body + " ".repeat(16384))));
        assertFalse(codec.isValid(event(body.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"))));
    }
}
