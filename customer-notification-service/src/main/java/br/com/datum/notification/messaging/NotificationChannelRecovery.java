package br.com.datum.notification.messaging;

import com.rabbitmq.client.Channel;

import java.io.IOException;

@FunctionalInterface
public interface NotificationChannelRecovery {
    void abandon(Channel channel) throws IOException;
}
