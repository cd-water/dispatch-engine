package com.ridematching.dispatch;

import com.ridematching.platform.outbox.JdbcOutboxWriter;
import com.ridematching.platform.outbox.OutboxPublisher;
import com.ridematching.dispatch.adapters.out.persistence.JdbcIdempotencyStore;
import com.ridematching.dispatch.adapters.out.persistence.JdbcTripRepository;
import com.ridematching.platform.outbox.OutboxWriter;
import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.dispatch.application.port.TripRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * 装配派单 API。所有 Spring 知识都集中在这里，好让用例和适配器在测试中仍可手工构造
 * （ADR-0001）。
 */
@Configuration
@EnableScheduling
public class DispatchConfiguration implements SchedulingConfigurer {

    private final OutboxPublisher outboxPublisher;

    public DispatchConfiguration(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    @Bean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public static OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcOutboxWriter(jdbc, objectMapper);
    }

    @Bean
    public static TripRepository tripRepository(JdbcClient jdbc, OutboxWriter outbox) {
        return new JdbcTripRepository(jdbc, outbox);
    }

    @Bean
    public static OutboxPublisher outboxPublisher(JdbcClient jdbc,
                                                  KafkaTemplate<String, String> kafka,
                                                  MeterRegistry meters,
                                                  Clock clock) {
        return new OutboxPublisher(jdbc, kafka, meters, clock, 100);
    }

    @Bean
    public static IdempotencyStore idempotencyStore(JdbcClient jdbc, Clock clock) {
        return new JdbcIdempotencyStore(jdbc, clock);
    }

    @Bean
    public static RequestRideUseCase requestRideUseCase(TripRepository trips,
                                                        IdempotencyStore idempotency,
                                                        MeterRegistry meters,
                                                        Clock clock) {
        return new RequestRideUseCase(trips, idempotency, meters, clock);
    }

    /**
     * 以较短的固定延迟排空发件箱。
     *
     * <p>用固定延迟而不是固定频率：如果 Kafka 变慢、一批耗时超过间隔，固定频率会把重叠的
     * 排空堆到本已吃力的 broker 上。固定延迟则让积压增长，而 outbox.backlog 指标会把它
     * 显示出来。
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(outboxPublisher::publishBatch, java.time.Duration.ofMillis(200));
    }
}
