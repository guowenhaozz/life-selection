package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.QueueConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final long PUBLISH_CONFIRM_TIMEOUT_SECONDS = 3L;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
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

        PublishStatus publishStatus = publishOrder(order);
        if (publishStatus == PublishStatus.CONFIRMED) {
            return Result.ok(orderId);
        }
        if (publishStatus == PublishStatus.FAILED) {
            rollbackRedisReservation(voucherId, userId);
            return Result.fail("订单提交失败，请重试");
        }

        // A confirm timeout is an unknown state. Rolling back could cause overselling
        // when the broker has actually received the message.
        return Result.fail("订单正在确认中，请稍后查询");
    }

    private PublishStatus publishOrder(VoucherOrder order) {
        CorrelationData correlationData = new CorrelationData(order.getId().toString());
        try {
            rabbitTemplate.convertAndSend(
                    QueueConfig.ORDER_EXCHANGE,
                    QueueConfig.ORDER_ROUTING_KEY,
                    JSONUtil.toJsonStr(order),
                    message -> {
                        MessageProperties properties = message.getMessageProperties();
                        properties.setMessageId(order.getId().toString());
                        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                        properties.setContentEncoding("UTF-8");
                        return message;
                    },
                    correlationData
            );

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(PUBLISH_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                log.error("RabbitMQ rejected voucher order, orderId={}, reason={}",
                        order.getId(), confirm.getReason());
                return PublishStatus.FAILED;
            }
            if (correlationData.getReturnedMessage() != null) {
                log.error("RabbitMQ returned unroutable voucher order, orderId={}", order.getId());
                return PublishStatus.FAILED;
            }
            return PublishStatus.CONFIRMED;
        } catch (AmqpException e) {
            log.error("Failed to publish voucher order, orderId={}", order.getId(), e);
            return PublishStatus.FAILED;
        } catch (TimeoutException | ExecutionException e) {
            log.error("Voucher order publish confirmation is unknown, orderId={}", order.getId(), e);
            return PublishStatus.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for voucher order confirmation, orderId={}",
                    order.getId(), e);
            return PublishStatus.UNKNOWN;
        }
    }

    private void rollbackRedisReservation(Long voucherId, Long userId) {
        try {
            Long result = stringRedisTemplate.execute(
                    SECKILL_ROLLBACK_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString()
            );
            log.warn("Redis voucher reservation rolled back, voucherId={}, userId={}, result={}",
                    voucherId, userId, result);
        } catch (RuntimeException e) {
            log.error("Failed to roll back Redis voucher reservation, voucherId={}, userId={}",
                    voucherId, userId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        int existingOrders = query()
                .eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (existingOrders > 0) {
            log.info("Voucher order already exists, userId={}, voucherId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId());
            return;
        }

        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            throw new IllegalStateException("Database voucher stock is insufficient");
        }

        if (!save(voucherOrder)) {
            throw new IllegalStateException("Failed to persist voucher order");
        }
    }

    private enum PublishStatus {
        CONFIRMED,
        FAILED,
        UNKNOWN
    }
}
