package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

@Component
public class DefaultRedisReservationTracker implements RedisReservationTracker {

    public static final String RESERVATION_KEY_PREFIX = "seckill:reservation:v1:";
    public static final String PENDING_KEY = "seckill:reservation:pending:v1";

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    public DefaultRedisReservationTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void markPublishStatus(Long orderId, OrderPublishStatus status) {
        if (!hasReservation(orderId)) {
            return;
        }
        redisTemplate.opsForHash().put(key(orderId), "publishStatus", status.name());
        redisTemplate.opsForHash().put(key(orderId), "updatedAt", String.valueOf(System.currentTimeMillis()));
    }

    @Override
    public void markProcessStatus(Long orderId, OrderProcessStatus status) {
        if (!hasReservation(orderId)) {
            return;
        }
        redisTemplate.opsForHash().put(key(orderId), "processStatus", status.name());
        redisTemplate.opsForHash().put(key(orderId), "updatedAt", String.valueOf(System.currentTimeMillis()));
    }

    @Override
    public int refund(VoucherOrder order) {
        Long result = redisTemplate.execute(
                ROLLBACK_SCRIPT,
                Collections.emptyList(),
                order.getVoucherId().toString(),
                order.getUserId().toString(),
                order.getId().toString(),
                String.valueOf(System.currentTimeMillis())
        );
        return result == null ? -1 : result.intValue();
    }

    @Override
    public void removePending(Long orderId) {
        redisTemplate.opsForZSet().remove(PENDING_KEY, orderId.toString());
    }

    @Override
    public Set<Long> findPendingOrderIds(long cutoffMillis, int limit) {
        Set<String> values = redisTemplate.opsForZSet()
                .rangeByScore(PENDING_KEY, 0, cutoffMillis, 0, limit);
        Set<Long> orderIds = new LinkedHashSet<>();
        if (values == null) {
            return orderIds;
        }
        for (String value : values) {
            try {
                orderIds.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // A malformed index member is ignored and left for manual inspection.
            }
        }
        return orderIds;
    }

    @Override
    public Map<String, String> loadReservation(Long orderId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(orderId));
        Map<String, String> reservation = new HashMap<>();
        if (values == null) {
            return reservation;
        }
        for (Map.Entry<Object, Object> entry : values.entrySet()) {
            reservation.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return reservation;
    }

    private String key(Long orderId) {
        return RESERVATION_KEY_PREFIX + orderId;
    }

    private boolean hasReservation(Long orderId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(orderId)));
    }
}
