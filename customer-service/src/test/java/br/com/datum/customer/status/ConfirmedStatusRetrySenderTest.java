package br.com.datum.customer.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.messaging.status.ConfirmedStatusRetrySender;
import br.com.datum.customer.infrastructure.messaging.status.StatusConsumerProperties;
import br.com.datum.customer.infrastructure.messaging.status.StatusMessageMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfirmedStatusRetrySenderTest {
    final RabbitTemplate template = mock(RabbitTemplate.class);
    final ConfirmedStatusRetrySender sender = new ConfirmedStatusRetrySender(template,
            new StatusConsumerProperties(true, 10, 1000, 100));
    final StatusChangeEvent event = StatusFixtures.event(123, 0, CustomerStatus.INACTIVE);

    @Test
    void shouldPreserveOriginalHttpCorrelationWhenRebuildingRetryProperties() {
        String correlation = java.util.UUID.randomUUID().toString();
        var original = StatusFixtures.received(event, 0);
        original.getMessageProperties().setHeader("x-correlation-id", correlation);
        doAnswer(call -> {
            var message = call.getArgument(2, Message.class);
            assertEquals(correlation, message.getMessageProperties().getHeader("x-correlation-id"));
            assertEquals(event.eventId().toString(), message.getMessageProperties().getCorrelationId());
            assertArrayEquals(original.getBody(), message.getBody());
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertTrue(sender.send(original, event, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void shouldPreserveBodyAndIdentityAndDeriveOnlyOwnRetryDestination(int next) {
        var original = StatusFixtures.received(event, next - 1);
        doAnswer(call -> {
            var message = call.getArgument(2, Message.class);
            var metadata = message.getMessageProperties();
            assertArrayEquals(original.getBody(), message.getBody());
            assertEquals("application/json", metadata.getContentType());
            assertEquals("UTF-8", metadata.getContentEncoding());
            assertEquals(MessageDeliveryMode.PERSISTENT, metadata.getDeliveryMode());
            assertEquals(event.eventId().toString(), metadata.getMessageId());
            assertEquals(event.eventId().toString(), metadata.getCorrelationId());
            assertEquals(StatusChangeEvent.TYPE, metadata.getType());
            assertEquals(java.util.Map.of("x-correlation-id", event.eventId().toString(), "x-retry-attempt", next), metadata.getHeaders());
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq(StatusMessageMetadata.RETRY), eq(StatusChangeEvent.CONSUMER + ".retry." + next),
                any(Message.class), any(CorrelationData.class));
        assertTrue(sender.send(original, event, next));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nack", "returned", "exceptional", "send-failure", "timeout"})
    void shouldNotConfirmAmbiguousOrFailedHandoff(String failure) {
        doAnswer(call -> {
            var correlation = call.getArgument(3, CorrelationData.class);
            switch (failure) {
                case "nack" -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "private cause"));
                case "returned" -> {
                    correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "private cause", "exchange", "key"));
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                }
                case "exceptional" -> correlation.getFuture().completeExceptionally(new IllegalStateException("private cause"));
                case "send-failure" -> throw new AmqpException("private cause");
                default -> { }
            }
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertFalse(sender.send(StatusFixtures.received(event, 0), event, 1));
    }

    @Test
    void shouldPreserveInterruptedThread() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(sender.send(StatusFixtures.received(event, 0), event, 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
