package br.com.datum.customer.infrastructure.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class OutboxSchedulerTest {
    @Test
    void shouldContainUnexpectedFailureWithoutLeakingMessageAndAllowNextPoll(CapturedOutput output) {
        var relay = mock(OutboxRelay.class);
        doThrow(new IllegalStateException("SENSITIVE_OUTBOX_EXCEPTION_ONLY")).doNothing().when(relay).runOnce();
        var scheduler = new OutboxScheduler(relay);
        assertDoesNotThrow(scheduler::poll);
        assertDoesNotThrow(scheduler::poll);
        verify(relay, times(2)).runOnce();
        assertTrue(output.getAll().contains("IllegalStateException"));
        assertFalse(output.getAll().contains("SENSITIVE_OUTBOX_EXCEPTION_ONLY"));
    }
}
