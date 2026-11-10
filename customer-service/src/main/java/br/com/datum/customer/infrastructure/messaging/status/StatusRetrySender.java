package br.com.datum.customer.infrastructure.messaging.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import org.springframework.amqp.core.Message;

public interface StatusRetrySender {
    boolean send(Message original, StatusChangeEvent event, int nextAttempt);
}
