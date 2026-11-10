package br.com.datum.customer.status;

import br.com.datum.customer.application.status.StatusChangeService;
import br.com.datum.customer.application.status.StatusDecision;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.messaging.status.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class StatusDeliveryHandlerTest {
    final StatusChangeService service = mock(StatusChangeService.class);
    final StatusRetrySender retry = mock(StatusRetrySender.class);
    final StatusChannelRecovery recovery = mock(StatusChannelRecovery.class);
    final Channel channel = mock(Channel.class);
    final StatusDeliveryHandler handler = new StatusDeliveryHandler(new StatusChangeCodec(new ObjectMapper()),
            new StatusMessageMetadata(), service, retry, recovery);
    final br.com.datum.customer.application.status.StatusChangeEvent event = StatusFixtures.event(123, 0, CustomerStatus.INACTIVE);

    @ParameterizedTest
    @EnumSource(StatusDecision.class)
    void shouldSettleOnlyAfterTransactionalServiceReturns(StatusDecision decision) throws Exception {
        when(service.process(event)).thenReturn(decision);
        handler.onMessage(StatusFixtures.received(event, 0), channel);
        var order = inOrder(service, channel);
        order.verify(service).process(event);
        if (decision.accepted()) { order.verify(channel).basicAck(42, false); }
        else { order.verify(channel).basicNack(42, false, false); }
        verifyNoInteractions(retry, recovery);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void shouldAckOriginalOnlyAfterConfirmedRetryHandoff(int attempt) throws Exception {
        when(service.process(any())).thenThrow(new IllegalStateException("SENSITIVE_STATUS_ONLY"));
        when(retry.send(any(), any(), eq(attempt + 1))).thenReturn(true);
        var message = StatusFixtures.received(event, attempt);
        handler.onMessage(message, channel);
        var order = inOrder(retry, channel);
        order.verify(retry).send(message, event, attempt + 1);
        order.verify(channel).basicAck(42, false);
        verifyNoInteractions(recovery);
    }

    @Test
    void shouldRouteExhaustedTransientFailureToDlqWithoutFurtherPublish(CapturedOutput output) throws Exception {
        when(service.process(any())).thenThrow(new IllegalStateException("SENSITIVE_STATUS_ONLY"));
        handler.onMessage(StatusFixtures.received(event, 3), channel);
        verify(channel).basicNack(42, false, false);
        verifyNoInteractions(retry, recovery);
        assertFalse(output.getAll().contains("SENSITIVE_STATUS_ONLY"));
    }

    @Test
    void shouldNeverAckWhenRetryTransferIsUncertain() throws Exception {
        when(service.process(any())).thenThrow(new IllegalStateException());
        handler.onMessage(StatusFixtures.received(event, 0), channel);
        verify(recovery).abandon(channel);
        verifyNoInteractions(channel);
    }

    @Test
    void shouldRejectInvalidMetadataBeforeDatabaseAccess() throws Exception {
        var message = StatusFixtures.received(event, 0);
        message.getMessageProperties().setHeader("Authorization", "SENSITIVE_STATUS_ONLY");
        handler.onMessage(message, channel);
        verify(channel).basicNack(42, false, false);
        verifyNoInteractions(service, retry, recovery);
    }

    @Test
    void shouldAbandonUncertainAckWithoutRejectingCommittedEvent() throws Exception {
        when(service.process(event)).thenReturn(StatusDecision.APPLIED);
        doThrow(new java.io.IOException("private cause")).when(channel).basicAck(42, false);
        handler.onMessage(StatusFixtures.received(event, 0), channel);
        verify(recovery).abandon(channel);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(retry);
    }
}
