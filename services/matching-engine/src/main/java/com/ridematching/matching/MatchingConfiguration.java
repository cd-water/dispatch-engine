package com.ridematching.matching;

import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.DriverLocationIndex;
import com.ridematching.geoindex.RedisKeys;
import com.ridematching.geoindex.redis.RedisDriverClaimStore;
import com.ridematching.geoindex.redis.RedisDriverLocationIndex;
import com.ridematching.matching.adapters.in.kafka.RideRequestedConsumer;
import com.ridematching.matching.application.DriverMatcher;
import com.ridematching.matching.application.MatchingProperties;
import com.ridematching.platform.messaging.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * 装配匹配引擎。
 *
 * <p>Redis 键布局来自共享的 geoindex 模块，而不是在这里重建——这个服务搜索的正是
 * location-service 写入的同一个索引，而两份各自独立的键字符串实现最终会分叉成两个互不相交
 * 的命名空间。
 */
@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RedisKeys redisKeys(
            @org.springframework.beans.factory.annotation.Value("${ridematching.matching.city:default}")
            String city) {
        return new RedisKeys(city);
    }

    @Bean
    public DriverLocationIndex driverLocationIndex(StringRedisTemplate redis, RedisKeys keys) {
        return new RedisDriverLocationIndex(redis, keys);
    }

    @Bean
    public DriverClaimStore driverClaimStore(StringRedisTemplate redis, RedisKeys keys) {
        return new RedisDriverClaimStore(redis, keys);
    }

    @Bean
    public DriverMatcher driverMatcher(DriverLocationIndex index,
                                       DriverClaimStore claims,
                                       MatchingProperties properties,
                                       MeterRegistry meters) {
        return new DriverMatcher(index, claims, properties, meters);
    }

    @Bean
    public ProcessedEvents processedEvents(StringRedisTemplate redis) {
        return new ProcessedEvents(redis, "matching-engine", Duration.ofHours(24));
    }

    @Bean
    public RideRequestedConsumer rideRequestedConsumer(DriverMatcher matcher,
                                                       KafkaTemplate<String, String> kafka,
                                                       ObjectMapper objectMapper,
                                                       ProcessedEvents processed,
                                                       Clock clock) {
        return new RideRequestedConsumer(matcher, kafka, objectMapper, processed, clock);
    }
}
