package com.ridematching.trip.application;

import com.ridematching.domain.trip.RideId;

/**
 * 行程在读取与写入之间发生了变化。
 *
 * <p>乐观锁而非悲观锁：这里的争用窗口只有几微秒宽，冲突率很低，所以每次读取都加行锁的
 * 成本会超过它省下的偶发重试。关键在于冲突要被<em>检测到</em>——一个"最后写入者获胜"的
 * 更新会让司机的接受悄悄覆盖乘客的取消，而这类 bug 正是这个系统要证明其不存在的对象。
 *
 * <p>以 {@code 409 Conflict} 加 {@code Retry-After} 呈现。调用方重新读取后重新决策，
 * 因为正确的动作确实取决于另一个写入者做了什么。
 */
public class ConcurrentTripModificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentTripModificationException(RideId rideId, int expectedVersion) {
        super("Trip %s was modified concurrently; expected version %d"
                .formatted(rideId, expectedVersion));
    }
}
