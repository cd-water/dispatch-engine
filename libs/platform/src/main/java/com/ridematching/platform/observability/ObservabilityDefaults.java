package com.ridematching.platform.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * 应用到每个服务的指标默认值。
 *
 * <p>本项目的 SLO 以 p99 表述（见 README），而 p99 无法由普通计时器计算得出——若不启用直方图桶，
 * Micrometer 只会发布 count、sum 和 max。这里集中开启它，使任何服务都不会在缺少负载测试
 * 唯一评判依据的那个统计量时被意外发布上线。
 *
 * <p>百分位数以直方图桶的形式发布，而不是客户端侧百分位数，这样 Prometheus 才能在多个实例间
 * 正确聚合：客户端侧的 p99 若在多个副本之间求平均必然是错的。
 *
 * <p>注册为自动配置而非 {@code @Configuration}，因为各个服务并不对 {@code com.ridematching.platform}
 * 做组件扫描——它们只扫描自己的包。自动配置意味着只需加上 platform 依赖即可生效。
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class ObservabilityDefaults {

    /**
     * 获得直方图桶的计量项。桶会成倍放大时间序列基数，因此这是一份刻意维护的白名单，
     * 而不是一刀切的设置。
     */
    private static final String[] TIMED_METER_PREFIXES = {
            "http.server.requests",
            "matching.duration",
            "redis.geo.query.duration",
            "location.ingest.duration"
    };

    @Bean
    public MeterFilter percentileHistogramFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!isTimed(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        // 来自 README 的 SLO 边界。显式的桶让 Prometheus 能直接回答
                        // "有多大比例突破了 SLO？"。
                        .serviceLevelObjectives(
                                (double) Duration.ofMillis(50).toNanos(),
                                (double) Duration.ofMillis(120).toNanos(),
                                (double) Duration.ofMillis(500).toNanos(),
                                (double) Duration.ofSeconds(1).toNanos())
                        .minimumExpectedValue((double) Duration.ofMillis(1).toNanos())
                        .maximumExpectedValue((double) Duration.ofSeconds(10).toNanos())
                        .build()
                        .merge(config);
            }
        };
    }

    private static boolean isTimed(String meterName) {
        for (String prefix : TIMED_METER_PREFIXES) {
            if (meterName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
