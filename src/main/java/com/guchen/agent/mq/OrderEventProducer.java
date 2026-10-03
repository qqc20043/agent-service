package com.guchen.agent.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 订单事件生产者：老项目下单成功后投递事件，Agent 侧异步消费。
 *
 * 调用方式（老项目 OrderServiceImpl.insertOrderData 末尾加一行即可）：
 *   // 老项目里通过 HTTP 或直接 MQ 通知 Agent
 *   // 最简做法：老项目也用 spring-boot-starter-amqp 发同一条消息
 *   rabbitTemplate.convertAndSend("ms.order.exchange", "order.paid", payload);
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private final RabbitTemplate rabbitTemplate;

    public void publishOrderPaid(Long orderId, Long userId, String productNames, Double payAmount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", "ORDER_PAID");
        payload.put("orderId", orderId);
        payload.put("userId", userId);
        payload.put("productNames", productNames);
        payload.put("payAmount", payAmount);
        payload.put("occurredAt", LocalDateTime.now().toString());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE_ORDER,
                RabbitMqConfig.ROUTING_ORDER_PAID,
                payload);

        log.info("已投递订单支付事件 orderId={}", orderId);
    }
}
