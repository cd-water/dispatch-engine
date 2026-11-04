package com.ridematching.notification.adapters.in.kafka;

import com.ridematching.events.RideMatched;
import com.ridematching.platform.messaging.ProcessedEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

/**
 * 通知乘客司机正在赶来。
 *
 * <p>与所有其他服务使用不同的消费者组，这正是让扇出相互独立的原因：停止、重新部署或崩溃
 * 这个服务，都不会影响匹配延迟或其他消费者。负载测试通过在运行中途杀掉它来证明这一点。
 *
 * <p>是模拟而非真实——这里没有推送网关或支付处理器，假装有才是不诚实的。真实存在的是解耦、
 * 消费者组语义和去重。
 */
public class RideMatchedNotifier {

    private static final Logger log = LoggerFactory.getLogger(RideMatchedNotifier.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;
    private final Counter handled;
    private final Counter duplicates;

    public RideMatchedNotifier(ObjectMapper objectMapper, ProcessedEvents processed, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.handled = Counter.builder("notifications.handled")
                .description("RideMatched events acted on").register(meters);
        this.duplicates = Counter.builder("notifications.duplicates")
                .description("Redeliveries suppressed by de-duplication").register(meters);
    }

    @KafkaListener(topics = "${ridematching.topics.ride-matched:ride.matched.v1}",
            groupId = "notification-service")
    public void onRideMatched(String payload) {
        RideMatched event = objectMapper.readValue(payload, RideMatched.class);

        if (!processed.markIfFirst(event.eventId().toString())) {
            duplicates.increment();
            return;
        }

        try {
            log.info("Notifying rider: ride {} matched to driver {} ({}m away)",
                    event.rideId(), event.driverId(), Math.round(event.distanceMeters()));
            handled.increment();
        } catch (RuntimeException e) {
            // 忘掉标记，让重新投递成为一次真正的重试，而不是静默丢弃。
            processed.forget(event.eventId().toString());
            throw e;
        }
    }
}
