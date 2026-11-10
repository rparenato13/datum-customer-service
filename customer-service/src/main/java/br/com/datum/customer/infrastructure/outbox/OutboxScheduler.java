package br.com.datum.customer.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxScheduler.class);
    private final OutboxRelay relay;

    public OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${customer.outbox.poll-delay-ms}")
    public void poll() {
        try {
            relay.runOnce();
        } catch (Exception ex) {
            // Uma falha apos o envio pode gerar duplicata, nunca ACK local ficticio.
            // A reserva expira e outra tentativa recupera o mesmo evento.
            LOG.error("Publicador outbox interrompeu o ciclo. Tipo={}", ex.getClass().getSimpleName());
        }
    }
}
