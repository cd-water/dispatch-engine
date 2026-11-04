package com.ridematching.platform.messaging;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 为至少一次（at-least-once）的消费者提供去重（ADR-0003）。
 *
 * <p>Kafka 在重平衡时、在处理与偏移量提交之间崩溃时、以及任何重试时都会重新投递。若没有去重，
 * 同一次行程会让乘客收到两条"已找到司机"通知，计费也会扣两次款。
 *
 * <p>底层使用 Redis {@code SET NX} 而不是数据库表，因为这些消费者并不持有任何持久状态——
 * 为了存放事件 id 就给 notification-service 单独建一个 PostgreSQL 模式，对一件簿记性质的事情来说
 * 代价过于沉重。这个取舍是公开的：Redis 被清空后重复事件会漏过去，对通知而言那只是多推送一条消息，
 * 而不是账本被破坏。涉及金钱的消费者应当在与其写入相同的事务里去重。
 *
 * <p>TTL 用于限制内存占用。它必须远大于最长的合理重投递窗口——即消费者从滞后的偏移量重启——
 * 否则一个旧的重复事件会从已过期的标记旁边溜过去。
 */
public class ProcessedEvents {

    private final StringRedisTemplate redis;
    private final String prefix;
    private final Duration retention;

    public ProcessedEvents(StringRedisTemplate redis, String consumerGroup, Duration retention) {
        this.redis = redis;
        // 按消费者组划分命名空间：同一个事件合法地同时到达通知与计费，
        // 二者不得互相遮蔽。
        this.prefix = "processed:" + consumerGroup + ":";
        this.retention = retention;
    }

    /**
     * 把 {@code eventId} 标记为已见过，返回当前调用方是否为第一个。
     *
     * <p>{@code SET NX} 是原子的，因此处理同一条重投递记录的两个消费线程不可能同时被告知自己是第一个。
     *
     * @return 如果该事件此前未被处理过、现在应当被处理，则返回 true
     */
    public boolean markIfFirst(String eventId) {
        Boolean first = redis.opsForValue().setIfAbsent(prefix + eventId, "1", retention);
        return Boolean.TRUE.equals(first);
    }

    /**
     * 忘掉一个事件，使其可以被重新处理。
     *
     * <p>在处理失败时调用：标记是在工作<em>之前</em>写入的，因此若失败后标记仍然保留，
     * 重试会被完全压制，事件会被静默丢弃。
     */
    public void forget(String eventId) {
        redis.delete(prefix + eventId);
    }
}
