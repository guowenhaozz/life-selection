package com.hmdp.reliability;

public final class OrderProcessTransitions {

    private OrderProcessTransitions() {
    }

    public static boolean canPersist(OrderProcessStatus status) {
        return status == OrderProcessStatus.PENDING || status == OrderProcessStatus.MANUAL_REVIEW;
    }

    public static boolean canReplay(OrderProcessStatus status) {
        return status == OrderProcessStatus.PENDING || status == OrderProcessStatus.MANUAL_REVIEW;
    }

    public static boolean canRequestRefund(OrderProcessStatus status) {
        return status == OrderProcessStatus.PENDING
                || status == OrderProcessStatus.MANUAL_REVIEW
                || status == OrderProcessStatus.CANCEL_PENDING_REFUND;
    }

    public static boolean canMarkRefunded(OrderProcessStatus status) {
        return status == OrderProcessStatus.CANCEL_PENDING_REFUND;
    }
}
