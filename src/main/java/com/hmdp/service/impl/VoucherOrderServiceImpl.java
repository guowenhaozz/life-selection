package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.reliability.OrderPublishStatus;
import com.hmdp.reliability.RedisReservationTracker;
import com.hmdp.reliability.VoucherOrderPublisher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderReliabilityService;
import com.hmdp.service.VoucherOrderPersistenceService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisReservationTracker reservationTracker;

    @Resource
    private VoucherOrderPublisher orderPublisher;

    @Resource
    private OrderReliabilityService orderReliabilityService;

    @Resource
    private VoucherOrderPersistenceService persistenceService;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        long reservedAt = System.currentTimeMillis();

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId),
                String.valueOf(reservedAt)
        );
        if (result == null) {
            return Result.fail("秒杀服务繁忙，请稍后重试");
        }
        if (result == -1L) {
            return Result.fail("秒杀库存未初始化");
        }
        if (result == 1L) {
            return Result.fail("库存不足");
        }
        if (result == 2L) {
            return Result.fail("不能重复下单");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);

        try {
            orderReliabilityService.initialize(order, toDateTime(reservedAt));
        } catch (RuntimeException e) {
            log.error("Failed to initialize voucher order process, orderId={}", orderId, e);
            releaseReservation(order);
            return Result.fail("订单服务繁忙，请稍后重试");
        }

        OrderPublishStatus publishStatus = orderPublisher.publish(order);
        orderReliabilityService.updatePublishStatus(orderId, publishStatus,
                publishStatus == OrderPublishStatus.CONFIRMED ? null : "PUBLISH_" + publishStatus.name());
        if (publishStatus == OrderPublishStatus.CONFIRMED) {
            return Result.ok(orderId);
        }
        if (publishStatus == OrderPublishStatus.FAILED) {
            Result refundResult = orderReliabilityService.refund(
                    orderId, "auto-publish-failure-" + orderId,
                    "RabbitMQ 明确拒绝或路由失败", null);
            if (!refundResult.getSuccess()) {
                log.error("Known publish failure was not refunded, orderId={}, result={}",
                        orderId, refundResult);
            }
            return Result.fail("订单提交失败，请重试");
        }

        // A confirm timeout is an unknown state. Rolling back could cause overselling
        // when the broker has actually received the message.
        return Result.fail("订单正在确认中，请稍后查询", orderId);
    }

    @Override
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        return persistenceService.persist(voucherOrder);
    }

    private LocalDateTime toDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis),
                ZoneId.systemDefault());
    }

    private void releaseReservation(VoucherOrder order) {
        try {
            int result = reservationTracker.refund(order);
            log.warn("Redis voucher reservation released after process initialization failure, " +
                    "orderId={}, result={}", order.getId(), result);
        } catch (RuntimeException e) {
            log.error("Failed to release Redis voucher reservation, orderId={}", order.getId(), e);
        }
    }
}
