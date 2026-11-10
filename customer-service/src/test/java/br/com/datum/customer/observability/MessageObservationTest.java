package br.com.datum.customer.observability;

import br.com.datum.customer.infrastructure.messaging.status.*;
import br.com.datum.customer.application.status.StatusChangeService;
import br.com.datum.customer.application.status.StatusDecision;
import br.com.datum.customer.status.StatusFixtures;
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
    final StatusChangeService service = mock(StatusChangeService.class);
    final StatusRetrySender retry = mock(StatusRetrySender.class);
    final StatusChannelRecovery recovery = mock(StatusChannelRecovery.class);
    final Channel channel = mock(Channel.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StatusDeliveryHandler handler = new StatusDeliveryHandler(new StatusChangeCodec(new ObjectMapper()), new StatusMessageMetadata(),
            service, retry, recovery, new Telemetry(registry, 250));
    final br.com.datum.customer.application.status.StatusChangeEvent event = StatusFixtures.event(123, 0, br.com.datum.customer.domain.CustomerStatus.INACTIVE);

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldScopeCorrelationAroundTransactionAndAckThenRestoreThread(boolean hasHeader) throws Exception {
        String correlation = hasHeader ? UUID.randomUUID().toString() : event.eventId().toString();
        var message = StatusFixtures.received(event, 0);
        if (hasHeader) message.getMessageProperties().setHeader(CorrelationContext.MESSAGE_HEADER, correlation);
        when(service.process(event)).thenAnswer(call -> {
            assertEquals(correlation, CorrelationContext.id());
            return StatusDecision.APPLIED;
        });
        String outer = UUID.randomUUID().toString();
        try (var scope = CorrelationContext.open(outer)) {
            handler.onMessage(message, channel);
            assertEquals(outer, CorrelationContext.id());
        }
        assertNull(CorrelationContext.id());
        verify(channel).basicAck(42, false);
        assertEquals(1, registry.get("datum.messaging.process.duration").tag("outcome", "APPLIED").timer().count());
        assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(t -> t.getValue().equals(correlation) || t.getValue().equals(event.eventId().toString())));
    }

    @ParameterizedTest @ValueSource(strings = {"private@example.test", "16899535009", "not-a-uuid", ""})
    void shouldRejectUntrustedTransportCorrelationBeforeApplication(String correlation) throws Exception {
        var message = StatusFixtures.received(event, 0);
        message.getMessageProperties().setHeader(CorrelationContext.MESSAGE_HEADER, correlation);
        handler.onMessage(message, channel);
        verifyNoInteractions(service, retry);
        verify(channel).basicNack(42, false, false);
        assertEquals(1, registry.get("datum.messaging.process.duration").tag("outcome", "INVALID_MESSAGE").timer().count());
        assertNull(CorrelationContext.id());
    }
}
