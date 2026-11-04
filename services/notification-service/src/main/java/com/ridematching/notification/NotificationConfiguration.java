package com.ridematching.notification;

import com.ridematching.notification.adapters.in.kafka.RideMatchedNotifier;
import com.ridematching.platform.messaging.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** 装配消费者。所有 Spring 知识都集中在这里（ADR-0001）。 */
@Configuration
public class NotificationConfiguration {

    /**
     * 去重标记保留 24 小时：宽裕地长于任何可能的重新投递窗口，包括消费者从一个严重滞后的
     * 偏移量重新启动的情况。
     */
    @Bean
    public ProcessedEvents processedEvents(StringRedisTemplate redis) {
        return new ProcessedEvents(redis, "notification-service", Duration.ofHours(24));
    }

    @Bean
    public RideMatchedNotifier rideMatchedNotifier(ObjectMapper objectMapper, ProcessedEvents processed, MeterRegistry meters) {
        return new RideMatchedNotifier(objectMapper, processed, meters);
    }
}
