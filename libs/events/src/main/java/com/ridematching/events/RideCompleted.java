package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次行程正常结束。司机已被归还到可用司机池。
 *
 * <p>这是计费的触发点：事件流中第一个意味着产生了车费的信号。
 * {@link RideMatched} 只表示司机被指派了，两者不是一回事——以取消收场的指派不产生任何费用。
 *
 * @param eventId    对每个事件实例唯一
 * @param rideId     行程本身，同时是分区键
 * @param driverId   执驾该行程的司机
 * @param distanceMeters 上车点到下车点的直线距离。这不是按路线计算的里程，因此也不是车费：
 *                       真实的平台按实际行驶路径计费。这个字段以它的真实含义命名，
 *                       而不是把自己包装成别的样子。
 * @param occurredAt 乘客下车的时刻
 */
public record RideCompleted(
        UUID eventId,
        UUID rideId,
        UUID driverId,
        double distanceMeters,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_COMPLETED;
    }
}
