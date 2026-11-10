package br.com.datum.notification.messaging;

import br.com.datum.notification.application.CustomerCreatedEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CustomerCreatedCodec {
    private static final Set<String> FIELDS = Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "customerId");
    private final ObjectReader reader;

    public CustomerCreatedCodec(ObjectMapper mapper) {
        reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    public CustomerCreatedEvent decode(byte[] bytes) {
        try {
            if (bytes.length == 0 || bytes.length > 16384) {
                throw new InvalidCreatedEventException();
            }
            var json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root = reader.readTree(json);
            if (root == null || !root.isObject()) {
                throw new InvalidCreatedEventException();
            }
            var fields = new HashSet<String>();
            root.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(FIELDS) || !CustomerCreatedEvent.TYPE.equals(text(root, "eventType"))
                    || number(root.path("schemaVersion"), 1, 1) != 1) {
                throw new InvalidCreatedEventException();
            }
            var id = text(root, "eventId");
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
                throw new InvalidCreatedEventException();
            }
            var timestamp = text(root, "occurredAt");
            if (!timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")) {
                throw new InvalidCreatedEventException();
            }
            var occurredAt = OffsetDateTime.parse(timestamp);
            if (occurredAt.getYear() < 1) {
                throw new InvalidCreatedEventException();
            }
            return new CustomerCreatedEvent(UUID.fromString(id), occurredAt.toInstant(),
                    number(root.path("customerId"), 1, Long.MAX_VALUE));
        } catch (Exception ex) {
            // Nao reter causas de parsing que podem conter o corpo recebido.
            throw new InvalidCreatedEventException();
        }
    }

    private String text(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isTextual()) {
            throw new InvalidCreatedEventException();
        }
        return value.textValue();
    }

    private long number(JsonNode value, long min, long max) {
        if (!value.isNumber()) {
            throw new InvalidCreatedEventException();
        }
        BigInteger integer = value.decimalValue().toBigIntegerExact();
        if (integer.compareTo(BigInteger.valueOf(min)) < 0 || integer.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new InvalidCreatedEventException();
        }
        return integer.longValueExact();
    }
}
