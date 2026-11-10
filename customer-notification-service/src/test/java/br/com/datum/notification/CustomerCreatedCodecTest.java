package br.com.datum.notification;

import br.com.datum.notification.messaging.CustomerCreatedCodec;
import br.com.datum.notification.messaging.InvalidCreatedEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CustomerCreatedCodecTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String VALID = new String(CreatedFixtures.body(CreatedFixtures.event(123)), StandardCharsets.UTF_8);
    final CustomerCreatedCodec codec = new CustomerCreatedCodec(MAPPER);

    @Test
    void shouldNormalizeEquivalentJsonWithoutLosingBigintPrecision() {
        String first = VALID.replace("\"customerId\":123", "\"customerId\":9223372036854775807");
        var event = codec.decode(first.getBytes(StandardCharsets.UTF_8));
        assertEquals(Long.MAX_VALUE, event.customerId());
        String second = first.replace(".123Z", ".123000Z").replace("9223372036854775807", "9223372036854775807.0");
        assertEquals(event.fingerprint(), codec.decode(second.getBytes(StandardCharsets.UTF_8)).fingerprint());
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void shouldRejectClosedContractAndMalformedTransport(String value) {
        var error = assertThrows(InvalidCreatedEventException.class, () -> codec.decode(value.getBytes(StandardCharsets.UTF_8)));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("SENSITIVE_SENTINEL"));
    }

    static Stream<String> invalid() {
        return Stream.of("", "{", "null", "[]", "{}", VALID + "{}", " ".repeat(16385),
                VALID.replace("CUSTOMER_CREATED", "CUSTOMER_STATUS_CHANGE"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"),
                VALID.replace("\"customerId\":123", "\"customerId\":0"),
                VALID.replace("\"customerId\":123", "\"customerId\":-1"),
                VALID.replace("\"customerId\":123", "\"customerId\":9223372036854775808"),
                VALID.replace("\"customerId\":123", "\"customerId\":\"123\""),
                VALID.replace("\"customerId\":123", "\"customerId\":123.000000000000000001"),
                VALID.replace("2026-11-10", "2026-02-30"), VALID.replace(".123Z", ".1234567Z"),
                VALID.replace(".123Z", ".123+00:00"), VALID.replace("2026-11-10", "0000-11-10"),
                VALID.replace("\"eventId\":\"", "\"eventId\":\"INVALID"),
                VALID.replaceFirst("\\{", "{\"cpf\":\"SENSITIVE_SENTINEL\","),
                VALID.replaceFirst("\\{", "{\"customerId\":123,"));
    }

    @Test
    void shouldRejectEachMissingFieldAndInvalidUtf8() throws Exception {
        ObjectNode original = (ObjectNode) MAPPER.readTree(VALID);
        var fields = new java.util.ArrayList<String>();
        original.fieldNames().forEachRemaining(fields::add);
        for (var field : fields) {
            var missing = original.deepCopy();
            missing.remove(field);
            assertThrows(InvalidCreatedEventException.class, () -> codec.decode(MAPPER.writeValueAsBytes(missing)), field);
        }
        assertThrows(InvalidCreatedEventException.class, () -> codec.decode(VALID.getBytes(StandardCharsets.UTF_16)));
        assertThrows(InvalidCreatedEventException.class, () -> codec.decode(new byte[]{(byte) 0xc3, (byte) 0x28}));
    }
}
