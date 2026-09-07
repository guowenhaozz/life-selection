package com.hmdp.reliability;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class RabbitVoucherOrderPublisher implements VoucherOrderPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 3L;

    private final RabbitTemplate rabbitTemplate;

    public RabbitVoucherOrderPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public OrderPublishStatus publish(VoucherOrder order) {
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
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            boolean returned = correlationData.getReturnedMessage() != null;
            OrderPublishStatus status = PublishOutcomeClassifier.classify(confirm.isAck(), returned);
            if (status == OrderPublishStatus.FAILED) {
                log.error("RabbitMQ rejected or returned voucher order, orderId={}, reason={}",
                        order.getId(), confirm.getReason());
            }
            return status;
        } catch (AmqpException e) {
            log.error("Failed to publish voucher order, orderId={}", order.getId(), e);
            return OrderPublishStatus.FAILED;
        } catch (TimeoutException | ExecutionException e) {
            log.error("Voucher order publish confirmation is unknown, orderId={}", order.getId(), e);
            return OrderPublishStatus.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for voucher order confirmation, orderId={}", order.getId(), e);
            return OrderPublishStatus.UNKNOWN;
        }
    }
}
