package br.com.datum.notification.messaging;

import br.com.datum.notification.application.CustomerCreatedEvent;
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

public class ConfirmedNotificationRetrySender implements NotificationRetrySender {
    private final RabbitTemplate template;
    private final NotificationConsumerProperties properties;

    public ConfirmedNotificationRetrySender(RabbitTemplate template, NotificationConsumerProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public boolean send(Message original, CustomerCreatedEvent event, int nextAttempt) {
        if (nextAttempt < 1 || nextAttempt > 3) {
            throw new IllegalArgumentException("Rodada de retry invalida.");
        }
        var outgoing = new MessageProperties();
        outgoing.setContentType("application/json");
        outgoing.setContentEncoding("UTF-8");
        outgoing.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        outgoing.setMessageId(event.eventId().toString());
        outgoing.setCorrelationId(event.eventId().toString());
        outgoing.setType(CustomerCreatedEvent.TYPE);
        outgoing.setHeader("x-retry-attempt", nextAttempt);
        var correlation = new CorrelationData(event.eventId() + ":" + nextAttempt + ":" + UUID.randomUUID());
        try {
            template.send(CreatedMessageMetadata.RETRY, CustomerCreatedEvent.CONSUMER + ".retry." + nextAttempt,
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
