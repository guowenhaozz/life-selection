package com.hmdp.reliability;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderProcess;
import com.hmdp.entity.VoucherOrderRecoveryAudit;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderDeadLetterMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.mapper.VoucherOrderRecoveryAuditMapper;
import com.hmdp.service.OrderReliabilityService;
import com.hmdp.service.VoucherOrderRecoveryStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderReliabilityServiceTest {

    @Mock
    private VoucherOrderProcessMapper processMapper;
    @Mock
    private VoucherOrderMapper orderMapper;
    @Mock
    private VoucherOrderDeadLetterMapper deadLetterMapper;
    @Mock
    private RedisReservationTracker reservationTracker;
    @Mock
    private VoucherOrderPublisher publisher;
    @Mock
    private VoucherOrderRecoveryStateService recoveryStateService;
    @Mock
    private VoucherOrderRecoveryAuditMapper auditMapper;

    private OrderReliabilityService service;

    @BeforeEach
    void setUp() {
        service = new OrderReliabilityService(processMapper, orderMapper, deadLetterMapper,
                auditMapper, reservationTracker, publisher, recoveryStateService);
    }

    @Test
    void ownerCanQueryTrackedOrderButOtherUserCannot() {
        VoucherOrderProcess process = pendingProcess(101L, 7L, 2L);
        when(processMapper.selectById(101L)).thenReturn(process);

        Result ownerResult = service.queryStatus(101L, 7L);
        Result otherResult = service.queryStatus(101L, 8L);

        assertTrue(ownerResult.getSuccess());
        assertFalse(otherResult.getSuccess());
        assertEquals("无权查看该订单", otherResult.getErrorMsg());
    }

    @Test
    void persistedOrderCannotBeReplayedOrRefunded() {
        VoucherOrderProcess process = pendingProcess(101L, 7L, 2L);
        process.setProcessStatus(OrderProcessStatus.PERSISTED.name());
        when(processMapper.selectById(101L)).thenReturn(process);

        Result replay = service.replay(101L, "request-1", "manual retry", 99L);
        Result refund = service.refund(101L, "request-2", "manual refund", 99L);

        assertFalse(replay.getSuccess());
        assertEquals("订单已经落库，不能重放", replay.getErrorMsg());
        assertFalse(refund.getSuccess());
        assertEquals("订单已经落库，不能回补", refund.getErrorMsg());
    }

    @Test
    void stalePendingOrderWithoutDatabaseOrderBecomesManualReview() {
        VoucherOrderProcess process = pendingProcess(101L, 7L, 2L);
        when(processMapper.findUnresolvedBefore(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(Collections.singletonList(process));
        when(orderMapper.selectById(101L)).thenReturn(null);
        when(processMapper.markManualReview(101L, "ORDER_NOT_PERSISTED_AFTER_TIMEOUT"))
                .thenReturn(1);

        int reviewed = service.reconcileBatch(LocalDateTime.now(), 100);

        assertEquals(1, reviewed);
    }

    @Test
    void manualReviewOrderCanBeReplayedWithoutAnotherReservation() {
        VoucherOrderProcess process = pendingProcess(101L, 7L, 2L);
        process.setProcessStatus(OrderProcessStatus.MANUAL_REVIEW.name());
        when(processMapper.selectById(101L)).thenReturn(process);
        when(auditMapper.insert(any(VoucherOrderRecoveryAudit.class))).thenReturn(1);
        when(publisher.publish(any(VoucherOrder.class))).thenReturn(OrderPublishStatus.CONFIRMED);
        when(processMapper.markReplayPending(101L)).thenReturn(1);

        Result result = service.replay(101L, "request-1", "retry dead letter", 99L);

        assertTrue(result.getSuccess());
        assertEquals(101L, result.getData());
    }

    @Test
    void unresolvedOrderCanBeRefundedOnlyAfterDatabaseCancellationMarker() {
        VoucherOrderProcess process = pendingProcess(101L, 7L, 2L);
        process.setProcessStatus(OrderProcessStatus.MANUAL_REVIEW.name());
        when(processMapper.selectById(101L)).thenReturn(process);
        when(orderMapper.selectById(101L)).thenReturn(null);
        when(auditMapper.insert(any(VoucherOrderRecoveryAudit.class))).thenReturn(1);
        when(reservationTracker.refund(any(VoucherOrder.class))).thenReturn(1);
        when(recoveryStateService.prepareRefund(101L)).thenReturn(process);
        when(recoveryStateService.markRefunded(101L)).thenReturn(true);

        Result result = service.refund(101L, "request-2", "confirmed missing", 99L);

        assertTrue(result.getSuccess());
        assertEquals(101L, result.getData());
    }

    @Test
    void duplicateRecoveryRequestReturnsRecordedResult() {
        VoucherOrderRecoveryAudit audit = new VoucherOrderRecoveryAudit();
        audit.setRequestId("request-3");
        audit.setOrderId(101L);
        audit.setAction("REFUND");
        audit.setResult("SUCCESS");
        when(auditMapper.selectByRequestId("request-3")).thenReturn(audit);

        Result result = service.refund(101L, "request-3", "duplicate click", 99L);

        assertTrue(result.getSuccess());
        assertEquals(101L, result.getData());
    }

    private VoucherOrderProcess pendingProcess(Long orderId, Long userId, Long voucherId) {
        VoucherOrderProcess process = new VoucherOrderProcess();
        process.setOrderId(orderId);
        process.setUserId(userId);
        process.setVoucherId(voucherId);
        process.setPublishStatus(OrderPublishStatus.UNKNOWN.name());
        process.setProcessStatus(OrderProcessStatus.PENDING.name());
        process.setReservedAt(LocalDateTime.now().minusMinutes(2));
        return process;
    }
}
