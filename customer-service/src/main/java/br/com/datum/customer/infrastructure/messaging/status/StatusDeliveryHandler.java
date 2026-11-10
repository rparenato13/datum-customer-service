package br.com.datum.customer.infrastructure.messaging.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.application.status.StatusChangeService;
import br.com.datum.customer.application.status.StatusDecision;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;

import java.io.IOException;

public class StatusDeliveryHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StatusDeliveryHandler.class);
    private final StatusChangeCodec codec;
    private final StatusMessageMetadata metadata;
    private final StatusChangeService service;
    private final StatusRetrySender retry;
    private final StatusChannelRecovery recovery;

    public StatusDeliveryHandler(StatusChangeCodec codec, StatusMessageMetadata metadata, StatusChangeService service,
                                 StatusRetrySender retry, StatusChannelRecovery recovery) {
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
            LOG.warn("Confirmacao status interrompida. code=SETTLEMENT_UNCERTAIN");
            recovery.abandon(channel);
        }
    }

    private void handle(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        StatusChangeEvent event;
        int attempt;
        try {
            event = codec.decode(message.getBody());
            attempt = metadata.attempt(message.getMessageProperties(), event);
        } catch (InvalidStatusEventException ex) {
            LOG.warn("Mensagem status rejeitada. code=INVALID_MESSAGE");
            channel.basicNack(tag, false, false);
            return;
        }
        StatusDecision decision;
        try {
            // O proxy transacional confirma/rollbacka antes de devolver o resultado.
            decision = service.process(event);
        } catch (RuntimeException ex) {
            LOG.warn("Processamento status falhou. eventId={} code=PROCESSING_FAILURE tipo={}",
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
            LOG.info("Evento status confirmado. eventId={} code={}", event.eventId(), decision.name());
        } else {
            channel.basicNack(tag, false, false);
            LOG.warn("Evento status rejeitado. eventId={} code={}", event.eventId(), decision.name());
        }
    }

    private boolean transfer(Message message, StatusChangeEvent event, int attempt) {
        try {
            return retry.send(message, event, attempt);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
