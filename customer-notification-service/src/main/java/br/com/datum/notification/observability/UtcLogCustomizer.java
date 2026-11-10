package br.com.datum.notification.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

public final class UtcLogCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {
    @Override public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.add("timestamp", event -> event.getInstant().toString());
    }
}
