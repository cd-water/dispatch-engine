package com.ridematching.domain.rider;

import java.util.UUID;

/**
 * 一位乘客——即请求行程的乘客——的标识符。
 *
 * <p>选择包装而不是直接传递裸的 {@code UUID}，这样当在期望乘客 id 的地方传入司机 id 时，
 * 编译器能拒绝这种经典混淆；在一个连续接收多个 UUID 的调用点上，
 * 这类 bug 是不可见的。
 */
public record RiderId(UUID value) {

    public RiderId {
        if (value == null) {
            throw new IllegalArgumentException("RiderId must not be null");
        }
    }

    public static RiderId newId() {
        return new RiderId(UUID.randomUUID());
    }

    public static RiderId of(String value) {
        return new RiderId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
