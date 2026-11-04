package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次行程在开始之前被取消。
 *
 * @param eventId    对每个事件实例唯一
 * @param rideId     行程本身，同时是分区键
 * @param driverId   已被指派的司机，若尚未指派则为 null。两种情况都很常见：
 *                   乘客可以在仍处于 MATCHING 时取消，而任何假设司机必定存在的消费者，
 *                   都会在第一次有人迅速反悔时崩溃。
 * @param reason     取消原因，由取消方提供
 * @param occurredAt 取消被记录的时刻
 */
public record RideCancelled(
        UUID eventId,
        UUID rideId,
        UUID driverId,
        String reason,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_CANCELLED;
    }
}
