package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * 每一个被发布的事件都必须满足的契约。
 *
 * <p>Kafka 的投递是至少一次（at-least-once）（ADR-0003），因此消费者按 {@link #eventId()}
 * 去重。这使 id 成为契约的一部分而非元数据：没有稳定 id 的事件无法被安全地消费两次。
 *
 * <p>{@link #partitionKey()} 是保序的关键。共享同一个键的事件落在同一个分区上，
 * 因而彼此之间是严格有序的——这正是行程事件以行程 id 作为分区键（partition key）、
 * 位置事件以司机 id 作为分区键的原因。
 */
public interface DomainEvent {

    /** 稳定，且对每个事件实例唯一。重投递会重复它；重试不会改变它。 */
    UUID eventId();

    /** 事件实际发生的时刻，而不是它被发布的时刻。 */
    Instant occurredAt();

    /** Kafka 分区键。同时决定顺序与消费者的扇出。 */
    String partitionKey();

    /** 目标 topic，包含其版本后缀（例如 {@code ride.matched.v1}）。 */
    String topic();
}
