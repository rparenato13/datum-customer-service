package br.com.datum.notification.application;

import br.com.datum.notification.infrastructure.persistence.NotificationStore;
import org.springframework.transaction.annotation.Transactional;

public class NotificationService {
    private final NotificationStore store;

    public NotificationService(NotificationStore store) {
        this.store = store;
    }

    @Transactional(timeout = 10)
    public NotificationDecision process(CustomerCreatedEvent event) {
        if (!store.begin(event)) {
            var previous = store.existing(event);
            if (!event.fingerprint().equals(previous.fingerprint())) {
                return NotificationDecision.EVENT_ID_CONFLICT;
            }
            if ("NOTIFICATION_REGISTERED".equals(previous.outcome())) {
                return NotificationDecision.DUPLICATE;
            }
            throw new IllegalStateException("Processamento anterior nao concluido.");
        }
        // O efeito simulado e somente um registro local; nao envia e-mail nem
        // consulta o customer-service ou qualquer outro banco.
        store.register(event);
        return NotificationDecision.NOTIFICATION_REGISTERED;
    }
}
