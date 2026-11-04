package com.ridematching.domain.trip;

import com.ridematching.domain.driver.DriverId;

import java.time.Instant;
import java.util.Optional;

/**
 * 一次被记录下来的状态变更，追加到行程的历史中且永不被修改。
 *
 * <p>它是 {@code trip_events} 表在内存中的形态：一条只追加的审计
 * 轨迹，无需推断就能回答“这个行程为什么会处于这个状态？”。当乘客
 * 对车费提出争议，或司机对一次指派提出异议时，这一串转换
 * 连同各自的原因就是记录本身。
 *
 * @param from      被离开的状态；初始进入 REQUESTED 的那次转换时为 null
 * @param to        进入的状态
 * @param driverId  涉及的司机，如果有的话
 * @param reason    简短的、人类可读的原因，例如“offer expired”（报价过期）、“rider cancelled”（乘客取消）
 * @param occurredAt 变更发生的时间
 */
public record TripTransition(
        TripStatus from,
        TripStatus to,
        DriverId driverId,
        String reason,
        Instant occurredAt) {

    public TripTransition {
        if (to == null) {
            throw new IllegalArgumentException("Transition target must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Transition timestamp must not be null");
        }
    }

    public Optional<DriverId> driver() {
        return Optional.ofNullable(driverId);
    }

    public Optional<TripStatus> previousState() {
        return Optional.ofNullable(from);
    }
}
