package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderProcess;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.service.VoucherOrderRecoveryStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderRecoveryStateServiceTest {

    @Mock
    private VoucherOrderProcessMapper processMapper;
    @Mock
    private VoucherOrderMapper orderMapper;

    private VoucherOrderRecoveryStateService service;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderRecoveryStateService(processMapper, orderMapper);
    }

    @Test
    void prepareRefundLocksProcessAndMarksCancellation() {
        VoucherOrderProcess process = process(101L, OrderProcessStatus.MANUAL_REVIEW);
        when(processMapper.selectForUpdate(101L)).thenReturn(process);
        when(orderMapper.selectById(101L)).thenReturn(null);
        when(processMapper.markCancelPending(101L)).thenReturn(1);

        VoucherOrderProcess prepared = service.prepareRefund(101L);

        assertEquals(OrderProcessStatus.CANCEL_PENDING_REFUND.name(), prepared.getProcessStatus());
        verify(processMapper).selectForUpdate(101L);
        verify(processMapper).markCancelPending(101L);
    }

    @Test
    void alreadyCancelledProcessCanRetryWithoutRewritingMarker() {
        VoucherOrderProcess process = process(101L, OrderProcessStatus.CANCEL_PENDING_REFUND);
        when(processMapper.selectForUpdate(101L)).thenReturn(process);
        when(orderMapper.selectById(101L)).thenReturn(null);

        VoucherOrderProcess prepared = service.prepareRefund(101L);

        assertEquals(OrderProcessStatus.CANCEL_PENDING_REFUND.name(), prepared.getProcessStatus());
        verify(processMapper, never()).markCancelPending(101L);
    }

    @Test
    void persistedProcessCannotBeRefunded() {
        VoucherOrderProcess process = process(101L, OrderProcessStatus.PERSISTED);
        when(processMapper.selectForUpdate(101L)).thenReturn(process);

        assertNull(service.prepareRefund(101L));
    }

    private VoucherOrderProcess process(Long orderId, OrderProcessStatus status) {
        VoucherOrderProcess process = new VoucherOrderProcess();
        process.setOrderId(orderId);
        process.setUserId(7L);
        process.setVoucherId(2L);
        process.setProcessStatus(status.name());
        return process;
    }
}
