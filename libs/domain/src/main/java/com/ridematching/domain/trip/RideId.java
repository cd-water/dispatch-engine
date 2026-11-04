package com.ridematching.domain.trip;

import java.util.UUID;

/**
 * 单次叫车请求及其随后形成的行程的标识符。
 *
 * <p>选择包装而不是直接传递裸的 {@code UUID}，这样当在期望 ride id 的地方传入乘客 id 时，
 * 编译器能拒绝这种经典混淆——在一个连续接收三个 UUID 的调用点上，
 * 这类 bug 是不可见的。
 *
 * <p>它同时充当每一条 ride 事件的 Kafka 分区键，这正是保证
 * {@code requested -> matched -> completed} 永远不会被乱序观察到的原因（ADR-0003）。
 */
public record RideId(UUID value) {

    public RideId {
        if (value == null) {
            throw new IllegalArgumentException("RideId must not be null");
        }
    }

    public static RideId newId() {
        return new RideId(UUID.randomUUID());
    }

    public static RideId of(String value) {
        return new RideId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
