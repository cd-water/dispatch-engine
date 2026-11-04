package com.ridematching.domain.driver;

import java.util.UUID;

/**
 * 一位司机的标识符。
 *
 * <p>选择包装而不是直接传递裸的 {@code UUID}，这样当在期望司机 id 的地方传入乘客 id 时，
 * 编译器能拒绝。
 *
 * <p>整个并发设计正是围绕这个 id 展开的：它是 Redis 认领键、
 * PostgreSQL 中 {@code uniq_driver_active_trip} 唯一部分索引的主体，
 * 同时也是位置遥测的 Kafka 分区键（ADR-0004、ADR-0003）。
 */
public record DriverId(UUID value) {

    public DriverId {
        if (value == null) {
            throw new IllegalArgumentException("DriverId must not be null");
        }
    }

    public static DriverId newId() {
        return new DriverId(UUID.randomUUID());
    }

    public static DriverId of(String value) {
        return new DriverId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
