package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderStatusDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderProcess;
import com.hmdp.entity.VoucherOrderRecoveryAudit;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderDeadLetterMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.mapper.VoucherOrderRecoveryAuditMapper;
import com.hmdp.reliability.OrderPublishStatus;
import com.hmdp.reliability.OrderProcessStatus;
import com.hmdp.reliability.OrderProcessTransitions;
import com.hmdp.reliability.RedisReservationTracker;
import com.hmdp.reliability.VoucherOrderPublisher;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OrderReliabilityService {

    private final VoucherOrderProcessMapper processMapper;
    private final VoucherOrderMapper orderMapper;
    private final VoucherOrderDeadLetterMapper deadLetterMapper;
    private final VoucherOrderRecoveryAuditMapper auditMapper;
    private final RedisReservationTracker reservationTracker;
    private final VoucherOrderPublisher publisher;
    private final VoucherOrderRecoveryStateService recoveryStateService;

    public OrderReliabilityService(VoucherOrderProcessMapper processMapper,
                                   VoucherOrderMapper orderMapper,
                                   VoucherOrderDeadLetterMapper deadLetterMapper,
                                   VoucherOrderRecoveryAuditMapper auditMapper,
                                   RedisReservationTracker reservationTracker,
                                   VoucherOrderPublisher publisher,
                                   VoucherOrderRecoveryStateService recoveryStateService) {
        this.processMapper = processMapper;
        this.orderMapper = orderMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.auditMapper = auditMapper;
        this.reservationTracker = reservationTracker;
        this.publisher = publisher;
        this.recoveryStateService = recoveryStateService;
    }

    public Result queryStatus(Long orderId, Long userId) {
        VoucherOrderProcess process = processMapper.selectById(orderId);
        if (process == null) {
            return Result.fail("订单追踪记录不存在");
        }
        if (userId == null || !userId.equals(process.getUserId())) {
            return Result.fail("无权查看该订单");
        }
        VoucherOrderStatusDTO status = new VoucherOrderStatusDTO();
        status.setOrderId(process.getOrderId());
        status.setPublishStatus(process.getPublishStatus());
        status.setProcessStatus(process.getProcessStatus());
        status.setFailureReason(process.getFailureReason());
        status.setUpdateTime(process.getUpdateTime());
        return Result.ok(status);
    }

    public Result replay(Long orderId, String requestId, String reason, Long operatorId) {
        Result validation = validateRecoveryRequest(requestId, reason);
        if (validation != null) {
            return validation;
        }
        Result previous = previousRecoveryResult(requestId, orderId, "REPLAY");
        if (previous != null) {
            return previous;
        }
        VoucherOrderProcess process = processMapper.selectById(orderId);
        if (process == null) {
            return Result.fail("订单追踪记录不存在");
        }
        OrderProcessStatus status = OrderProcessStatus.valueOf(process.getProcessStatus());
        VoucherOrder persistedOrder = orderMapper.selectById(orderId);
        if (persistedOrder != null) {
            if (!sameOrder(process, persistedOrder)) {
                return Result.fail("订单身份不一致，请人工核查", orderId);
            }
            if (status == OrderProcessStatus.CANCEL_PENDING_REFUND
                    || status == OrderProcessStatus.REFUNDED) {
                return Result.fail("订单状态与落库记录不一致，请人工核查", orderId);
            }
            if (!createAudit(requestId, orderId, "REPLAY", reason, operatorId)) {
                Result duplicate = previousRecoveryResult(requestId, orderId, "REPLAY");
                return duplicate == null ? Result.fail("修复请求创建失败") : duplicate;
            }
            markPersisted(orderId);
            deadLetterMapper.markReplayedByOrderId(orderId);
            finishAudit(requestId, "SUCCESS", "订单已经落库，无需重放");
            return Result.ok(orderId);
        }
        if (!OrderProcessTransitions.canReplay(status)) {
            return Result.fail(status == OrderProcessStatus.PERSISTED
                    ? "订单已经落库，不能重放" : "当前状态不能重放");
        }
        if (!createAudit(requestId, orderId, "REPLAY", reason, operatorId)) {
            Result duplicate = previousRecoveryResult(requestId, orderId, "REPLAY");
            return duplicate == null ? Result.fail("修复请求创建失败") : duplicate;
        }
        if (processMapper.markReplayPending(orderId) != 1) {
            finishAudit(requestId, "REJECTED", "订单状态已经变化");
            return Result.fail("订单状态已经变化，请重新查询");
        }

        VoucherOrder order = toOrder(process);
        OrderPublishStatus publishStatus = publisher.publish(order);
        updatePublishStatus(orderId, publishStatus, publishStatus == OrderPublishStatus.CONFIRMED
                ? null : "MANUAL_REPLAY_" + publishStatus.name());
        if (publishStatus == OrderPublishStatus.CONFIRMED) {
            deadLetterMapper.markReplayedByOrderId(orderId);
            finishAudit(requestId, "SUCCESS", "订单消息已重新投递");
            return Result.ok(orderId);
        }
        if (publishStatus == OrderPublishStatus.FAILED) {
            markManualReview(orderId, "MANUAL_REPLAY_FAILED");
        }
        finishAudit(requestId, "FAILED", "消息投递状态：" + publishStatus.name());
        return Result.fail("订单重放未确认，请查看异常记录", orderId);
    }

    public Result refund(Long orderId, String requestId, String reason, Long operatorId) {
        Result validation = validateRecoveryRequest(requestId, reason);
        if (validation != null) {
            return validation;
        }
        Result previous = previousRecoveryResult(requestId, orderId, "REFUND");
        if (previous != null) {
            return previous;
        }
        VoucherOrderProcess process = processMapper.selectById(orderId);
        if (process == null) {
            return Result.fail("订单追踪记录不存在");
        }
        OrderProcessStatus status = OrderProcessStatus.valueOf(process.getProcessStatus());
        if (!OrderProcessTransitions.canRequestRefund(status)) {
            return Result.fail(status == OrderProcessStatus.PERSISTED
                    ? "订单已经落库，不能回补" : "当前状态不能回补");
        }
        if (orderMapper.selectById(orderId) != null) {
            return Result.fail("订单已经落库，不能回补");
        }
        if (!createAudit(requestId, orderId, "REFUND", reason, operatorId)) {
            Result duplicate = previousRecoveryResult(requestId, orderId, "REFUND");
            return duplicate == null ? Result.fail("修复请求创建失败") : duplicate;
        }
        VoucherOrderProcess preparedProcess;
        try {
            preparedProcess = recoveryStateService.prepareRefund(orderId);
        } catch (RuntimeException e) {
            log.error("Failed to prepare voucher order refund, orderId={}", orderId, e);
            finishAudit(requestId, "FAILED", "取消标记未完成");
            return Result.fail("回补准备失败，请稍后重试", orderId);
        }
        if (preparedProcess == null) {
            finishAudit(requestId, "REJECTED", "订单状态已经变化");
            return Result.fail("订单状态已经变化，请重新查询");
        }
        int refundResult;
        try {
            reservationTracker.markProcessStatus(orderId, OrderProcessStatus.CANCEL_PENDING_REFUND);
            refundResult = reservationTracker.refund(toOrder(preparedProcess));
        } catch (RuntimeException e) {
            log.error("Failed to release Redis reservation, orderId={}", orderId, e);
            finishAudit(requestId, "FAILED", "Redis 预扣尚未释放");
            return Result.fail("回补未完成，订单保留为待回补状态", orderId);
        }
        boolean markedRefunded;
        try {
            markedRefunded = refundResult >= 0 && recoveryStateService.markRefunded(orderId);
        } catch (RuntimeException e) {
            log.error("Failed to mark voucher order refund as completed, orderId={}", orderId, e);
            markedRefunded = false;
        }
        if (!markedRefunded) {
            finishAudit(requestId, "FAILED", "Redis 预扣尚未释放");
            return Result.fail("回补未完成，订单保留为待回补状态", orderId);
        }
        try {
            reservationTracker.markProcessStatus(orderId, OrderProcessStatus.REFUNDED);
            reservationTracker.removePending(orderId);
        } catch (RuntimeException e) {
            log.error("Redis reservation was refunded in the script but cleanup failed, orderId={}",
                    orderId, e);
        }
        finishAudit(requestId, "SUCCESS", "Redis 预扣已释放");
        return Result.ok(orderId);
    }

    public int reconcileBatch(LocalDateTime cutoff, int limit) {
        Map<Long, VoucherOrderProcess> processByOrderId = new HashMap<>();
        long cutoffMillis = cutoff.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Set<Long> pendingOrderIds = reservationTracker.findPendingOrderIds(cutoffMillis, limit);
        if (pendingOrderIds != null) {
            for (Long orderId : pendingOrderIds) {
                VoucherOrderProcess existingProcess = processMapper.selectById(orderId);
                if (existingProcess != null) {
                    if (OrderProcessStatus.PERSISTED.name().equals(existingProcess.getProcessStatus())
                            || OrderProcessStatus.REFUNDED.name().equals(existingProcess.getProcessStatus())) {
                        syncProcessStatusToRedis(orderId);
                    }
                    continue;
                }
                VoucherOrderProcess recovered = fromReservation(orderId,
                        reservationTracker.loadReservation(orderId));
                if (recovered != null) {
                    try {
                        processMapper.insert(recovered);
                    } catch (DuplicateKeyException ignored) {
                        // Another reconciliation worker created the trace first.
                    }
                }
            }
        }

        List<VoucherOrderProcess> processes = processMapper.findUnresolvedBefore(cutoff, limit);
        if (processes != null) {
            for (VoucherOrderProcess process : processes) {
                processByOrderId.put(process.getOrderId(), process);
            }
        }
        int reviewed = 0;
        for (VoucherOrderProcess process : processByOrderId.values()) {
            VoucherOrder order = orderMapper.selectById(process.getOrderId());
            if (order == null) {
                reviewed += markManualReview(
                        process.getOrderId(), "ORDER_NOT_PERSISTED_AFTER_TIMEOUT");
            } else if (sameOrder(process, order)) {
                reviewed += markPersisted(process.getOrderId());
            } else {
                reviewed += markManualReview(
                        process.getOrderId(), "ORDER_IDENTITY_CONFLICT");
            }
        }
        return reviewed;
    }

    public void initialize(VoucherOrder order, LocalDateTime reservedAt) {
        VoucherOrderProcess process = new VoucherOrderProcess();
        process.setOrderId(order.getId());
        process.setUserId(order.getUserId());
        process.setVoucherId(order.getVoucherId());
        process.setPublishStatus(OrderPublishStatus.PENDING.name());
        process.setProcessStatus(OrderProcessStatus.PENDING.name());
        process.setRetryCount(0);
        process.setReservedAt(reservedAt);
        processMapper.insert(process);
    }

    public void updatePublishStatus(Long orderId, OrderPublishStatus status, String reason) {
        processMapper.updatePublishStatus(orderId, status.name(), reason);
        try {
            reservationTracker.markPublishStatus(orderId, status);
        } catch (RuntimeException e) {
            log.error("Failed to synchronize publish status to Redis, orderId={}", orderId, e);
        }
    }

    public int markPersisted(Long orderId) {
        int updated = processMapper.markPersisted(orderId);
        if (updated == 1) {
            syncPersistedToRedis(orderId);
        }
        return updated;
    }

    public void syncPersistedToRedis(Long orderId) {
        try {
            reservationTracker.markProcessStatus(orderId, OrderProcessStatus.PERSISTED);
            reservationTracker.removePending(orderId);
        } catch (RuntimeException e) {
            // MySQL is the order source of truth after commit; Redis cleanup can be retried by reconciliation.
            log.error("Failed to synchronize persisted order to Redis, orderId={}", orderId, e);
        }
    }

    public void syncProcessStatusToRedis(Long orderId) {
        VoucherOrderProcess process = processMapper.selectById(orderId);
        if (process == null || process.getProcessStatus() == null) {
            return;
        }
        try {
            OrderProcessStatus status = OrderProcessStatus.valueOf(process.getProcessStatus());
            reservationTracker.markProcessStatus(orderId, status);
            if (status == OrderProcessStatus.PERSISTED || status == OrderProcessStatus.REFUNDED) {
                reservationTracker.removePending(orderId);
            }
        } catch (RuntimeException e) {
            log.error("Failed to synchronize process status to Redis, orderId={}", orderId, e);
        }
    }

    public int markManualReview(Long orderId, String reason) {
        int updated = processMapper.markManualReview(orderId, reason);
        if (updated == 1) {
            try {
                reservationTracker.markProcessStatus(orderId, OrderProcessStatus.MANUAL_REVIEW);
            } catch (RuntimeException e) {
                log.error("Failed to synchronize manual-review status to Redis, orderId={}",
                        orderId, e);
            }
        }
        return updated;
    }

    public void recordConsumerFailure(Long orderId) {
        try {
            processMapper.incrementRetryCount(orderId);
        } catch (RuntimeException e) {
            log.error("Failed to record voucher order consumer failure, orderId={}", orderId, e);
        }
    }

    public List<VoucherOrderProcess> listAnomalies(int limit) {
        return processMapper.findAnomalies(limit);
    }

    private Result previousRecoveryResult(String requestId, Long orderId, String action) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return null;
        }
        VoucherOrderRecoveryAudit audit = auditMapper.selectByRequestId(requestId);
        if (audit == null) {
            return null;
        }
        if (!Objects.equals(orderId, audit.getOrderId()) || !Objects.equals(action, audit.getAction())) {
            return Result.fail("requestId 已绑定其他修复操作");
        }
        if ("SUCCESS".equals(audit.getResult())) {
            return Result.ok(audit.getOrderId());
        }
        return Result.fail("PENDING".equals(audit.getResult()) ? "修复请求正在处理中"
                        : audit.getDetails() == null ? "修复请求已处理" : audit.getDetails(),
                audit.getOrderId());
    }

    private Result validateRecoveryRequest(String requestId, String reason) {
        if (requestId == null || requestId.trim().isEmpty()
                || reason == null || reason.trim().isEmpty()) {
            return Result.fail("requestId 和 reason 不能为空");
        }
        return null;
    }

    private boolean createAudit(String requestId, Long orderId, String action,
                                String reason, Long operatorId) {
        if (requestId == null || requestId.trim().isEmpty()
                || reason == null || reason.trim().isEmpty()) {
            return false;
        }
        VoucherOrderRecoveryAudit audit = new VoucherOrderRecoveryAudit();
        audit.setRequestId(requestId);
        audit.setOrderId(orderId);
        audit.setAction(action);
        audit.setResult("PENDING");
        audit.setReason(reason);
        audit.setOperatorId(operatorId);
        try {
            return auditMapper.insert(audit) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void finishAudit(String requestId, String result, String details) {
        auditMapper.updateResult(requestId, result, details);
    }

    private VoucherOrder toOrder(VoucherOrderProcess process) {
        VoucherOrder order = new VoucherOrder();
        order.setId(process.getOrderId());
        order.setUserId(process.getUserId());
        order.setVoucherId(process.getVoucherId());
        return order;
    }

    private boolean sameOrder(VoucherOrderProcess process, VoucherOrder order) {
        return process.getOrderId().equals(order.getId())
                && process.getUserId().equals(order.getUserId())
                && process.getVoucherId().equals(order.getVoucherId());
    }

    private VoucherOrderProcess fromReservation(Long orderId, Map<String, String> reservation) {
        if (reservation == null || reservation.isEmpty()
                || !reservation.containsKey("userId")
                || !reservation.containsKey("voucherId")) {
            return null;
        }
        try {
            VoucherOrderProcess process = new VoucherOrderProcess();
            process.setOrderId(orderId);
            process.setUserId(Long.valueOf(reservation.get("userId")));
            process.setVoucherId(Long.valueOf(reservation.get("voucherId")));
            process.setPublishStatus(valueOrDefault(reservation.get("publishStatus"),
                    OrderPublishStatus.PENDING.name()));
            process.setProcessStatus(valueOrDefault(reservation.get("processStatus"),
                    OrderProcessStatus.PENDING.name()));
            process.setRetryCount(0);
            String reservedAt = reservation.get("reservedAt");
            if (reservedAt != null) {
                long timestamp = Long.parseLong(reservedAt);
                process.setReservedAt(LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()));
            } else {
                process.setReservedAt(LocalDateTime.now());
            }
            return process;
        } catch (RuntimeException e) {
            log.warn("Unable to recover Redis reservation trace, orderId={}", orderId, e);
            return null;
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }
}
