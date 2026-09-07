package com.hmdp.job;

import com.hmdp.config.OrderReliabilityProperties;
import com.hmdp.config.QueueConfig;
import com.hmdp.service.DeadLetterArchiveService;
import com.rabbitmq.client.GetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "hmdp.order-reliability.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterArchiveJob {

    private final RabbitTemplate rabbitTemplate;
    private final DeadLetterArchiveService archiveService;
    private final OrderReliabilityProperties properties;

    @Scheduled(fixedDelayString = "${hmdp.order-reliability.dead-letter-fixed-delay-ms:5000}")
    public void archive() {
        try {
            Integer archived = rabbitTemplate.execute(channel -> {
                int count = 0;
                while (count < properties.getDeadLetterBatchSize()) {
                    GetResponse response = channel.basicGet(QueueConfig.ORDER_DEAD_LETTER_QUEUE, false);
                    if (response == null) {
                        break;
                    }
                    long deliveryTag = response.getEnvelope().getDeliveryTag();
                    try {
                        archiveService.archive(response);
                        channel.basicAck(deliveryTag, false);
                        count++;
                    } catch (RuntimeException e) {
                        channel.basicNack(deliveryTag, false, true);
                        throw e;
                    }
                }
                return count;
            });
            if (archived != null && archived > 0) {
                log.info("Voucher order dead letters archived, count={}", archived);
            }
        } catch (RuntimeException e) {
            log.error("Voucher order dead-letter archive failed; messages remain in the queue", e);
        }
    }
}
