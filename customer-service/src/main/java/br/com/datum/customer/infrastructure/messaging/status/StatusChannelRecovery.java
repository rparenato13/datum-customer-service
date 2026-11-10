package br.com.datum.customer.infrastructure.messaging.status;

import com.rabbitmq.client.Channel;

import java.io.IOException;

@FunctionalInterface
public interface StatusChannelRecovery {
    void abandon(Channel channel) throws IOException;
}
