package com.ridematching.domain.trip;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个行程已持久化的状态，被拉平后的形态。
 *
 * <p>它的存在是为了让持久化适配器有且只有一个可以来回映射的东西，而不必
 * 一个字段一个字段地伸进聚合内部。{@link Trip#snapshot()} 产出它、
 * {@link Trip#rehydrate} 消费它，因此给聚合增加一个字段会让两个方向都在编译期一起报错，
 * 而不是在写回的路上悄悄丢掉一列。
 *
 * <p>刻意不把它做成聚合本身：一个带公开组件的 record 没有不变式、
 * 也没有转换守卫，这对一行数据是合适的，对一个行程则是错的。所有
 * 强制执行状态机的东西都留在 {@link Trip} 中。
 *
 * @param offerToken 来自预留了 {@code driverId} 的那次 Redis 认领的令牌（offer token）。之后释放该认领
 *                   需要用到它，而这也正是要把它存储下来而不是推导出来的原因：
 *                   匹配引擎的命名约定是那个服务自己的事，由另一个服务去拼出这个字符串，
 *                   会把两者耦合到一个从未有人声明过的格式上。
 */
public record TripSnapshot(
        RideId id,
        RiderId riderId,
        Coordinates pickup,
        Coordinates dropoff,
        VehicleClass vehicleClass,
        TripStatus status,
        DriverId driverId,
        Long fenceToken,
        String offerToken,
        Instant requestedAt,
        Instant matchedAt,
        Instant completedAt) {

    public TripSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(riderId, "riderId");
        Objects.requireNonNull(pickup, "pickup");
        Objects.requireNonNull(dropoff, "dropoff");
        Objects.requireNonNull(vehicleClass, "vehicleClass");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");

        // 与 trips_driver_required_when_held CHECK 约束在 PostgreSQL 中强制执行的是同一条不变式，
        // 既在写入数据库的方向上适用，也在从数据库读出的方向上适用。一条
        // 违反它的行就是损坏的数据，把它加载进聚合只会扩散
        // 损坏，而不是让它浮出水面。
        if (status.holdsDriver() && driverId == null) {
            throw new IllegalArgumentException(
                    "Trip %s is %s but has no driver".formatted(id, status));
        }
    }
}
