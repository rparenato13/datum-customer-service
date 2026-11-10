package br.com.datum.notification.messaging;

import br.com.datum.notification.application.CustomerCreatedEvent;
import br.com.datum.notification.application.NotificationService;
import br.com.datum.notification.application.NotificationDecision;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;

import java.io.IOException;
import br.com.datum.notification.observability.CorrelationContext;
import br.com.datum.notification.observability.Telemetry;
import org.slf4j.event.Level;

public class CreatedDeliveryHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CreatedDeliveryHandler.class);
    private final Telemetry telemetry;
    private final CustomerCreatedCodec codec;
    private final CreatedMessageMetadata metadata;
    private final NotificationService service;
    private final NotificationRetrySender retry;
    private final NotificationChannelRecovery recovery;

    public CreatedDeliveryHandler(CustomerCreatedCodec codec, CreatedMessageMetadata metadata, NotificationService service,
                                 NotificationRetrySender retry, NotificationChannelRecovery recovery) {
        this(codec, metadata, service, retry, recovery, Telemetry.standalone());
    }

    public CreatedDeliveryHandler(CustomerCreatedCodec codec, CreatedMessageMetadata metadata, NotificationService service,
                                 NotificationRetrySender retry, NotificationChannelRecovery recovery, Telemetry telemetry) {
        this.telemetry = telemetry;
        this.codec = codec;
        this.metadata = metadata;
        this.service = service;
        this.retry = retry;
        this.recovery = recovery;
    }

    public void onMessage(Message message, Channel channel) throws IOException {
        var delivery = new Delivery();
        long start = System.nanoTime();
        Object incoming = message.getMessageProperties().getHeaders().get(CorrelationContext.MESSAGE_HEADER);
        try (var scope = CorrelationContext.open(incoming instanceof String text ? text : null)) {
            try {
                handle(message, channel, delivery);
            } catch (IOException ex) {
                delivery.ack = "UNCERTAIN";
                delivery.outcome = "SETTLEMENT_UNCERTAIN";
                LOG.warn("Confirmacao interrompida. code=SETTLEMENT_UNCERTAIN");
                recovery.abandon(channel);
            } finally {
                long elapsed = System.nanoTime() - start;
                telemetry.duration("datum.messaging.process.duration", elapsed, "consumer", "customer-notification-service.created.v1",
                        "outcome", delivery.outcome, "attempt", Integer.toString(delivery.attempt), "ack", delivery.ack);
                telemetry.event(LOG, "ACK".equals(delivery.ack) ? Level.INFO : Level.WARN,
                                "message.consume", delivery.outcome, elapsed)
                        .addKeyValue("consumer", "customer-notification-service.created.v1").addKeyValue("queue", "customer-notification-service.created.v1").addKeyValue("eventType", "CUSTOMER_CREATED")
                        .addKeyValue("eventId", delivery.eventId).addKeyValue("retryCount", delivery.attempt)
                        .addKeyValue("ackOutcome", delivery.ack).addKeyValue("duplicate", "DUPLICATE".equals(delivery.outcome))
                        .addKeyValue("dlqReason", "REJECT".equals(delivery.ack) ? delivery.outcome : "none").log("Entrega de mensagem concluida.");
            }
        }
    }

    private static final class Delivery {
        String eventId = "none";
        int attempt;
        String outcome = "PROCESSING_FAILURE";
        String ack = "UNSETTLED";
    }

    private void handle(Message message, Channel channel, Delivery delivery) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        CustomerCreatedEvent event;
        int attempt;
        try {
            event = codec.decode(message.getBody());
            attempt = metadata.attempt(message.getMessageProperties(), event);
            delivery.attempt = attempt;
            delivery.eventId = event.eventId().toString();
            Object incoming = message.getMessageProperties().getHeaders().get(CorrelationContext.MESSAGE_HEADER);
            CorrelationContext.use(incoming instanceof String text ? text : event.eventId().toString());
        } catch (InvalidCreatedEventException ex) {
            LOG.warn("Mensagem de criacao rejeitada. code=INVALID_MESSAGE");
            delivery.outcome = "INVALID_MESSAGE";
            channel.basicNack(tag, false, false);
            delivery.ack = "REJECT";
            return;
        }
        NotificationDecision decision;
        try {
            // O proxy transacional confirma/rollbacka antes de devolver o resultado.
            decision = service.process(event);
        } catch (RuntimeException ex) {
            LOG.warn("Processamento de notificacao falhou. eventId={} code=PROCESSING_FAILURE tipo={}",
                    event.eventId(), ex.getClass().getSimpleName());
            if (attempt >= 3) {
                delivery.outcome = "RETRY_EXHAUSTED";
                channel.basicNack(tag, false, false);
                delivery.ack = "REJECT";
            } else if (transfer(message, event, attempt + 1)) {
                delivery.outcome = "RETRY_SCHEDULED";
                channel.basicAck(tag, false);
                delivery.ack = "ACK";
            } else {
                delivery.outcome = "RETRY_HANDOFF_FAILED";
                LOG.error("Transferencia de retry nao confirmada. eventId={} code=RETRY_HANDOFF_FAILED", event.eventId());
                // Sem ACK: abortar o canal devolve a entrega ao broker. A pausa
                // anterior ao aborto impede redelivery imediata em loop apertado.
                recovery.abandon(channel);
            }
            return;
        }
        delivery.outcome = decision.name();
        if (decision.accepted()) {
            channel.basicAck(tag, false);
            delivery.ack = "ACK";
            LOG.info("Evento de criacao confirmado. eventId={} code={}", event.eventId(), decision.name());
        } else {
            channel.basicNack(tag, false, false);
            delivery.ack = "REJECT";
            LOG.warn("Evento de criacao rejeitado. eventId={} code={}", event.eventId(), decision.name());
        }
    }

    private boolean transfer(Message message, CustomerCreatedEvent event, int attempt) {
        long start = System.nanoTime();
        boolean confirmed = false;
        try {
            confirmed = retry.send(message, event, attempt);
            return confirmed;
        } catch (RuntimeException ex) {
            return false;
        } finally {
            telemetry.duration("datum.messaging.retry.transfer.duration", System.nanoTime() - start,
                    "consumer", "customer-notification-service.created.v1", "attempt", Integer.toString(attempt),
                    "outcome", confirmed ? "confirmed" : "unconfirmed");
        }
    }
}
