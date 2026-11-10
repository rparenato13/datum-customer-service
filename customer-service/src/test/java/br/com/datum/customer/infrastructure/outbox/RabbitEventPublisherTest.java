package br.com.datum.customer.infrastructure.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitEventPublisherTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final OutboxProperties properties = new OutboxProperties(false, 1000, 10, 100, 30000);
    private final RabbitEventPublisher publisher = new RabbitEventPublisher(template, properties);
    private final OutboxEvent event = new OutboxEvent(UUID.randomUUID(), 123L, "CUSTOMER_CREATED", 1,
            Instant.parse("2026-11-10T03:00:00Z"), "{\"payload\":\"fixed bytes\"}", 1, UUID.randomUUID());

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWaitForConfirmationAndSendOnlyApprovedMetadata(boolean ack) {
        doAnswer(call -> {
            var message = call.getArgument(2, Message.class);
            var metadata = message.getMessageProperties();
            assertArrayEquals(event.payload().getBytes(StandardCharsets.UTF_8), message.getBody());
            assertEquals("application/json", metadata.getContentType());
            assertEquals("UTF-8", metadata.getContentEncoding());
            assertEquals(MessageDeliveryMode.PERSISTENT, metadata.getDeliveryMode());
            assertEquals(event.eventId().toString(), metadata.getMessageId());
            assertEquals(event.eventId().toString(), metadata.getCorrelationId());
            assertEquals(event.eventType(), metadata.getType());
            assertEquals(java.util.Map.of("x-correlation-id", event.correlationId().toString()), metadata.getHeaders());
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(ack, "not logged"));
            return null;
        }).when(template).send(eq(CustomerCreatedEventCodec.EXCHANGE), eq(CustomerCreatedEventCodec.ROUTING_KEY), any(Message.class), any(CorrelationData.class));
        assertEquals(ack ? EventPublisher.Result.CONFIRMED : EventPublisher.Result.NACK, publisher.publish(event));
        verify(template, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void shouldRejectReturnedMessageEvenWithPositiveConfirm() {
        doAnswer(call -> {
            var correlation = call.getArgument(3, CorrelationData.class);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "secret not logged", "exchange", "key"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertEquals(EventPublisher.Result.RETURNED, publisher.publish(event));
    }

    @Test
    void shouldLeaveAmbiguousConfirmationPending() {
        assertEquals(EventPublisher.Result.CONFIRM_TIMEOUT, publisher.publish(event));
    }

    @Test
    void shouldHandleExceptionalConfirmationWithoutLeakingCause() {
        doAnswer(call -> {
            call.getArgument(3, CorrelationData.class).getFuture().completeExceptionally(new IllegalStateException("secret"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertEquals(EventPublisher.Result.BROKER_UNAVAILABLE, publisher.publish(event));
    }

    @Test
    void shouldHandleConnectionFailureWithoutThrowingToCaller() {
        doThrow(new AmqpConnectException(new ConnectException("secret"))).when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertEquals(EventPublisher.Result.BROKER_UNAVAILABLE, publisher.publish(event));
    }

    @Test
    void shouldPreserveInterruption() {
        Thread.currentThread().interrupt();
        try {
            assertEquals(EventPublisher.Result.INTERRUPTED, publisher.publish(event));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
