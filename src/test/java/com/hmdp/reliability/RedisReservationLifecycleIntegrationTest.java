package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisReservationLifecycleIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private DefaultRedisReservationTracker tracker;
    private String voucherId;
    private String userId;
    private String orderId;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        tracker = new DefaultRedisReservationTracker(redisTemplate);

        long suffix = System.currentTimeMillis();
        voucherId = "990" + suffix;
        userId = "880" + suffix;
        orderId = "770" + suffix;
        redisTemplate.opsForValue().set("seckill:stock:" + voucherId, "1");
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete("seckill:stock:" + voucherId);
        redisTemplate.delete("seckill:order:" + voucherId);
        redisTemplate.delete(DefaultRedisReservationTracker.RESERVATION_KEY_PREFIX + orderId);
        redisTemplate.opsForZSet().remove(DefaultRedisReservationTracker.PENDING_KEY, orderId);
        connectionFactory.destroy();
    }

    @Test
    void reservationIsTracedAtomicallyAndRefundIsIdempotent() {
        DefaultRedisScript<Long> seckillScript = new DefaultRedisScript<>();
        seckillScript.setLocation(new ClassPathResource("seckill.lua"));
        seckillScript.setResultType(Long.class);

        Long result = redisTemplate.execute(seckillScript, Collections.emptyList(),
                voucherId, userId, orderId, String.valueOf(System.currentTimeMillis()));

        assertEquals(0L, result);
        String reservationKey = DefaultRedisReservationTracker.RESERVATION_KEY_PREFIX + orderId;
        assertEquals(userId, redisTemplate.opsForHash().get(reservationKey, "userId"));
        assertEquals(voucherId, redisTemplate.opsForHash().get(reservationKey, "voucherId"));
        assertEquals(OrderProcessStatus.PENDING.name(),
                redisTemplate.opsForHash().get(reservationKey, "processStatus"));

        VoucherOrder order = new VoucherOrder();
        order.setId(Long.valueOf(orderId));
        order.setUserId(Long.valueOf(userId));
        order.setVoucherId(Long.valueOf(voucherId));

        assertEquals(1, tracker.refund(order));
        assertEquals(0, tracker.refund(order));
        assertEquals("1", redisTemplate.opsForValue().get("seckill:stock:" + voucherId));

        redisTemplate.delete(reservationKey);
        assertEquals(-3, tracker.refund(order));
    }
}
