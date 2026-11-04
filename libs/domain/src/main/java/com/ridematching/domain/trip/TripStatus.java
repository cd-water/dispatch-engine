package com.ridematching.domain.trip;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 行程可以处于的状态，以及它们之间唯一被允许的转换。
 *
 * <p>转换表只在这里声明一次，而不是散落在服务代码的各个 {@code if}
 * 语句中。{@link Trip} 里的每一处守卫都参照它，因此“什么才是合法的”
 * 只有一个可以被穷尽测试的定义。
 *
 * <pre>
 *   REQUESTED ──▶ MATCHING ──▶ OFFERED ──▶ ACCEPTED ──▶ IN_PROGRESS ──▶ COMPLETED
 *                    │  ▲         │
 *                    │  └─────────┘  报价被拒绝或已过期：尝试下一位候选司机
 *                    ▼
 *                UNMATCHED         半径已用尽或 30 秒 SLA 已到期
 * </pre>
 *
 * <p><strong>IN_PROGRESS 不可以被取消。</strong>乘客一旦上车，
 * 行程就必须到达 {@link #COMPLETED}；在旅程中途中止是另一套业务流程，
 * 有它自己的计费含义，并不算取消。这与 README 中的状态图保持一致，并且刻意收得很紧——
 * 一个真实的平台会增加一个独立的
 * {@code ABORTED} 状态，而不是放宽取消的口径。
 */
public enum TripStatus {

    /** 乘客已请求行程；尚未调度任何东西。 */
    REQUESTED,

    /** 匹配引擎正在搜索并认领候选司机。 */
    MATCHING,

    /** 已认领一位司机，其持有的报价很快就会过期。 */
    OFFERED,

    /** 司机已接受，正在前往上车点。 */
    ACCEPTED,

    /** 乘客已在车内。 */
    IN_PROGRESS,

    /** 行程正常结束。终态。 */
    COMPLETED,

    /** 行程在开始之前被取消。终态。 */
    CANCELLED,

    /** 在 SLA 时间窗口内未能找到司机。终态。 */
    UNMATCHED;

    private static final Map<TripStatus, Set<TripStatus>> ALLOWED_TRANSITIONS;

    static {
        // 枚举常量在静态初始化器运行之前就已完全构造完毕，因此
        // 在这里引用它们是安全的。
        Map<TripStatus, Set<TripStatus>> allowed = new EnumMap<>(TripStatus.class);
        allowed.put(REQUESTED, EnumSet.of(MATCHING, CANCELLED));
        allowed.put(MATCHING, EnumSet.of(OFFERED, UNMATCHED, CANCELLED));
        allowed.put(OFFERED, EnumSet.of(ACCEPTED, MATCHING, CANCELLED));
        allowed.put(ACCEPTED, EnumSet.of(IN_PROGRESS, CANCELLED));
        allowed.put(IN_PROGRESS, EnumSet.of(COMPLETED));
        allowed.put(COMPLETED, EnumSet.noneOf(TripStatus.class));
        allowed.put(CANCELLED, EnumSet.noneOf(TripStatus.class));
        allowed.put(UNMATCHED, EnumSet.noneOf(TripStatus.class));

        // 每个值集合都必须被单独包裹。Collections.unmodifiableMap 只会
        // 冻结映射本身——里面的 EnumSet 依然是可变的，而这张表是
        // 静态共享状态，因此一次失手的 add() 就会悄悄改写整个 JVM 的状态机。
        // TripStatusTest 证明了这一漏洞如今已被堵上。
        Map<TripStatus, Set<TripStatus>> frozen = new EnumMap<>(TripStatus.class);
        allowed.forEach((state, targets) -> frozen.put(state, Collections.unmodifiableSet(targets)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(frozen);
    }

    /** 处于该状态的行程是否可以迁移到 {@code target}。 */
    public boolean canTransitionTo(TripStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** 从当前状态一步可达的状态。永不为 null；终态时为空。 */
    public Set<TripStatus> allowedTargets() {
        return ALLOWED_TRANSITIONS.get(this);
    }

    /** 终态没有任何向外的转换；行程就此彻底结束。 */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /**
     * 处于该状态的行程是否持有司机。
     *
     * <p>这个集合恰好就是 PostgreSQL 中 {@code uniq_driver_active_trip} 唯一部分索引
     * 的谓词——正是这条约束让重复指派在物理上
     * 成为不可能（ADR-0004）。两者必须一致：一个在领域里持有司机、
     * 却落在索引谓词之外的状态，就是这个保证上的一个漏洞。
     *
     * <p>{@link #OFFERED} 被包含在内，是因为行程行会在 Redis 认领成功之后立刻写入，
     * 并且是在司机<em>接受之前</em>。这正是阻止第二个工作节点在报价窗口内
     * 插入竞争行的手段；若等到接受才写入，整个报价的 8 秒 TTL 期间这个竞态都会敞开着。
     *
     * <p>它也被用作不变式检查：处于这些状态之一却没有指派司机的行程
     * 是一条损坏的记录，而不是一条合法记录。
     */
    public boolean holdsDriver() {
        return this == OFFERED || this == ACCEPTED || this == IN_PROGRESS;
    }
}
