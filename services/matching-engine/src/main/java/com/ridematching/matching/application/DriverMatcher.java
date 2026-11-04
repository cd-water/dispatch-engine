package com.ridematching.matching.application;

import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.DriverLocationIndex;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Optional;

/**
 * 把一条叫车请求变成一次司机认领（ADR-0004）。
 *
 * <p>按扩展的半径搜索，对候选排序，然后依次认领，直到有一个成功。循环才是重点：在负载下
 * 认领失败是<strong>预期之内</strong>的，正确的反应是转向下一个候选，绝不重试同一个司机。
 * 重试一个正在被争抢的司机，正是把一个热门上车点变成队列的方式。
 */
public class DriverMatcher {

    private final DriverLocationIndex index;
    private final DriverClaimStore claims;
    private final MatchingProperties properties;

    private final Counter matched;
    private final Counter unmatched;
    private final Counter contention;
    private final Timer duration;

    public DriverMatcher(DriverLocationIndex index,
                         DriverClaimStore claims,
                         MatchingProperties properties,
                         MeterRegistry meters) {
        this.index = index;
        this.claims = claims;
        this.properties = properties;

        this.matched = Counter.builder("matching.matched")
                .description("Requests that ended with a claimed driver").register(meters);
        this.unmatched = Counter.builder("matching.unmatched")
                .description("Requests that exhausted the radius without a driver").register(meters);
        // 让争用可观察而不是成谜的指标。这个值始终为零的负载测试，其实并没有真正触发
        // 竞争（LOAD_TESTING.md，S3）。
        this.contention = Counter.builder("driver.claim.contention")
                .description("Claims lost to another worker").register(meters);
        this.duration = Timer.builder("matching.duration")
                .description("Time from request to claimed driver").register(meters);
    }

    /**
     * 为 {@code pickup} 找到并认领最合适的可用司机。
     *
     * @param offerToken 标识这次报价的不透明令牌；之后用于释放或确认
     * @return 被认领的司机及其围栏令牌；若一个都认领不到则为空
     */
    public Optional<MatchedDriver> match(Coordinates pickup, String offerToken) {
        return duration.record(() -> attemptMatch(pickup, offerToken));
    }

    private Optional<MatchedDriver> attemptMatch(Coordinates pickup, String offerToken) {
        for (double radiusMeters : properties.getSearchRadiiMeters()) {
            List<NearbyDriver> candidates =
                    index.search(pickup, radiusMeters, properties.getCandidateLimit());

            for (NearbyDriver candidate : rank(candidates)) {
                ClaimResult claim = claims.claim(
                        candidate.driverId(), offerToken, properties.getClaimTtl());

                if (claim.won()) {
                    matched.increment();
                    return Optional.of(new MatchedDriver(
                            candidate.driverId(),
                            claim.fenceToken(),
                            candidate.distanceMeters(),
                            offerToken));
                }

                // 别人先到了。计入指标，而不是按错误记录：在负载下这是系统在正常工作，
                // 而不是失败。
                if (ClaimResult.ALREADY_CLAIMED.equals(claim.reason())
                        || ClaimResult.NOT_AVAILABLE.equals(claim.reason())) {
                    contention.increment();
                }
            }
            // 这个半径内没有可认领的司机——扩大范围而不是放弃。更宽的搜索比告诉乘客
            // "没有司机"更便宜。
        }

        unmatched.increment();
        return Optional.empty();
    }

    /**
     * 对候选排序。
     *
     * <p>目前是最先最近，而这正是 {@code GEOSEARCH} 已经返回的顺序，所以这里是一个空转的
     * 直通实现，用来占住接缝。ARCHITECTURE.md 中的复合评分——ETA、接单率、朝向、空闲
     * 时长——需要系统尚未采集的司机历史，而在不存在的数据上编造评分，比承认今天的排序很
     * 简单更糟。
     */
    private List<NearbyDriver> rank(List<NearbyDriver> candidates) {
        return candidates;
    }

    /**
     * 一位被成功认领的司机。
     *
     * @param driverId       被认领的是谁
     * @param fenceToken     必须随持久化指派写入一起提交的单调令牌
     * @param distanceMeters 与上车点的直线距离
     * @param offerToken     释放或确认这次认领所需的令牌
     */
    public record MatchedDriver(com.ridematching.domain.driver.DriverId driverId,
                                long fenceToken,
                                double distanceMeters,
                                String offerToken) {
    }
}
