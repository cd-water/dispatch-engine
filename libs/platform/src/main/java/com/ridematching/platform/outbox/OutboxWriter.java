package com.ridematching.platform.outbox;

import com.ridematching.events.DomainEvent;

/**
 * 在调用方的事务中记录一个待发布的事件（ADR-0003）。
 *
 * <p>之所以存在这个抽象，是因为向 Kafka 生产消息与向 PostgreSQL 提交事务无法合并成一次原子操作。
 * 先发布，可能产生一个对应行程从未落库的事件；先提交，则可能留下一个没人被告知的已保存行程。
 * 把事件作为一行写入与状态变更相同的事务中，就消除了这个两难选择：要么两者都成功，要么都不成功。
 */
public interface OutboxWriter {

    /** 将 {@code event} 追加到发件箱。必须在调用方的事务内执行。 */
    void append(DomainEvent event);
}
