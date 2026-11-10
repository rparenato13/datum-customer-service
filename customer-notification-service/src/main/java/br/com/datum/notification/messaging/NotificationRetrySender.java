package br.com.datum.notification.messaging;

import br.com.datum.notification.application.CustomerCreatedEvent;
import org.springframework.amqp.core.Message;

public interface NotificationRetrySender {
    boolean send(Message original, CustomerCreatedEvent event, int nextAttempt);
}
