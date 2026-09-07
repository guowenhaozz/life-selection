package com.hmdp.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderDeadLetter;
import com.hmdp.mapper.VoucherOrderDeadLetterMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.reliability.OrderProcessStatus;
import com.hmdp.reliability.RedisReservationTracker;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DeadLetterArchiveService {

    private final VoucherOrderDeadLetterMapper deadLetterMapper;
    private final VoucherOrderProcessMapper processMapper;
    private final RedisReservationTracker reservationTracker;

    public DeadLetterArchiveService(VoucherOrderDeadLetterMapper deadLetterMapper,
                                    VoucherOrderProcessMapper processMapper,
                                    RedisReservationTracker reservationTracker) {
        this.deadLetterMapper = deadLetterMapper;
        this.processMapper = processMapper;
        this.reservationTracker = reservationTracker;
    }

    public void archive(GetResponse response) {
        String payload = new String(response.getBody(), StandardCharsets.UTF_8);
        AMQP.BasicProperties properties = response.getProps();
        Map<String, Object> headers = properties == null ? null : properties.getHeaders();
        String deathReason = deathReason(headers);
        String messageId = properties == null ? null : properties.getMessageId();
        if (StrUtil.isBlank(messageId)) {
            messageId = SecureUtil.md5(payload + "|" + deathReason);
        }
        VoucherOrderDeadLetter existingDeadLetter = deadLetterMapper.selectByMessageId(messageId);
        if (existingDeadLetter != null) {
            markManualReview(existingDeadLetter.getOrderId(), deathReason);
            return;
        }

        VoucherOrder order = parseOrder(payload);
        VoucherOrderDeadLetter deadLetter = new VoucherOrderDeadLetter();
        deadLetter.setMessageId(messageId);
        deadLetter.setOrderId(order == null ? null : order.getId());
        deadLetter.setPayload(payload);
        deadLetter.setDeathReason(deathReason);
        deadLetter.setRetryCount(retryCount(headers));
        deadLetter.setStatus("ARCHIVED");
        try {
            deadLetterMapper.insert(deadLetter);
        } catch (DuplicateKeyException ignored) {
            existingDeadLetter = deadLetterMapper.selectByMessageId(messageId);
            if (existingDeadLetter != null) {
                markManualReview(existingDeadLetter.getOrderId(), deathReason);
            }
            return;
        }
        markManualReview(order == null ? null : order.getId(), deathReason);
    }

    private void markManualReview(Long orderId, String deathReason) {
        if (orderId == null) {
            return;
        }
        int updated = processMapper.markManualReview(orderId, "DEAD_LETTER_" + deathReason);
        if (updated == 1) {
            try {
                reservationTracker.markProcessStatus(orderId, OrderProcessStatus.MANUAL_REVIEW);
            } catch (RuntimeException e) {
                log.error("Failed to synchronize dead-letter status to Redis, orderId={}",
                        orderId, e);
            }
        }
    }

    private VoucherOrder parseOrder(String payload) {
        try {
            return JSONUtil.toBean(payload, VoucherOrder.class);
        } catch (RuntimeException e) {
            log.warn("Dead-letter payload is not a voucher order JSON: {}", payload);
            return null;
        }
    }

    private String deathReason(Map<String, Object> headers) {
        if (headers == null) {
            return "UNKNOWN";
        }
        Object firstReason = headers.get("x-first-death-reason");
        if (firstReason != null) {
            return headerText(firstReason);
        }
        Object deaths = headers.get("x-death");
        if (deaths instanceof List && !((List<?>) deaths).isEmpty()) {
            Object first = ((List<?>) deaths).get(0);
            if (first instanceof Map) {
                Object reason = ((Map<?, ?>) first).get("reason");
                if (reason != null) {
                    return headerText(reason);
                }
            }
        }
        return "UNKNOWN";
    }

    private int retryCount(Map<String, Object> headers) {
        if (headers == null) {
            return 0;
        }
        Object deaths = headers.get("x-death");
        if (deaths instanceof List && !((List<?>) deaths).isEmpty()) {
            Object first = ((List<?>) deaths).get(0);
            if (first instanceof Map) {
                Object count = ((Map<?, ?>) first).get("count");
                if (count instanceof Number) {
                    return ((Number) count).intValue();
                }
            }
        }
        return 0;
    }

    private String headerText(Object value) {
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}
