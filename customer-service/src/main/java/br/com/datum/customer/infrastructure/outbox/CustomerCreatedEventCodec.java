package br.com.datum.customer.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CustomerCreatedEventCodec {
    public static final String TYPE = "CUSTOMER_CREATED";
    public static final String EXCHANGE = "datum.customer.events";
    public static final String ROUTING_KEY = "customer.created.v1";
    private static final Set<String> FIELDS = Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "customerId");
    private final ObjectMapper mapper;
    private final ObjectReader reader;

    public CustomerCreatedEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        this.reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String encode(UUID id, long customerId, Instant occurredAt) {
        try {
            return mapper.writeValueAsString(Map.of("eventId", id.toString(), "eventType", TYPE,
                    "schemaVersion", 1, "occurredAt", occurredAt.toString(), "customerId", customerId));
        } catch (Exception ex) {
            throw new IllegalStateException("Nao foi possivel preparar o evento de criacao.");
        }
    }

    public boolean isValid(OutboxEvent event) {
        try {
            if (event.payload().getBytes(StandardCharsets.UTF_8).length > 16384) {
                return false;
            }
            var body = reader.readTree(event.payload());
            if (body == null || !body.isObject() || body.size() != FIELDS.size()) {
                return false;
            }
            var names = new java.util.HashSet<String>();
            body.fieldNames().forEachRemaining(names::add);
            var id = body.path("eventId");
            var timestamp = body.path("occurredAt");
            return names.equals(FIELDS) && TYPE.equals(event.eventType()) && event.schemaVersion() == 1
                    && id.isTextual() && id.textValue().equals(event.eventId().toString())
                    && event.eventId().version() == 4 && event.eventId().variant() == 2
                    && TYPE.equals(body.path("eventType").textValue())
                    && body.path("schemaVersion").isIntegralNumber() && body.path("schemaVersion").canConvertToInt()
                    && body.path("schemaVersion").intValue() == 1
                    && timestamp.isTextual() && timestamp.textValue().matches(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")
                    && Instant.parse(timestamp.textValue()).equals(event.occurredAt())
                    && body.path("customerId").isIntegralNumber() && body.path("customerId").canConvertToLong()
                    && event.customerId() > 0 && body.path("customerId").longValue() == event.customerId();
        } catch (Exception ex) {
            return false;
        }
    }
}
