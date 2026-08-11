package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueueConfig {

    public static final String ORDER_EXCHANGE = "hmdp.voucher.order.exchange";
    public static final String ORDER_ROUTING_KEY = "voucher.order.created";
    public static final String ORDER_QUEUE = "hmdp.voucher.order.queue";

    public static final String ORDER_DEAD_LETTER_EXCHANGE = "hmdp.voucher.order.dlx";
    public static final String ORDER_DEAD_LETTER_ROUTING_KEY = "voucher.order.dead";
    public static final String ORDER_DEAD_LETTER_QUEUE = "hmdp.voucher.order.dlq";

    @Bean
    public DirectExchange voucherOrderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange voucherOrderDeadLetterExchange() {
        return new DirectExchange(ORDER_DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue voucherOrderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .deadLetterExchange(ORDER_DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue voucherOrderDeadLetterQueue() {
        return QueueBuilder.durable(ORDER_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding voucherOrderBinding(
            @Qualifier("voucherOrderQueue") Queue queue,
            @Qualifier("voucherOrderExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding voucherOrderDeadLetterBinding(
            @Qualifier("voucherOrderDeadLetterQueue") Queue queue,
            @Qualifier("voucherOrderDeadLetterExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ORDER_DEAD_LETTER_ROUTING_KEY);
    }
}
