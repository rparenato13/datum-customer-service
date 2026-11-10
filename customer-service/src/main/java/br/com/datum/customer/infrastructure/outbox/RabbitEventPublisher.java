package br.com.datum.customer.infrastructure.outbox;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RabbitEventPublisher implements EventPublisher {
    private final RabbitTemplate template;
    private final OutboxProperties properties;

    public RabbitEventPublisher(RabbitTemplate template, OutboxProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public Result publish(OutboxEvent event) {
        var metadata = new MessageProperties();
        metadata.setContentType("application/json");
        metadata.setContentEncoding("UTF-8");
        metadata.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        metadata.setMessageId(event.eventId().toString());
        metadata.setCorrelationId(event.eventId().toString());
        metadata.setType(event.eventType());
        var correlation = new CorrelationData(event.eventId() + ":" + event.claimToken());
        try {
            template.send(CustomerCreatedEventCodec.EXCHANGE, CustomerCreatedEventCodec.ROUTING_KEY,
                    new Message(event.payload().getBytes(StandardCharsets.UTF_8), metadata), correlation);
            var confirm = correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
            // Spring preenche returned antes de completar o future do confirm.
            if (correlation.getReturned() != null) {
                return Result.RETURNED;
            }
            return confirm.isAck() ? Result.CONFIRMED : Result.NACK;
        } catch (TimeoutException ex) {
            return Result.CONFIRM_TIMEOUT;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.INTERRUPTED;
        } catch (AmqpException | ExecutionException ex) {
            return Result.BROKER_UNAVAILABLE;
        }
    }
}
