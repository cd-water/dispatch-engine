package com.ridematching.dispatch.application.port;

import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.DomainEvent;

import java.util.Optional;

/** 行程聚合的持久化端口（ADR-0001）。 */
public interface TripRepository {

    /**
     * 在一个事务中插入一趟新请求的行程及其初始历史条目。
     *
     * @throws com.ridematching.dispatch.application.DriverAlreadyAssignedException 若
     *         {@code uniq_driver_active_trip} 索引拒绝了写入——ADR-0004 的结构性保证在生效
     */
    void insert(Trip trip);

    /**
     * 原子地插入一趟行程，并记录 {@code event} 供发布。
     *
     * <p>一个事务，两次写入。这就是发件箱的全部意义：当"保存"和"发布"是两次独立提交时，
     * 不存在任何一种安全的先后顺序，所以把它们合并成一次（ADR-0003）。
     */
    void insertWithEvent(Trip trip, DomainEvent event);

    Optional<Trip> findById(RideId rideId);
}
