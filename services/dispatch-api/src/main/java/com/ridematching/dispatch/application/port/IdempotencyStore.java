package com.ridematching.dispatch.application.port;

import java.util.Optional;

/**
 * 幂等键的存储（ADR-0005）。
 *
 * <p>刻意用 PostgreSQL 而不是 Redis：这是一个正确性机制，必须能经受住缓存被清空。
 */
public interface IdempotencyStore {

    /**
     * 尝试为一个新请求抢占 {@code key}。
     *
     * <p>必须是一条原子语句——{@code INSERT ... ON CONFLICT DO NOTHING}——而不是先读后写。
     * 两个同时发生的重试都会调用它；由数据库决定谁赢，不存在两者都看到"未找到"的窗口。
     *
     * @return 若该调用方抢到了键、应当执行工作，则为 true
     */
    boolean tryClaim(String key, String requestHash);

    /** 读取一条已有记录。仅当该键从未被抢占或已过期时为空。 */
    Optional<IdempotencyRecord> find(String key);

    /** 存储响应，以便之后的重试原样重放它。 */
    void complete(String key, java.util.UUID rideId, int responseStatus, String responseBody);

    /** 释放一个工作已失败的抢占，让客户端的重试可以真正重试。 */
    void release(String key);

    /** 删除已过期的键。返回删除了多少个。 */
    int purgeExpired();
}
