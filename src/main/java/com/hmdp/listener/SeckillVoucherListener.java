package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.OrderReliabilityService;
import com.hmdp.service.VoucherOrderPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    private final VoucherOrderPersistenceService persistenceService;
    private final OrderReliabilityService orderReliabilityService;

    @RabbitListener(queues = QueueConfig.ORDER_QUEUE)
    public void handleVoucherOrderMessage(String message) {
        VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
        try {
            boolean persisted = persistenceService.persist(voucherOrder);
            if (persisted) {
                orderReliabilityService.syncPersistedToRedis(voucherOrder.getId());
                log.info("Voucher order persisted, orderId={}", voucherOrder.getId());
            } else {
                orderReliabilityService.syncProcessStatusToRedis(voucherOrder.getId());
                log.warn("Voucher order was not persisted because it was cancelled or requires review, " +
                        "orderId={}", voucherOrder.getId());
            }
        } catch (RuntimeException e) {
            orderReliabilityService.recordConsumerFailure(voucherOrder.getId());
            throw e;
        }
    }
}
