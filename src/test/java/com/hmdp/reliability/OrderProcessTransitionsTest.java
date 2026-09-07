package com.hmdp.reliability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderProcessTransitionsTest {

    @Test
    void cancelledOrRefundedReservationRejectsLateConsumer() {
        assertFalse(OrderProcessTransitions.canPersist(OrderProcessStatus.CANCEL_PENDING_REFUND));
        assertFalse(OrderProcessTransitions.canPersist(OrderProcessStatus.REFUNDED));
    }

    @Test
    void onlyUnresolvedReservationCanBeReplayedOrCancelled() {
        assertTrue(OrderProcessTransitions.canReplay(OrderProcessStatus.PENDING));
        assertTrue(OrderProcessTransitions.canReplay(OrderProcessStatus.MANUAL_REVIEW));
        assertFalse(OrderProcessTransitions.canReplay(OrderProcessStatus.PERSISTED));
        assertFalse(OrderProcessTransitions.canReplay(OrderProcessStatus.REFUNDED));

        assertTrue(OrderProcessTransitions.canRequestRefund(OrderProcessStatus.PENDING));
        assertTrue(OrderProcessTransitions.canRequestRefund(OrderProcessStatus.MANUAL_REVIEW));
        assertTrue(OrderProcessTransitions.canRequestRefund(OrderProcessStatus.CANCEL_PENDING_REFUND));
        assertFalse(OrderProcessTransitions.canRequestRefund(OrderProcessStatus.PERSISTED));
        assertFalse(OrderProcessTransitions.canRequestRefund(OrderProcessStatus.REFUNDED));
    }

    @Test
    void refundCompletesOnlyFromPendingRefundState() {
        assertTrue(OrderProcessTransitions.canMarkRefunded(OrderProcessStatus.CANCEL_PENDING_REFUND));
        assertFalse(OrderProcessTransitions.canMarkRefunded(OrderProcessStatus.PENDING));
        assertFalse(OrderProcessTransitions.canMarkRefunded(OrderProcessStatus.PERSISTED));
    }
}
