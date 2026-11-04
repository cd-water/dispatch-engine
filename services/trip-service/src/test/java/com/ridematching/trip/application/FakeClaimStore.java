package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.DriverClaimStore;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存中的认领存储，保留了真实实现的那条规则：只有调用方的令牌仍拥有认领时，释放才会成功。
 *
 * <p>手写而非 mock。mock 会让测试只能断言 {@code release} 被调用过，而那并不是关键性质——
 * 关键性质是司机最终回到了池中。在这里建模令牌检查，意味着测试可以断言最终状态，而用错误
 * 令牌的释放会像在 Redis 里一样在测试中失败。
 */
class FakeClaimStore implements DriverClaimStore {

    private final Map<DriverId, String> claims = new HashMap<>();
    private final Map<DriverId, String> statuses = new HashMap<>();
    private final Map<DriverId, Boolean> pinned = new HashMap<>();
    private long fence;

    private RuntimeException failure;

    /** 在索引中把司机置为可认领。 */
    void available(DriverId driverId) {
        statuses.put(driverId, "AVAILABLE");
        claims.remove(driverId);
        pinned.remove(driverId);
    }

    /** 把司机置于匹配引擎赢得认领后留下的状态。 */
    void reserved(DriverId driverId, String offerToken) {
        statuses.put(driverId, "RESERVED");
        claims.put(driverId, offerToken);
        pinned.put(driverId, false);
    }

    /** 让之后每次调用都抛异常，模拟 Redis 不可达。 */
    void failWith(RuntimeException e) {
        this.failure = e;
    }

    boolean isPinned(DriverId driverId) {
        return Boolean.TRUE.equals(pinned.get(driverId));
    }

    boolean holdsClaim(DriverId driverId) {
        return claims.containsKey(driverId);
    }

    @Override
    public ClaimResult claim(DriverId driverId, String offerToken, Duration ttl) {
        raiseIfFailing();
        if (claims.containsKey(driverId)) {
            return ClaimResult.lost(ClaimResult.ALREADY_CLAIMED);
        }
        claims.put(driverId, offerToken);
        statuses.put(driverId, "RESERVED");
        return ClaimResult.won(++fence);
    }

    @Override
    public boolean release(DriverId driverId, String offerToken) {
        raiseIfFailing();
        // 令牌检查，这正是真实存储里释放必须写成脚本的全部原因。
        if (!offerToken.equals(claims.get(driverId))) {
            return false;
        }
        claims.remove(driverId);
        pinned.remove(driverId);
        statuses.put(driverId, "AVAILABLE");
        return true;
    }

    @Override
    public void markOnTrip(DriverId driverId, String offerToken) {
        raiseIfFailing();
        if (!offerToken.equals(claims.get(driverId))) {
            return;
        }
        statuses.put(driverId, "ON_TRIP");
        pinned.put(driverId, true);
    }

    @Override
    public String statusOf(DriverId driverId) {
        return statuses.get(driverId);
    }

    @Override
    public Optional<Long> fenceOf(DriverId driverId) {
        return Optional.of(fence);
    }

    private void raiseIfFailing() {
        if (failure != null) {
            throw failure;
        }
    }
}
