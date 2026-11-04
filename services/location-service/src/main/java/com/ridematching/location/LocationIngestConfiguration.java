package com.ridematching.location;

import tools.jackson.databind.ObjectMapper;
import com.ridematching.location.adapters.in.ws.DriverHandshakeInterceptor;
import com.ridematching.location.adapters.in.ws.DriverLocationWebSocketHandler;
import com.ridematching.geoindex.RedisKeys;
import com.ridematching.geoindex.redis.RedisDriverLocationIndex;
import com.ridematching.location.application.LocationIngestProperties;
import com.ridematching.location.application.LocationIngestService;
import com.ridematching.location.application.StaleDriverReaper;
import com.ridematching.geoindex.DriverLocationIndex;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.time.Clock;

/**
 * 把摄取路径装配起来。
 *
 * <p>所有 Spring 知识都集中在这里，这正是 {@link LocationIngestService}、清理器和适配器在
 * 测试中仍可手工构造的原因（ADR-0001）。
 */
@Configuration
@EnableWebSocket
@EnableScheduling
@EnableConfigurationProperties(LocationIngestProperties.class)
public class LocationIngestConfiguration implements WebSocketConfigurer, SchedulingConfigurer {

    private final LocationIngestService ingestService;
    private final StaleDriverReaper reaper;
    private final LocationIngestProperties properties;
    private final ObjectMapper objectMapper;

    public LocationIngestConfiguration(LocationIngestService ingestService,
                                       StaleDriverReaper reaper,
                                       LocationIngestProperties properties,
                                       ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.reaper = reaper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    /** 键布局与匹配引擎共享，所以它来自 geoindex。 */
    @Bean
    public static RedisKeys redisKeys(LocationIngestProperties properties) {
        return new RedisKeys(properties.getCity());
    }

    @Bean
    public static DriverLocationIndex driverLocationIndex(StringRedisTemplate redis, RedisKeys keys) {
        return new RedisDriverLocationIndex(redis, keys);
    }

    @Bean
    public static LocationIngestService locationIngestService(DriverLocationIndex index,
                                                              LocationIngestProperties properties,
                                                              MeterRegistry meters,
                                                              Clock clock) {
        return new LocationIngestService(index, properties, meters, clock);
    }

    @Bean
    public static StaleDriverReaper staleDriverReaper(DriverLocationIndex index,
                                                      LocationIngestProperties properties,
                                                      Clock clock) {
        return new StaleDriverReaper(index, properties, clock);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new DriverLocationWebSocketHandler(ingestService, objectMapper), "/ws/driver")
                .addInterceptors(new DriverHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    /**
     * 以编程方式调度刷写器和清理器。
     *
     * <p>不用 {@code @Scheduled(fixedDelayString = "...")}：该属性只接受纯毫秒数或 ISO-8601
     * 字符串，无法读取 {@code @ConfigurationProperties} 能顺利绑定的同一个 {@code 50ms} 值。
     * 在这里注册意味着间隔只有一个定义——{@link LocationIngestProperties}——而不是同一个
     * 数字用两种语法写两遍、各自漂移。
     *
     * <p>固定<em>延迟</em>，不是固定频率：如果某次刷写耗时超过了间隔，固定频率会把重叠的
     * 刷写堆到一个本已吃力的 Redis 上。固定延迟则让批量增长，这是更便宜的失败方式，而且会
     * 自我纠正。
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(ingestService::flush, properties.getFlushInterval());
        registrar.addFixedDelayTask(reaper::reap, properties.getReaperInterval());
    }
}
