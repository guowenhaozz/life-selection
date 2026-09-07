package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderProcess;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.reliability.OrderProcessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherOrderRecoveryStateService {

    private final VoucherOrderProcessMapper processMapper;
    private final VoucherOrderMapper orderMapper;

    public VoucherOrderRecoveryStateService(VoucherOrderProcessMapper processMapper,
                                            VoucherOrderMapper orderMapper) {
        this.processMapper = processMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * Commits the cancellation marker before Redis is touched, so a late consumer
     * observes the marker and cannot create the order.
     */
    @Transactional(rollbackFor = Exception.class)
    public VoucherOrderProcess prepareRefund(Long orderId) {
        VoucherOrderProcess process = processMapper.selectForUpdate(orderId);
        if (process == null || process.getProcessStatus() == null) {
            return null;
        }
        OrderProcessStatus status;
        try {
            status = OrderProcessStatus.valueOf(process.getProcessStatus());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (status == OrderProcessStatus.PERSISTED || status == OrderProcessStatus.REFUNDED) {
            return null;
        }
        VoucherOrder existingOrder = orderMapper.selectById(orderId);
        if (existingOrder != null) {
            return null;
        }
        if (status == OrderProcessStatus.PENDING || status == OrderProcessStatus.MANUAL_REVIEW) {
            if (processMapper.markCancelPending(orderId) != 1) {
                return null;
            }
            process.setProcessStatus(OrderProcessStatus.CANCEL_PENDING_REFUND.name());
        } else if (status != OrderProcessStatus.CANCEL_PENDING_REFUND) {
            return null;
        }
        return process;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markRefunded(Long orderId) {
        if (processMapper.markRefunded(orderId) == 1) {
            return true;
        }
        VoucherOrderProcess process = processMapper.selectById(orderId);
        return process != null
                && OrderProcessStatus.REFUNDED.name().equals(process.getProcessStatus());
    }
}
