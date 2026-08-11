package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    private final IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = QueueConfig.ORDER_QUEUE)
    public void handleVoucherOrderMessage(String message) {
        VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
            log.info("Voucher order persisted, orderId={}", voucherOrder.getId());
        } catch (DuplicateKeyException e) {
            // Re-delivered messages are acknowledged after the transaction rolls back.
            log.warn("Duplicate voucher order ignored, orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
        }
    }
}
