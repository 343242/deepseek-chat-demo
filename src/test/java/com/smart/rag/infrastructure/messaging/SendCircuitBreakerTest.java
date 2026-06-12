package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.messaging.MessagingCircuitBreakerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class SendCircuitBreakerTest {

    private static final MessagingProperties.CircuitBreakerConfig CONFIG =
        new MessagingProperties.CircuitBreakerConfig(3, 10_000);

    private FakeClock clock;
    private SendCircuitBreaker cb;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        cb = new SendCircuitBreaker(CONFIG, clock);
    }

    @Nested
    @DisplayName("CLOSED state")
    class ClosedState {
        @Test
        void allowsCalls() {
            assertTrue(cb.isCallAllowed());
        }

        @Test
        void staysClosedOnSuccess() {
            cb.recordSuccess();
            assertEquals(MessagingCircuitBreakerState.CLOSED, cb.state());
        }

        @Test
        void transitionsToOpenAfterThreshold() {
            cb.recordFailure();
            cb.recordFailure();
            assertTrue(cb.isCallAllowed()); // still closed, only 2 failures (threshold=3)
            cb.recordFailure(); // 3rd failure = threshold reached
            assertFalse(cb.isCallAllowed());
            assertEquals(MessagingCircuitBreakerState.OPEN, cb.state());
        }
    }

    @Nested
    @DisplayName("OPEN state")
    class OpenState {
        @BeforeEach
        void tripOpen() {
            cb.recordFailure();
            cb.recordFailure();
            cb.recordFailure(); // trips open (threshold=3)
        }

        @Test
        void rejectsCalls() {
            assertFalse(cb.isCallAllowed());
        }

        @Test
        void transitionsToHalfOpenAfterCooldown() {
            clock.advanceMs(10_001);
            assertEquals(MessagingCircuitBreakerState.HALF_OPEN, cb.state());
            assertTrue(cb.isCallAllowed());
        }
    }

    @Nested
    @DisplayName("HALF_OPEN state")
    class HalfOpenState {
        @BeforeEach
        void enterHalfOpen() {
            cb.recordFailure();
            cb.recordFailure();
            cb.recordFailure(); // open
            clock.advanceMs(10_001); // half-open
        }

        @Test
        void allowsSingleProbe() {
            assertTrue(cb.isCallAllowed());
            assertFalse(cb.isCallAllowed()); // second probe rejected
        }

        @Test
        void probeSuccessClosesCircuit() {
            cb.isCallAllowed(); // consume probe slot
            cb.recordSuccess();
            assertEquals(MessagingCircuitBreakerState.CLOSED, cb.state());
            assertTrue(cb.isCallAllowed());
        }

        @Test
        void probeFailureReopensCircuit() {
            cb.isCallAllowed(); // consume probe slot
            cb.recordFailure();
            assertEquals(MessagingCircuitBreakerState.OPEN, cb.state());
            assertFalse(cb.isCallAllowed());
        }
    }

    private static class FakeClock extends Clock {
        private Instant instant = Instant.EPOCH;

        void advanceMs(long ms) {
            instant = instant.plusMillis(ms);
        }

        @Override
        public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override
        public Clock withZone(ZoneId zone) { return this; }
        @Override
        public Instant instant() { return instant; }
    }
}
