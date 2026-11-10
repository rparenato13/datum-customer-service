package br.com.datum.customer.infrastructure.messaging.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ConfirmedStatusRetrySender implements StatusRetrySender {
    private final RabbitTemplate template;
    private final StatusConsumerProperties properties;

    public ConfirmedStatusRetrySender(RabbitTemplate template, StatusConsumerProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public boolean send(Message original, StatusChangeEvent event, int nextAttempt) {
        if (nextAttempt < 1 || nextAttempt > 3) {
            throw new IllegalArgumentException("Rodada de retry invalida.");
        }
        var outgoing = new MessageProperties();
        outgoing.setContentType("application/json");
        outgoing.setContentEncoding("UTF-8");
        outgoing.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        outgoing.setMessageId(event.eventId().toString());
        outgoing.setCorrelationId(event.eventId().toString());
        outgoing.setType(StatusChangeEvent.TYPE);
        outgoing.setHeader("x-retry-attempt", nextAttempt);
        var correlation = new CorrelationData(event.eventId() + ":" + nextAttempt + ":" + UUID.randomUUID());
        try {
            template.send(StatusMessageMetadata.RETRY, StatusChangeEvent.CONSUMER + ".retry." + nextAttempt,
                    new Message(original.getBody(), outgoing), correlation);
            return correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS).isAck()
                    && correlation.getReturned() == null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (AmqpException | ExecutionException | TimeoutException ex) {
            return false;
        }
    }
}
