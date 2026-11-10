package br.com.datum.customer.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.messaging.status.InvalidStatusEventException;
import br.com.datum.customer.infrastructure.messaging.status.StatusMessageMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class StatusMessageMetadataTest {
    final StatusChangeEvent event = StatusFixtures.event(123, 0, CustomerStatus.INACTIVE);
    final StatusMessageMetadata metadata = new StatusMessageMetadata();

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void shouldAcceptTrustedSourceAndBoundedRetry(int attempt) {
        var properties = StatusFixtures.received(event, attempt).getMessageProperties();
        properties.setHeader("x-acquired-count", 2L);
        properties.setHeader("x-delivery-count", 1L);
        assertEquals(attempt, metadata.attempt(properties, event));
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "type", "correlation", "encoding", "content", "route", "exchange", "header", "forgedRetry", "forgedDeath", "nonPersistent"})
    void shouldRejectMismatchedOrForgedMetadata(String field) {
        var properties = StatusFixtures.received(event, 0).getMessageProperties();
        switch (field) {
            case "identity" -> properties.setMessageId("not-an-id");
            case "type" -> properties.setType("CUSTOMER_CREATED");
            case "correlation" -> properties.setCorrelationId("wrong");
            case "encoding" -> properties.setContentEncoding("UTF-16");
            case "content" -> properties.setContentType("text/plain");
            case "route" -> properties.setReceivedRoutingKey("customer.created.v1");
            case "exchange" -> properties.setReceivedExchange(StatusMessageMetadata.REDELIVERY);
            case "header" -> properties.setHeader("__TypeId__", "java.lang.Object");
            case "forgedRetry" -> properties.setHeader("x-retry-attempt", 1);
            case "forgedDeath" -> properties.setHeader("x-death", java.util.List.of());
            case "nonPersistent" -> properties.setReceivedDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
        }
        assertThrows(InvalidStatusEventException.class, () -> metadata.attempt(properties, event));
    }

    @Test
    void shouldRejectUnboundedOrNonIntegralRetryCounters() {
        for (var value : new Object[]{0, 4, -1, "1", 1.0, true, null}) {
            var properties = StatusFixtures.received(event, 1).getMessageProperties();
            properties.setHeader("x-retry-attempt", value);
            assertThrows(InvalidStatusEventException.class, () -> metadata.attempt(properties, event));
        }
    }
}
