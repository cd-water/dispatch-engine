package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次行程已认领到司机。扇出到通知与计费。
 *
 * @param eventId        对每个事件实例唯一
 * @param rideId         行程本身，同时是分区键
 * @param driverId       被认领的司机
 * @param fenceToken     这次认领的围栏令牌（fence token）（ADR-0004）
 * @param offerToken     Redis 认领的不透明令牌。随事件携带，是因为记录这次指派的
 *                       服务并不是做出认领的那一个，而日后释放该认领需要证明对它的所有权。
 *                       若靠命名约定去重建令牌，就会让两个服务都耦合到一份谁都没有声明的
 *                       字符串格式上。
 * @param distanceMeters 匹配时刻司机到上车点的直线距离
 * @param occurredAt     认领成功的时刻
 */
public record RideMatched(
        UUID eventId,
        UUID rideId,
        UUID driverId,
        long fenceToken,
        String offerToken,
        double distanceMeters,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_MATCHED;
    }
}
