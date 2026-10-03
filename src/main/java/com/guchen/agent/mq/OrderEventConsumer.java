package com.guchen.agent.mq;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * 订单事件消费者：异步触发 Agent 生成「茶品搭配建议」。
 *
 * 三个工程要点（都能写进简历）：
 *  1. **手动 ack**：业务处理成功才 ack，异常走 nack 进死信队列，消息不丢；
 *  2. **幂等**：以 eventId 为键在 Redis 做 setIfAbsent，重复投递直接跳过
 *     （MQ 至少一次语义 + 消费者重试，必然会有重复投递）；
 *  3. **限流**：LLM 调用贵且慢，prefetch=10 控制单消费者并发，避免打爆模型配额。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final StringRedisTemplate redis;

    private static final String IDEMPOTENT_KEY_PREFIX = "ms:agent:event:";
    private static final Duration IDEMPOTENT_TTL = Duration.ofDays(7);

    @RabbitListener(queues = RabbitMqConfig.QUEUE_ORDER_PAID, ackMode = "MANUAL")
    public void onOrderPaid(Map<String, Object> payload, Message message, Channel channel)
            throws IOException {

        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = String.valueOf(payload.get("eventId"));

        try {
            // ---------- 幂等校验 ----------
            Boolean first = redis.opsForValue()
                    .setIfAbsent(IDEMPOTENT_KEY_PREFIX + eventId, "1", IDEMPOTENT_TTL);
            if (Boolean.FALSE.equals(first)) {
                log.info("事件已处理过，跳过 eventId={}", eventId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ---------- 业务处理 ----------
            Long orderId = Long.valueOf(String.valueOf(payload.get("orderId")));
            Long userId = Long.valueOf(String.valueOf(payload.get("userId")));
            String productNames = String.valueOf(payload.get("productNames"));

            log.info("开始处理订单支付事件 orderId={}, userId={}, products={}",
                    orderId, userId, productNames);

            // TODO 调用 AgentOrchestrator 生成搭配建议，写入用户画像 / 推送站内信
            // 这一步耗时较长（LLM 调用），所以放在 MQ 消费线程里，不影响下单主链路

            channel.basicAck(deliveryTag, false);
            log.info("订单事件处理完成 orderId={}", orderId);

        } catch (Exception e) {
            log.error("订单事件处理失败，转入死信队列 eventId={}", eventId, e);
            // requeue=false → 进死信队列，避免无限重投
            channel.basicNack(deliveryTag, false, false);
            // 同时清掉幂等键，便于人工重放
            redis.delete(IDEMPOTENT_KEY_PREFIX + eventId);
        }
    }

    /** 死信队列监控：人工介入入口。 */
    @RabbitListener(queues = RabbitMqConfig.QUEUE_DLQ)
    public void onDeadLetter(Map<String, Object> payload) {
        log.error("【死信告警】事件处理失败，需人工介入: {}", payload);
        // 可对接告警（钉钉/企微机器人）
    }
}
