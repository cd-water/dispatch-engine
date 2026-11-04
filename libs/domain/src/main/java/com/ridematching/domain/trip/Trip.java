package com.ridematching.domain.trip;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次从叫车请求直到完成的行程——拥有行程生命周期的聚合。
 *
 * <p>每一次状态变更都经由 {@link #transitionTo} 汇入，它参照
 * {@link TripStatus#canTransitionTo} 并在非法时抛出异常而不是强行转换。不存在针对
 * {@code status} 的 setter：通过这个 API 不可能把一个 Trip 置于非法状态，因此这条不变式
 * 并不依赖调用者记得去检查。
 *
 * <p>刻意采用纯 Java（ADR-0001）：不用 Spring、不用 JPA、不用 Jackson。整个生命周期都能
 * 在无数据库的情况下于毫秒级完成单元测试，而持久化映射发生在依赖本类的适配器中，而非
 * 相反的方向。
 *
 * <p>不是线程安全的，而且是刻意为之。并发在持久状态的所在之处处理——Redis 认领与
 * PostgreSQL 唯一索引（ADR-0004）——而不是靠锁住一个只存在于单次请求作用域内的
 * 内存对象。
 */
public final class Trip {

    private final RideId id;
    private final RiderId riderId;
    private final Coordinates pickup;
    private final Coordinates dropoff;
    private final VehicleClass vehicleClass;
    private final Instant requestedAt;
    private final Clock clock;
    private final List<TripTransition> history = new ArrayList<>();

    private TripStatus status;
    private DriverId driverId;
    private Long fenceToken;
    private String offerToken;
    private Instant matchedAt;
    private Instant completedAt;

    private Trip(RideId id,
                 RiderId riderId,
                 Coordinates pickup,
                 Coordinates dropoff,
                 VehicleClass vehicleClass,
                 Clock clock,
                 Instant requestedAt,
                 TripStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.riderId = Objects.requireNonNull(riderId, "riderId");
        this.pickup = Objects.requireNonNull(pickup, "pickup");
        this.dropoff = Objects.requireNonNull(dropoff, "dropoff");
        this.vehicleClass = Objects.requireNonNull(vehicleClass, "vehicleClass");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.status = Objects.requireNonNull(status, "status");
    }

    /** 使用系统时钟创建一个新建请求的行程。 */
    public static Trip request(RideId id,
                               RiderId riderId,
                               Coordinates pickup,
                               Coordinates dropoff,
                               VehicleClass vehicleClass) {
        return request(id, riderId, pickup, dropoff, vehicleClass, Clock.systemUTC());
    }

    /**
     * 基于显式给定的时钟创建一个新建请求的行程。
     *
     * <p>时钟由外部注入，而不是在实体内部调用 {@code Instant.now()}，这样测试才能确定性地
     * 断言时间戳与顺序。时间是这个聚合的一个输入，而不是一个环境级的全局变量。
     */
    public static Trip request(RideId id,
                               RiderId riderId,
                               Coordinates pickup,
                               Coordinates dropoff,
                               VehicleClass vehicleClass,
                               Clock clock) {
        Trip trip = new Trip(id, riderId, pickup, dropoff, vehicleClass, clock,
                clock.instant(), TripStatus.REQUESTED);
        trip.history.add(new TripTransition(null, TripStatus.REQUESTED, null,
                "rider requested a trip", trip.requestedAt));
        return trip;
    }

    /**
     * 从已持久化的状态重建（rehydrate）一个行程。
     *
     * <p><strong>这是进入聚合的唯一一扇不经过转换表的门</strong>，并且只供持久化适配器
     * 使用。从数据库加载的行程已经处于它实际到达的状态；把它沿 {@code beginMatching -> offerTo -> accept}
     * 向前重放一遍会虚构出不曾发生的历史，还会改写那些让审计轨迹值得保留的时间戳。
     *
     * <p>它不会跳过的部分是不变式：一个 status 表明持有司机、却没有携带司机的快照，会在
     * {@link TripSnapshot} 的构造函数中被拒绝而不是被加载。真正重要的那条守卫——“这个行程能否合法地
     * 迁移到那个状态”——依然适用于加载之后做出的每一次转换，这正是要重建聚合而不是让适配器
     * 直接更新数据库列的全部原因。
     *
     * <p>历史不被恢复。持久化的审计轨迹存放在 {@code trip_events} 中，而每次状态变更都加载它，
     * 意味着要读取一堆没人看过的行，去回答一个只需当前 status 就能回答的问题
     * （{@code 这个行程接下来能做什么}）。重建（rehydrate）之后做出的转换会追加到一个全新的列表，
     * 适配器也只持久化这些转换。
     */
    public static Trip rehydrate(TripSnapshot snapshot, Clock clock) {
        Objects.requireNonNull(snapshot, "snapshot");
        Trip trip = new Trip(snapshot.id(), snapshot.riderId(), snapshot.pickup(),
                snapshot.dropoff(), snapshot.vehicleClass(), clock,
                snapshot.requestedAt(), snapshot.status());

        trip.driverId = snapshot.driverId();
        trip.fenceToken = snapshot.fenceToken();
        trip.offerToken = snapshot.offerToken();
        trip.matchedAt = snapshot.matchedAt();
        trip.completedAt = snapshot.completedAt();
        return trip;
    }

    /** 当前状态，为持久化而拉平。见 {@link #rehydrate}。 */
    public TripSnapshot snapshot() {
        return new TripSnapshot(id, riderId, pickup, dropoff, vehicleClass, status,
                driverId, fenceToken, offerToken, requestedAt, matchedAt, completedAt);
    }

    // ---------------------------------------------------------------- 转换

    /** 匹配引擎已接下该请求并开始搜索。 */
    public void beginMatching() {
        transitionTo(TripStatus.MATCHING, "matching engine consumed the request");
    }

    /**
     * 已认领一位司机并发出报价。
     *
     * @param driver     被认领的司机
     * @param fenceToken 来自 Redis 认领的单调递增令牌（fence token）；后续写入若携带更低的令牌
     *                   会被拒绝，这正是让过期租约变得安全的原因（ADR-0004）
     * @param offerToken 这次认领的不透明令牌（offer token），之所以保留下来，是因为之后释放 Redis 认领
     *                   需要证明对它的所有权。一个持有司机却没有它的行程，除了等 TTL 到期，
     *                   永远无法把那个司机交还回去。
     */
    public void offerTo(DriverId driver, long fenceToken, String offerToken) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(offerToken, "offerToken");
        if (fenceToken <= 0) {
            throw new IllegalArgumentException("Fence token must be positive, was " + fenceToken);
        }
        if (this.fenceToken != null && fenceToken <= this.fenceToken) {
            throw new IllegalArgumentException(
                    "Stale fence token %d; trip has already seen %d".formatted(fenceToken, this.fenceToken));
        }
        this.driverId = driver;
        this.fenceToken = fenceToken;
        this.offerToken = offerToken;
        transitionTo(TripStatus.OFFERED, "offer sent to driver " + driver);
    }

    /** 司机拒绝了报价，或报价 TTL 已到期。释放该司机并恢复搜索。 */
    public void releaseOffer(String reason) {
        DriverId released = this.driverId;
        transitionTo(TripStatus.MATCHING, reason);
        // 只有在转换成功之后才清空，因此被拒绝的转换会让行程保持原样、分毫不差。
        this.driverId = null;
        recordDriverRelease(released, reason);
    }

    /** 司机接受了报价，正在前往上车点。 */
    public void accept() {
        transitionTo(TripStatus.ACCEPTED, "driver accepted the offer");
        this.matchedAt = clock.instant();
    }

    /** 乘客已上车。 */
    public void startTrip() {
        transitionTo(TripStatus.IN_PROGRESS, "rider picked up");
    }

    /** 乘客已下车。 */
    public void complete() {
        transitionTo(TripStatus.COMPLETED, "rider dropped off");
        this.completedAt = clock.instant();
    }

    /** 在 SLA 时间窗口内未能找到任何司机。 */
    public void markUnmatched(String reason) {
        transitionTo(TripStatus.UNMATCHED, reason);
        this.driverId = null;
    }

    /** 在旅程开始之前被取消。一旦进入 IN_PROGRESS 即为非法——见 {@link TripStatus}。 */
    public void cancel(String reason) {
        transitionTo(TripStatus.CANCELLED, reason);
        this.driverId = null;
    }

    private void transitionTo(TripStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalTripTransitionException(id, status, target);
        }
        if (target.holdsDriver() && driverId == null) {
            throw new IllegalStateException(
                    "Trip %s cannot enter %s without an assigned driver".formatted(id, target));
        }
        TripStatus previous = status;
        status = target;
        history.add(new TripTransition(previous, target, driverId, reason, clock.instant()));
    }

    private void recordDriverRelease(DriverId released, String reason) {
        if (released != null) {
            history.add(new TripTransition(TripStatus.OFFERED, TripStatus.MATCHING, released,
                    "released driver " + released + ": " + reason, clock.instant()));
        }
    }

    // ---------------------------------------------------------------- 访问器

    public RideId id() {
        return id;
    }

    public RiderId riderId() {
        return riderId;
    }

    public TripStatus status() {
        return status;
    }

    public Coordinates pickup() {
        return pickup;
    }

    public Coordinates dropoff() {
        return dropoff;
    }

    public VehicleClass vehicleClass() {
        return vehicleClass;
    }

    public Optional<DriverId> assignedDriver() {
        return Optional.ofNullable(driverId);
    }

    public Optional<Long> fenceToken() {
        return Optional.ofNullable(fenceToken);
    }

    /**
     * 当前司机的 Redis 认领令牌（offer token），如果确实持有司机的话。
     *
     * <p>请在一个要放弃该司机的转换之前读取它（{@link #cancel}、
     * {@link #markUnmatched}、{@link #releaseOffer}）：那些方法会清空指派关系，而这个令牌
     * 正是认领存储释放该认领时所需的东西。
     */
    public Optional<String> offerToken() {
        return Optional.ofNullable(offerToken);
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Optional<Instant> matchedAt() {
        return Optional.ofNullable(matchedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    /** 只追加的审计轨迹，最旧的在前。 */
    public List<TripTransition> history() {
        return Collections.unmodifiableList(history);
    }

    /** 上车点与下车点之间的直线距离。不是沿路线计算的里程。 */
    public double directDistanceMeters() {
        return pickup.distanceMetersTo(dropoff);
    }

    @Override
    public boolean equals(Object o) {
        // 实体同一性：只要共享同一个 id，两个 Trip 实例就是同一个行程，
        // 无论它们的可变状态如何分道扬镳。
        if (this == o) {
            return true;
        }
        return o instanceof Trip other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Trip[id=%s, status=%s, driver=%s]".formatted(id, status, driverId);
    }
}
