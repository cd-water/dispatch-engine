package com.ridematching.geoindex;

import com.ridematching.domain.driver.DriverId;

import java.time.Duration;

/**
 * 两层司机认领中的快速路径（ADR-0004）。
 *
 * <p>刻意保持狭窄。这个端口无法表达"先加锁再做任意工作"，因为那种模式的安全性依赖于持有者
 * 在租约过期前完成——而 JVM GC 暂停会使这一假设失效。调用方先认领，再操作，然后要么确认
 * 要么释放，并且每一次持久化写入都携带围栏令牌，从而让迟到者在资源处被拒绝。
 */
public interface DriverClaimStore {

    /**
     * 尝试为 {@code offerToken} 预留 {@code driverId}。
     *
     * <p>原子性：并发调用方中恰好只有一个能赢。失败方会被立即告知，并且应当转向下一个候选
     * 而不是重试同一个司机——重试正是把一个有争用的司机变成队列的方式。
     */
    ClaimResult claim(DriverId driverId, String offerToken, Duration ttl);

    /**
     * 释放认领，但仅在 {@code offerToken} 仍拥有它时。
     *
     * <p>令牌校验是必需的：没有它，一个认领已过期的工作者可能会删除随后合法接手该司机的
     * 那个工作者所持有的认领。
     */
    boolean release(DriverId driverId, String offerToken);

    /** 在派单持久化之后，把已认领的司机标记为行程中。 */
    void markOnTrip(DriverId driverId, String offerToken);

    /** 司机在索引中的当前状态，未知司机时为 null。 */
    String statusOf(DriverId driverId);

    /** 司机当前的围栏令牌，从未被认领过时为空。 */
    java.util.Optional<Long> fenceOf(DriverId driverId);
}
