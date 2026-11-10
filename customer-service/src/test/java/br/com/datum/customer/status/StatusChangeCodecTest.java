package br.com.datum.customer.status;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.messaging.status.InvalidStatusEventException;
import br.com.datum.customer.infrastructure.messaging.status.StatusChangeCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StatusChangeCodecTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String VALID = new String(StatusFixtures.body(StatusFixtures.event(123, 0, CustomerStatus.INACTIVE)), StandardCharsets.UTF_8);
    final StatusChangeCodec codec = new StatusChangeCodec(MAPPER);

    @Test
    void shouldDecodeAndNormalizeEquivalentJsonWithoutLosingBigintPrecision() {
        var event = StatusFixtures.event(Long.MAX_VALUE, Long.MAX_VALUE - 1, CustomerStatus.ACTIVE);
        var raw = StatusFixtures.body(event);
        assertEquals(event, codec.decode(raw));
        var normalized = new String(raw, StandardCharsets.UTF_8).replace("00.123Z", "00.123000Z");
        assertEquals(event.fingerprint(), codec.decode(normalized.getBytes(StandardCharsets.UTF_8)).fingerprint());
        var decimal = VALID.replace("\"expectedVersion\":0", "\"expectedVersion\":0.0");
        assertEquals(codec.decode(VALID.getBytes(StandardCharsets.UTF_8)).fingerprint(), codec.decode(decimal.getBytes(StandardCharsets.UTF_8)).fingerprint());
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void shouldRejectMalformedOrIncompatiblePayloadWithoutRetainingInput(String value) {
        var error = assertThrows(InvalidStatusEventException.class, () -> codec.decode(value.getBytes(StandardCharsets.UTF_8)));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("SENSITIVE_STATUS_ONLY"));
    }

    static Stream<String> invalid() {
        return Stream.of("", "{", "[]", "null", "{}", VALID + VALID, VALID + " ".repeat(16384),
                VALID.replace("INACTIVE", "BLOCKED"), VALID.replace("INACTIVE", "inactive"),
                VALID.replace("CUSTOMER_STATUS_CHANGE", "CUSTOMER_CREATED"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"),
                VALID.replace("\"customerId\":123", "\"customerId\":0"),
                VALID.replace("\"customerId\":123", "\"customerId\":9223372036854775808"),
                VALID.replace("\"customerId\":123", "\"customerId\":\"123\""),
                VALID.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
                VALID.replace("\"expectedVersion\":0", "\"expectedVersion\":9223372036854775807"),
                VALID.replace("\"expectedVersion\":0", "\"expectedVersion\":0.00000000000000000001"),
                VALID.replace("\"expectedVersion\":0", "\"expectedVersion\":false"),
                VALID.replace("2026-11-10", "2026-02-30"), VALID.replace("00.123Z", "00.1234567Z"),
                VALID.replace("00.123Z", "00.123-03:00"), VALID.replace("\"eventId\":\"", "\"eventId\":\"INVALID-"),
                VALID.substring(0, VALID.length() - 1) + ",\"cpf\":\"SENSITIVE_STATUS_ONLY\"}",
                VALID.substring(0, VALID.length() - 1) + ",\"expectedVersion\":1}");
    }

    @Test
    void shouldRequireEveryField() throws Exception {
        var original = MAPPER.readTree(VALID);
        for (var fields = original.fieldNames(); fields.hasNext();) {
            var clone = original.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) clone).remove(fields.next());
            assertThrows(InvalidStatusEventException.class, () -> codec.decode(MAPPER.writeValueAsBytes(clone)));
        }
        assertThrows(InvalidStatusEventException.class, () -> codec.decode(VALID.getBytes(StandardCharsets.UTF_16)));
    }
}
