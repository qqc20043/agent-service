package com.guchen.agent.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置 —— 订单事件驱动的 Agent 异步链路。
 *
 * 业务场景（真实有价值，不是为用而用）：
 *   用户下单后，同步链路只需返回「下单成功」；
 *   而「生成茶品搭配建议 / 更新用户口味画像 / 发优惠券」这些耗时的 LLM 任务
 *   通过 MQ 异步触发，既削峰又不拖慢下单响应。
 *
 * 可靠性设计：
 *   - 交换机/队列/死信队列都声明为 durable；
 *   - 消费端手动 ack，失败进死信队列，避免消息丢失；
 *   - 消费端幂等：以 orderId 为幂等键，重复消费直接跳过（复用老项目 FOR UPDATE 的思路）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitMqConfig {

    public static final String EXCHANGE_ORDER = "ms.order.exchange";
    public static final String QUEUE_ORDER_PAID = "ms.order.paid.queue";
    public static final String ROUTING_ORDER_PAID = "order.paid";

    public static final String EXCHANGE_DLX = "ms.dlx.exchange";
    public static final String QUEUE_DLQ = "ms.order.dlq";
    public static final String ROUTING_DLQ = "order.dlq";

    private final ConnectionFactory connectionFactory;

    // ==================== 主链路 ====================

    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_ORDER).durable(true).build();
    }

    @Bean
    public Queue orderPaidQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_PAID)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", ROUTING_DLQ)
                .build();
    }

    @Bean
    public Binding orderPaidBinding() {
        return BindingBuilder.bind(orderPaidQueue())
                .to(orderExchange())
                .with(ROUTING_ORDER_PAID);
    }

    // ==================== 死信链路 ====================

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_DLX).durable(true).build();
    }

    @Bean
    public Queue orderDlq() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(orderDlq()).to(dlxExchange()).with(ROUTING_DLQ);
    }

    // ==================== 序列化与模板 ====================

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate() {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        // 开启 mandatory，路由失败可感知
        template.setMandatory(true);
        template.setReturnsCallback(returned ->
                log.error("消息路由失败: exchange={}, routingKey={}, reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
