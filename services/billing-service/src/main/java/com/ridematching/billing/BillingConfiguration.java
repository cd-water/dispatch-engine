package com.ridematching.billing;

import com.ridematching.billing.adapters.in.kafka.RideMatchedBilling;
import com.ridematching.platform.messaging.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** 装配消费者。所有 Spring 知识都集中在这里（ADR-0001）。 */
@Configuration
public class BillingConfiguration {

    /**
     * 去重标记保留 24 小时：宽裕地长于任何可能的重新投递窗口，包括消费者从一个严重滞后的
     * 偏移量重新启动的情况。
     */
    @Bean
    public ProcessedEvents processedEvents(StringRedisTemplate redis) {
        return new ProcessedEvents(redis, "billing-service", Duration.ofHours(24));
    }

    @Bean
    public RideMatchedBilling rideMatchedBilling(ObjectMapper objectMapper, ProcessedEvents processed, MeterRegistry meters) {
        return new RideMatchedBilling(objectMapper, processed, meters);
    }
}
