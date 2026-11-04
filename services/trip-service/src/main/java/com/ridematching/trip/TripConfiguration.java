package com.ridematching.trip;

import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import com.ridematching.geoindex.redis.RedisDriverClaimStore;
import com.ridematching.platform.messaging.ProcessedEvents;
import com.ridematching.platform.outbox.JdbcOutboxWriter;
import com.ridematching.platform.outbox.OutboxPublisher;
import com.ridematching.platform.outbox.OutboxWriter;
import com.ridematching.trip.adapters.in.kafka.RideMatchedConsumer;
import com.ridematching.trip.adapters.out.persistence.JdbcTripStore;
import com.ridematching.trip.application.ClaimReconciler;
import com.ridematching.trip.application.TripLifecycle;
import com.ridematching.trip.application.port.TripStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * 装配行程服务。所有 Spring 知识都集中在这里，好让用例和适配器在测试中仍可手工构造
 * （ADR-0001）。
 */
@Configuration
@EnableScheduling
public class TripConfiguration implements SchedulingConfigurer {

    private final OutboxPublisher outboxPublisher;
    private final ClaimReconciler claimReconciler;
    private final Duration outboxInterval;
    private final Duration reconcileInterval;

    public TripConfiguration(OutboxPublisher outboxPublisher,
                             ClaimReconciler claimReconciler,
                             @Value("${ridematching.trip.outbox-interval:PT0.2S}")
                             Duration outboxInterval,
                             @Value("${ridematching.trip.reconcile-interval:PT30S}")
                             Duration reconcileInterval) {
        this.outboxPublisher = outboxPublisher;
        this.claimReconciler = claimReconciler;
        this.outboxInterval = outboxInterval;
        this.reconcileInterval = reconcileInterval;
    }

    @Bean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public static RedisKeys redisKeys(@Value("${ridematching.matching.city:default}") String city) {
        // 与匹配引擎读取的是同一个属性。若两个服务指向了不同的城市，各自都只会看到
        // 键空间里空的一半，而且不会有任何东西明显报错。
        return new RedisKeys(city);
    }

    @Bean
    public static DriverClaimStore driverClaimStore(StringRedisTemplate redis, RedisKeys keys) {
        return new RedisDriverClaimStore(redis, keys);
    }

    @Bean
    public static OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcOutboxWriter(jdbc, objectMapper);
    }

    @Bean
    public static OutboxPublisher outboxPublisher(JdbcClient jdbc,
                                                  KafkaTemplate<String, String> kafka,
                                                  MeterRegistry meters,
                                                  Clock clock) {
        return new OutboxPublisher(jdbc, kafka, meters, clock, 100);
    }

    @Bean
    public static TripStore tripStore(JdbcClient jdbc, OutboxWriter outbox, Clock clock) {
        return new JdbcTripStore(jdbc, outbox, clock);
    }

    @Bean
    public static TripLifecycle tripLifecycle(TripStore trips,
                                              DriverClaimStore claims,
                                              MeterRegistry meters,
                                              Clock clock) {
        return new TripLifecycle(trips, claims, meters, clock);
    }

    @Bean
    public static ClaimReconciler claimReconciler(StringRedisTemplate redis,
                                                  JdbcClient jdbc,
                                                  DriverClaimStore claims,
                                                  RedisKeys keys,
                                                  MeterRegistry meters) {
        return new ClaimReconciler(redis, jdbc, claims, keys, meters, 200);
    }

    @Bean
    public static ProcessedEvents processedEvents(StringRedisTemplate redis) {
        return new ProcessedEvents(redis, "trip-service", Duration.ofHours(24));
    }

    @Bean
    public static RideMatchedConsumer rideMatchedConsumer(TripStore trips,
                                                          DriverClaimStore claims,
                                                          ObjectMapper objectMapper,
                                                          ProcessedEvents processed,
                                                          MeterRegistry meters) {
        return new RideMatchedConsumer(trips, claims, objectMapper, processed, meters);
    }

    /**
     * 两个后台循环，间隔差异很大。
     *
     * <p>发件箱排空处于请求路径的关键时间线上——滞留在发件箱里的事件就是乘客还没来得及收到
     * 的通知——所以它每 200ms 运行一次。认领调和器是兜底机制，针对一个本就不该发生的情况，
     * 而扫描 Redis 键空间是有成本的，所以它每 30 秒运行一次。让它更快不会让系统更正确，只会
     * 更忙。
     *
     * <p>两者都用固定延迟而不是固定频率：如果某一轮耗时超过了间隔，固定频率会把重叠的轮次
     * 堆到已经吃力的东西上。
     *
     * <p>两个间隔都是可配置属性，测试可以把它们挪开。后台循环轮询测试正在清空的同一批表，
     * 会产生属于测试夹具而非代码的回滚错误；一个打印 ERROR 的绿色构建，会教人们不再看日志。
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(outboxPublisher::publishBatch, outboxInterval);
        registrar.addFixedDelayTask(claimReconciler::reconcile, reconcileInterval);
    }
}
