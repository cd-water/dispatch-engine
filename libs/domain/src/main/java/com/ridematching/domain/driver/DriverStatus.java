package com.ridematching.domain.driver;

/**
 * 一位司机的调度可用性，镜像在 Redis 的 {@code driver:{id}:state} 哈希中。
 *
 * <pre>
 *   OFFLINE ──▶ AVAILABLE ──▶ RESERVED ──▶ ON_TRIP ──▶ AVAILABLE
 * </pre>
 *
 * <p>{@link #RESERVED} 是一个短暂存在的窗口：介于某个匹配工作节点赢得 Redis
 * 认领，与司机要么接受、要么报价过期之间（ADR-0004）。它的存在是为了让
 * 第二个工作节点的 {@code GEOSEARCH} 无需查询 PostgreSQL 就能看出该司机已被占用。
 */
public enum DriverStatus {

    /** 未连接。完全不出现在地理空间索引中。 */
    OFFLINE,

    /** 已连接、空闲，并有资格作为匹配候选被返回。 */
    AVAILABLE,

    /** 已被某个匹配工作节点认领；尚有一份报价未决。 */
    RESERVED,

    /** 正在载送乘客。 */
    ON_TRIP;

    /** 处于该状态的司机是否可以被派给新的叫车请求。 */
    public boolean isDispatchable() {
        return this == AVAILABLE;
    }
}
