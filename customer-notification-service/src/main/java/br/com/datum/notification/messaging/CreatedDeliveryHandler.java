package br.com.datum.notification.messaging;

import br.com.datum.notification.application.CustomerCreatedEvent;
import br.com.datum.notification.application.NotificationService;
import br.com.datum.notification.application.NotificationDecision;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;

import java.io.IOException;

public class CreatedDeliveryHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CreatedDeliveryHandler.class);
    private final CustomerCreatedCodec codec;
    private final CreatedMessageMetadata metadata;
    private final NotificationService service;
    private final NotificationRetrySender retry;
    private final NotificationChannelRecovery recovery;

    public CreatedDeliveryHandler(CustomerCreatedCodec codec, CreatedMessageMetadata metadata, NotificationService service,
                                 NotificationRetrySender retry, NotificationChannelRecovery recovery) {
        this.codec = codec;
        this.metadata = metadata;
        this.service = service;
        this.retry = retry;
        this.recovery = recovery;
    }

    public void onMessage(Message message, Channel channel) throws IOException {
        try {
            handle(message, channel);
        } catch (IOException ex) {
            // Confirmacao de entrega incerta: nunca substituir ACK perdido por
            // NACK de negocio. O inbox permite redelivery apos fechar o canal.
            LOG.warn("Confirmacao de notificacao interrompida. code=SETTLEMENT_UNCERTAIN");
            recovery.abandon(channel);
        }
    }

    private void handle(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        CustomerCreatedEvent event;
        int attempt;
        try {
            event = codec.decode(message.getBody());
            attempt = metadata.attempt(message.getMessageProperties(), event);
        } catch (InvalidCreatedEventException ex) {
            LOG.warn("Mensagem de criacao rejeitada. code=INVALID_MESSAGE");
            channel.basicNack(tag, false, false);
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
                channel.basicNack(tag, false, false);
            } else if (transfer(message, event, attempt + 1)) {
                channel.basicAck(tag, false);
            } else {
                LOG.error("Transferencia de retry nao confirmada. eventId={} code=RETRY_HANDOFF_FAILED", event.eventId());
                // Sem ACK: abortar o canal devolve a entrega ao broker. A pausa
                // anterior ao aborto impede redelivery imediata em loop apertado.
                recovery.abandon(channel);
            }
            return;
        }
        if (decision.accepted()) {
            channel.basicAck(tag, false);
            LOG.info("Evento de criacao confirmado. eventId={} code={}", event.eventId(), decision.name());
        } else {
            channel.basicNack(tag, false, false);
            LOG.warn("Evento de criacao rejeitado. eventId={} code={}", event.eventId(), decision.name());
        }
    }

    private boolean transfer(Message message, CustomerCreatedEvent event, int attempt) {
        try {
            return retry.send(message, event, attempt);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
