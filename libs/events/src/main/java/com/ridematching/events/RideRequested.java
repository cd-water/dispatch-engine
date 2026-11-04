package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * 一位乘客请求了一次行程。这是匹配引擎消费的工作项。
 *
 * @param eventId    对每个事件实例唯一；消费者按它去重
 * @param rideId     行程本身，同时是分区键
 * @param riderId    发起请求的人
 * @param pickupLat  上车点纬度
 * @param pickupLng  上车点经度
 * @param dropoffLat 下车点纬度
 * @param dropoffLng 下车点经度
 * @param vehicleClass 请求的车型档位
 * @param occurredAt 请求被受理的时刻
 */
public record RideRequested(
        UUID eventId,
        UUID rideId,
        UUID riderId,
        double pickupLat,
        double pickupLng,
        double dropoffLat,
        double dropoffLng,
        String vehicleClass,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        // 以 rideId 作为键，才能保证同一次行程的 requested -> matched -> completed
        // 永远不会乱序地被观察到。
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_REQUESTED;
    }
}
