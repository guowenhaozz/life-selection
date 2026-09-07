package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderProcess;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.service.VoucherOrderPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderPersistenceServiceTest {

    @Mock
    private VoucherOrderProcessMapper processMapper;
    @Mock
    private VoucherOrderMapper orderMapper;
    @Mock
    private SeckillVoucherMapper seckillVoucherMapper;

    private VoucherOrderPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderPersistenceService(processMapper, orderMapper, seckillVoucherMapper);
    }

    @Test
    void lateConsumerDoesNotPersistCancelledReservation() {
        VoucherOrder order = order(101L, 7L, 2L);
        when(processMapper.selectForUpdate(101L))
                .thenReturn(process(order, OrderProcessStatus.CANCEL_PENDING_REFUND));

        assertFalse(service.persist(order));
    }

    @Test
    void redeliveryOfSamePersistedOrderIsIdempotent() {
        VoucherOrder order = order(101L, 7L, 2L);
        when(processMapper.selectForUpdate(101L))
                .thenReturn(process(order, OrderProcessStatus.PENDING));
        when(orderMapper.selectById(101L)).thenReturn(order);
        when(processMapper.markPersisted(101L)).thenReturn(1);

        assertTrue(service.persist(order));
    }

    @Test
    void conflictingOrderIdentityIsSentToManualReview() {
        VoucherOrder messageOrder = order(101L, 7L, 2L);
        VoucherOrder existingOrder = order(202L, 7L, 2L);
        when(processMapper.selectForUpdate(101L))
                .thenReturn(process(messageOrder, OrderProcessStatus.PENDING));
        when(orderMapper.selectById(101L)).thenReturn(null);
        when(orderMapper.selectByUserAndVoucher(7L, 2L)).thenReturn(existingOrder);
        when(processMapper.markManualReview(101L, "USER_VOUCHER_ORDER_CONFLICT"))
                .thenReturn(1);

        assertFalse(service.persist(messageOrder));
    }

    @Test
    void databaseStockFailureRollsBackOrderTransaction() {
        VoucherOrder order = order(101L, 7L, 2L);
        when(processMapper.selectForUpdate(101L))
                .thenReturn(process(order, OrderProcessStatus.PENDING));
        when(orderMapper.selectById(101L)).thenReturn(null);
        when(orderMapper.selectByUserAndVoucher(7L, 2L)).thenReturn(null);
        when(seckillVoucherMapper.decrementStock(2L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.persist(order));
    }

    private VoucherOrder order(Long orderId, Long userId, Long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        return order;
    }

    private VoucherOrderProcess process(VoucherOrder order, OrderProcessStatus status) {
        VoucherOrderProcess process = new VoucherOrderProcess();
        process.setOrderId(order.getId());
        process.setUserId(order.getUserId());
        process.setVoucherId(order.getVoucherId());
        process.setPublishStatus(OrderPublishStatus.CONFIRMED.name());
        process.setProcessStatus(status.name());
        return process;
    }
}
