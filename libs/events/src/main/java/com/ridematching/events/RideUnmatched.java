package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * 在 SLA 时间窗口内没能认领到任何司机。
 *
 * <p>选择发布而不是静默丢弃：等待一趟永远不会被匹配的行程的乘客需要被告知，
 * 而这类事件的发生率是运力短缺最清晰的信号。
 *
 * @param eventId    对每个事件实例唯一
 * @param rideId     行程本身，同时是分区键
 * @param reason     匹配放弃的原因
 * @param occurredAt 放弃的时刻
 */
public record RideUnmatched(
        UUID eventId,
        UUID rideId,
        String reason,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_UNMATCHED;
    }
}
