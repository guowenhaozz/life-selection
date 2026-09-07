package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;

import java.util.Map;
import java.util.Set;

public interface RedisReservationTracker {
    void markPublishStatus(Long orderId, OrderPublishStatus status);
    void markProcessStatus(Long orderId, OrderProcessStatus status);
    int refund(VoucherOrder order);
    void removePending(Long orderId);
    Set<Long> findPendingOrderIds(long cutoffMillis, int limit);
    Map<String, String> loadReservation(Long orderId);
}
