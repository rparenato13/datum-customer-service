package br.com.datum.customer.infrastructure.messaging.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.domain.CustomerStatus;
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

public class StatusChangeCodec {
    private static final Set<String> FIELDS = Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "customerId", "status", "expectedVersion");
    private final ObjectReader reader;

    public StatusChangeCodec(ObjectMapper mapper) {
        reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    public StatusChangeEvent decode(byte[] bytes) {
        try {
            if (bytes.length == 0 || bytes.length > 16384) {
                throw new InvalidStatusEventException();
            }
            var json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root = reader.readTree(json);
            if (root == null || !root.isObject()) {
                throw new InvalidStatusEventException();
            }
            var fields = new HashSet<String>();
            root.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(FIELDS) || !StatusChangeEvent.TYPE.equals(text(root, "eventType"))
                    || number(root.path("schemaVersion"), 1, 1) != 1) {
                throw new InvalidStatusEventException();
            }
            var id = text(root, "eventId");
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
                throw new InvalidStatusEventException();
            }
            var timestamp = text(root, "occurredAt");
            if (!timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")) {
                throw new InvalidStatusEventException();
            }
            var occurredAt = OffsetDateTime.parse(timestamp);
            if (occurredAt.getYear() < 1) {
                throw new InvalidStatusEventException();
            }
            return new StatusChangeEvent(UUID.fromString(id), occurredAt.toInstant(),
                    number(root.path("customerId"), 1, Long.MAX_VALUE),
                    CustomerStatus.valueOf(text(root, "status")),
                    number(root.path("expectedVersion"), 0, Long.MAX_VALUE - 1));
        } catch (Exception ex) {
            // Nao reter causas de parsing que podem conter o corpo recebido.
            throw new InvalidStatusEventException();
        }
    }

    private String text(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isTextual()) {
            throw new InvalidStatusEventException();
        }
        return value.textValue();
    }

    private long number(JsonNode value, long min, long max) {
        if (!value.isNumber()) {
            throw new InvalidStatusEventException();
        }
        BigInteger integer = value.decimalValue().toBigIntegerExact();
        if (integer.compareTo(BigInteger.valueOf(min)) < 0 || integer.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new InvalidStatusEventException();
        }
        return integer.longValueExact();
    }
}
