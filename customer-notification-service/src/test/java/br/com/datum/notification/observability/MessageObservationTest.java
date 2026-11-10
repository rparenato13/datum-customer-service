package br.com.datum.notification.observability;

import br.com.datum.notification.messaging.*;
import br.com.datum.notification.application.NotificationService;
import br.com.datum.notification.application.NotificationDecision;
import br.com.datum.notification.CreatedFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageObservationTest {
    final NotificationService service = mock(NotificationService.class);
    final NotificationRetrySender retry = mock(NotificationRetrySender.class);
    final NotificationChannelRecovery recovery = mock(NotificationChannelRecovery.class);
    final Channel channel = mock(Channel.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CreatedDeliveryHandler handler = new CreatedDeliveryHandler(new CustomerCreatedCodec(new ObjectMapper()), new CreatedMessageMetadata(),
            service, retry, recovery, new Telemetry(registry, 250));
    final br.com.datum.notification.application.CustomerCreatedEvent event = CreatedFixtures.event(123);

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldScopeCorrelationAroundTransactionAndAckThenRestoreThread(boolean hasHeader) throws Exception {
        String correlation = hasHeader ? UUID.randomUUID().toString() : event.eventId().toString();
        var message = CreatedFixtures.received(event, 0);
        if (hasHeader) message.getMessageProperties().setHeader(CorrelationContext.MESSAGE_HEADER, correlation);
        when(service.process(event)).thenAnswer(call -> {
            assertEquals(correlation, CorrelationContext.id());
            return NotificationDecision.NOTIFICATION_REGISTERED;
        });
        String outer = UUID.randomUUID().toString();
        try (var scope = CorrelationContext.open(outer)) {
            handler.onMessage(message, channel);
            assertEquals(outer, CorrelationContext.id());
        }
        assertNull(CorrelationContext.id());
        verify(channel).basicAck(42, false);
        assertEquals(1, registry.get("datum.messaging.process.duration").tag("outcome", "NOTIFICATION_REGISTERED").timer().count());
        assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(t -> t.getValue().equals(correlation) || t.getValue().equals(event.eventId().toString())));
    }

    @ParameterizedTest @ValueSource(strings = {"private@example.test", "16899535009", "not-a-uuid", ""})
    void shouldRejectUntrustedTransportCorrelationBeforeApplication(String correlation) throws Exception {
        var message = CreatedFixtures.received(event, 0);
        message.getMessageProperties().setHeader(CorrelationContext.MESSAGE_HEADER, correlation);
        handler.onMessage(message, channel);
        verifyNoInteractions(service, retry);
        verify(channel).basicNack(42, false, false);
        assertEquals(1, registry.get("datum.messaging.process.duration").tag("outcome", "INVALID_MESSAGE").timer().count());
        assertNull(CorrelationContext.id());
    }
}
