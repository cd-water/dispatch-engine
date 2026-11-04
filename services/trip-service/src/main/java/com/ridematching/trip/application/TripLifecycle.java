package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.events.DomainEvent;
import com.ridematching.events.RideCancelled;
import com.ridematching.events.RideCompleted;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.trip.application.port.TripStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 推动行程在状态之间迁移，并在行程结束后把司机交还到池中。
 *
 * <p>这里的每个方法都是同样的四步：加载、转换、持久化，然后调和 Redis。顺序才是重点。
 *
 * <p><strong>先 PostgreSQL，后 Redis。</strong>唯一索引是正确性边界（ADR-0004），Redis 认领
 * 只是竞争优化器，所以必须由持久化写入来做决定。先释放认领会短暂地把司机标为可用，而一条已
 * 提交的行仍持有他——这本身无害，因为竞争者的认领会随后被索引拒绝，但它把一次廉价的 Redis
 * 拒绝变成了一次昂贵的数据库拒绝，而这恰恰是认领机制想要避免的交换。
 *
 * <p>这个顺序的代价是一个窗口：在提交之后、释放之前崩溃，会让司机在 Redis 中保持预留，却没有
 * 任何活跃行程。这不是假设——{@code markOnTrip} 会对认领标记调用 {@code PERSIST}，因此行程
 * 进行中的司机没有 TTL 可以把它救回来。{@link ClaimReconciler} 通过让 Redis 收敛到
 * PostgreSQL 的说法来关上这个窗口，而这是唯一可能正确的方向。
 */
public class TripLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TripLifecycle.class);

    private final TripStore trips;
    private final DriverClaimStore claims;
    private final Clock clock;

    private final Counter accepted;
    private final Counter started;
    private final Counter completed;
    private final Counter cancelled;
    private final Counter released;
    private final Counter releaseFailed;

    public TripLifecycle(TripStore trips, DriverClaimStore claims, MeterRegistry meters, Clock clock) {
        this.trips = trips;
        this.claims = claims;
        this.clock = clock;

        this.accepted = counter(meters, "trip.accepted", "Offers accepted by a driver");
        this.started = counter(meters, "trip.started", "Trips where the rider was picked up");
        this.completed = counter(meters, "trip.completed", "Trips finished normally");
        this.cancelled = counter(meters, "trip.cancelled", "Trips called off before they began");
        // 司机池健康度指标。释放量应当与完成数加取消数保持一致；持续的缺口意味着司机正在
        // 从池中泄漏，负载测试会先看到匹配率下降，而远早于任何报错。
        this.released = counter(meters, "driver.claim.released", "Claims handed back to the pool");
        this.releaseFailed = counter(meters, "driver.claim.release.failed",
                "Claims whose release did not take effect");
    }

    private static Counter counter(MeterRegistry meters, String name, String description) {
        return Counter.builder(name).description(description).register(meters);
    }

    /** 司机接受了报价。不发事件：目前没有任何下游会因接受而行动。 */
    public TripStatus accept(RideId rideId) {
        return transition(rideId, trip -> trip.accept(), null, accepted);
    }

    /** 乘客已上车。 */
    public TripStatus start(RideId rideId) {
        return transition(rideId, trip -> trip.startTrip(), null, started);
    }

    /**
     * 乘客已送达下车。终态，也是司机回到池中的时点。
     */
    public TripStatus complete(RideId rideId) {
        return transition(rideId, trip -> trip.complete(),
                (trip, held) -> new RideCompleted(UUID.randomUUID(), trip.id().value(),
                        held.driverId() == null ? null : held.driverId().value(),
                        trip.directDistanceMeters(), clock.instant()),
                completed);
    }

    /**
     * 行程被取消。终态。
     *
     * <p>一旦进入 {@code IN_PROGRESS} 即非法——领域层会拒绝它，且该拒绝会以 409 呈现，而不是
     * 被悄悄强转成某个合法状态。见 {@link TripStatus}。
     */
    public TripStatus cancel(RideId rideId, String reason) {
        return transition(rideId, trip -> trip.cancel(reason),
                (trip, held) -> new RideCancelled(UUID.randomUUID(), trip.id().value(),
                        held.driverId() == null ? null : held.driverId().value(),
                        reason, clock.instant()),
                cancelled);
    }

    /**
     * 加载行程用于读取。
     *
     * <p>返回聚合而不是一行记录，这样调用方看到的正是转换守卫会看到的同一个状态。
     */
    public Optional<Trip> find(RideId rideId) {
        return trips.load(rideId).map(loaded -> loaded.trip());
    }

    private TripStatus transition(RideId rideId,
                                  Consumer<Trip> change,
                                  BiFunction<Trip, HeldClaim, DomainEvent> eventFactory,
                                  Counter metric) {
        TripStore.Versioned loaded = trips.load(rideId)
                .orElseThrow(() -> new TripNotFoundException(rideId));
        Trip trip = loaded.trip();

        // 在转换之前捕获。cancel() 和 markUnmatched() 会清空指派，而不知道认领属于谁就无法
        // 释放——在转换之后读取会什么也找不到，并悄悄让司机滞留。
        HeldClaim held = HeldClaim.of(trip);

        change.accept(trip);

        // 在转换之后构建，这样它报告的是实际到达的状态，但司机信息是单独传入的：
        // cancel() 和 complete() 会清空指派，而一个不指名任何人的事件会让计费与通知无法
        // 说明刚结束的是谁的行程。只有当从未持有过司机时，司机才是真的不存在。
        DomainEvent event = eventFactory == null ? null : eventFactory.apply(trip, held);

        trips.save(trip, loaded.version(), event);
        metric.increment();

        if (trip.status() == TripStatus.ACCEPTED) {
            pinClaim(rideId, held);
        } else if (trip.status().isTerminal()) {
            releaseClaim(rideId, held);
        }
        return trip.status();
    }

    /**
     * 防止认领在已承诺该行程的司机脚下过期。
     *
     * <p>认领 TTL 是按<em>报价</em>来定长的——几秒钟，足够司机点一下接受。而一趟行程要持续
     * 二十分钟。没有这一步，标记会在司机还在路上时就过期，Redis 会把他们标为可用，匹配引擎
     * 会不停给他们派发随后被 {@code uniq_driver_active_trip} 索引拒绝的行程：结果正确，但在
     * 整段旅程期间，每一次尝试都会白白烧掉一个候选名额。
     *
     * <p>{@code markOnTrip} 会调用 {@code PERSIST}，这正是为什么末尾的释放是强制性的，而不
     * 只是收尾——之后已经没有 TTL 可以兜底了。
     */
    private void pinClaim(RideId rideId, HeldClaim held) {
        if (!held.present()) {
            return;
        }
        try {
            claims.markOnTrip(held.driverId(), held.offerToken());
        } catch (RuntimeException e) {
            // 不致命：接受已经提交。最坏情况是上面描述的白费报价空转，调和器会在行程结束时
            // 终止它。
            log.warn("Could not pin claim for driver {} on trip {}: {}",
                    held.driverId(), rideId, e.toString());
        }
    }

    /**
     * 在终态持久化之后把司机交还。
     *
     * <p>失败只记录和计数，绝不重抛。行程已经提交，调用方的请求确实成功了；把一次 Redis
     * 抖动变成 500，会告诉乘客其已完成的行程失败了。调和器会接手这里遗漏的任何情况。
     */
    private void releaseClaim(RideId rideId, HeldClaim held) {
        if (!held.present()) {
            // 在仍处于 MATCHING 时被取消的行程从未持有司机。这不是错误。
            return;
        }
        try {
            if (claims.release(held.driverId(), held.offerToken())) {
                released.increment();
            } else {
                // token 已不再拥有该标记：认领过期且别人已经拿到了这个司机，或者调和器
                // 已经释放了它。无论哪种情况，这个司机都不归我们释放，强行删除标记会把
                // 司机从合法持有者手中夺走。
                releaseFailed.increment();
                log.debug("Claim for driver {} on trip {} was not ours to release",
                        held.driverId(), rideId);
            }
        } catch (RuntimeException e) {
            releaseFailed.increment();
            log.warn("Could not release claim for driver {} on trip {}; "
                    + "the reconciler will retry: {}", held.driverId(), rideId, e.toString());
        }
    }

    /** 读取行程那一刻它持有的司机与认领令牌。 */
    private record HeldClaim(DriverId driverId, String offerToken) {

        static HeldClaim of(Trip trip) {
            return new HeldClaim(trip.assignedDriver().orElse(null),
                    trip.offerToken().orElse(null));
        }

        boolean present() {
            return driverId != null && offerToken != null;
        }
    }
}
