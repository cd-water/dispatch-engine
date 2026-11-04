package com.ridematching.trip.application.port;

import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.DomainEvent;

import java.util.Optional;

/**
 * 行程聚合作为一个可变的、长期存在的对象的持久化端口（ADR-0001）。
 *
 * <p>与 dispatch-api 的 {@code TripRepository} 不同，后者永远只做插入：那个服务创建行程
 * 然后交出，给它一个自己用不到的更新方法只会招来有人用它。这个端口把行程读回来并向前写，
 * 并且每次写入都做版本检查。
 *
 * <p><strong>版本是端口的一部分，不是实现细节。</strong>一趟行程会被两类完全不同的调用方
 * 修改——匹配引擎的事件落上去的同时，乘客可能正在取消——如果端口允许调用方在不说明自己读到
 * 哪个版本的情况下写入，丢失更新就会变得不可见。加载时交回版本、保存时要求提供版本，就把
 * 这场竞争变成了一次被拒绝的写入。
 */
public interface TripStore {

    /** 一趟行程以及读取它时的版本。 */
    record Versioned(Trip trip, int version) {
    }

    Optional<Versioned> load(RideId rideId);

    /**
     * 在一个事务中写入行程的当前状态、它新的历史条目以及 {@code event}。
     *
     * <p>事件写入发件箱而不是直接写 Kafka，这样状态变更和它的宣告要么一起提交、要么都不提交
     * （ADR-0003）。当一次转换没有下游意义时传 null——{@code accept} 和 {@code start} 是真实
     * 的状态变更，但目前没有消费者，而发布没人读的事件就是一个白维护的主题。
     *
     * @param expectedVersion {@link #load} 返回的版本
     * @throws com.ridematching.trip.application.ConcurrentTripModificationException 若该行在
     *         读取之后已经发生变化
     * @throws com.ridematching.trip.application.DriverAlreadyAssignedException 若
     *         {@code uniq_driver_active_trip} 拒绝了这次写入——另一趟行程已经持有这个司机
     *         （ADR-0004）
     */
    void save(Trip trip, int expectedVersion, DomainEvent event);
}
