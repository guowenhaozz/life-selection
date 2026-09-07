package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderProcess;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.reliability.OrderProcessStatus;
import com.hmdp.reliability.OrderProcessTransitions;
import com.hmdp.reliability.OrderPublishStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class VoucherOrderPersistenceService {

    private final VoucherOrderProcessMapper processMapper;
    private final VoucherOrderMapper orderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;

    public VoucherOrderPersistenceService(VoucherOrderProcessMapper processMapper,
                                          VoucherOrderMapper orderMapper,
                                          SeckillVoucherMapper seckillVoucherMapper) {
        this.processMapper = processMapper;
        this.orderMapper = orderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean persist(VoucherOrder order) {
        VoucherOrderProcess process = processMapper.selectForUpdate(order.getId());
        if (process == null) {
            createLegacyProcess(order);
            process = processMapper.selectForUpdate(order.getId());
        }
        if (process == null || !sameIdentity(process, order)) {
            throw new IllegalStateException("Order process identity is missing or inconsistent");
        }

        OrderProcessStatus status = parseStatus(process);
        if (!OrderProcessTransitions.canPersist(status)) {
            return status == OrderProcessStatus.PERSISTED;
        }

        VoucherOrder existingById = orderMapper.selectById(order.getId());
        if (existingById != null) {
            if (!sameIdentity(existingById, order)) {
                processMapper.markManualReview(order.getId(), "ORDER_IDENTITY_CONFLICT");
                return false;
            }
            requirePersistedMarker(order.getId());
            return true;
        }

        VoucherOrder existingByUser = orderMapper.selectByUserAndVoucher(
                order.getUserId(), order.getVoucherId());
        if (existingByUser != null) {
            processMapper.markManualReview(order.getId(), "USER_VOUCHER_ORDER_CONFLICT");
            return false;
        }

        if (seckillVoucherMapper.decrementStock(order.getVoucherId()) != 1) {
            throw new IllegalStateException("Database voucher stock is insufficient");
        }
        if (orderMapper.insert(order) != 1) {
            throw new IllegalStateException("Failed to persist voucher order");
        }
        requirePersistedMarker(order.getId());
        return true;
    }

    private void createLegacyProcess(VoucherOrder order) {
        VoucherOrderProcess process = new VoucherOrderProcess();
        process.setOrderId(order.getId());
        process.setUserId(order.getUserId());
        process.setVoucherId(order.getVoucherId());
        process.setPublishStatus(OrderPublishStatus.UNKNOWN.name());
        process.setProcessStatus(OrderProcessStatus.PENDING.name());
        process.setRetryCount(0);
        process.setReservedAt(LocalDateTime.now());
        processMapper.insert(process);
    }

    private void requirePersistedMarker(Long orderId) {
        if (processMapper.markPersisted(orderId) != 1) {
            throw new IllegalStateException("Failed to mark voucher order as persisted");
        }
    }

    private OrderProcessStatus parseStatus(VoucherOrderProcess process) {
        try {
            return OrderProcessStatus.valueOf(process.getProcessStatus());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unknown order process status: "
                    + process.getProcessStatus(), e);
        }
    }

    private boolean sameIdentity(VoucherOrderProcess process, VoucherOrder order) {
        return process.getOrderId().equals(order.getId())
                && process.getUserId().equals(order.getUserId())
                && process.getVoucherId().equals(order.getVoucherId());
    }

    private boolean sameIdentity(VoucherOrder left, VoucherOrder right) {
        return left.getId().equals(right.getId())
                && left.getUserId().equals(right.getUserId())
                && left.getVoucherId().equals(right.getVoucherId());
    }
}
