package com.ridematching.dispatch.adapters.out.persistence;

import com.ridematching.dispatch.application.DriverAlreadyAssignedException;
import com.ridematching.platform.outbox.OutboxWriter;
import com.ridematching.dispatch.application.port.TripRepository;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.domain.trip.TripTransition;
import com.ridematching.events.DomainEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * 行程聚合的 PostgreSQL 持久化。
 *
 * <p>手工把领域对象映射到列，而不是通过 JPA。聚合保持为一个没有注解的普通对象（ADR-0001），
 * 映射住在它该在的地方——这也意味着承载并发保证的 SQL 是可见的，而不是被生成的。
 */
public class JdbcTripRepository implements TripRepository {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public JdbcTripRepository(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public void insertWithEvent(Trip trip, DomainEvent event) {
        // 两次写入都在同一个 @Transactional 边界内经由同一个 JdbcClient，所以它们共享连接，
        // 一起提交或一起回滚。
        insert(trip);
        outbox.append(event);
    }

    @Override
    @Transactional
    public void insert(Trip trip) {
        try {
            jdbc.sql("""
                            INSERT INTO trips
                                (id, rider_id, driver_id, status, vehicle_class, fence_token,
                                 pickup_lat, pickup_lng, dropoff_lat, dropoff_lng,
                                 requested_at, matched_at, completed_at, version)
                            VALUES
                                (:id, :riderId, :driverId, CAST(:status AS trip_status),
                                 CAST(:vehicleClass AS vehicle_class), :fenceToken,
                                 :pickupLat, :pickupLng, :dropoffLat, :dropoffLng,
                                 :requestedAt, :matchedAt, :completedAt, 0)
                            """)
                    .param("id", trip.id().value())
                    .param("riderId", trip.riderId().value())
                    .param("driverId", trip.assignedDriver().map(driver -> driver.value()).orElse(null))
                    .param("status", trip.status().name())
                    .param("vehicleClass", trip.vehicleClass().name())
                    .param("fenceToken", trip.fenceToken().orElse(null))
                    .param("pickupLat", trip.pickup().latitude())
                    .param("pickupLng", trip.pickup().longitude())
                    .param("dropoffLat", trip.dropoff().latitude())
                    .param("dropoffLng", trip.dropoff().longitude())
                    .param("requestedAt", Timestamp.from(trip.requestedAt()))
                    .param("matchedAt", trip.matchedAt().map(Timestamp::from).orElse(null))
                    .param("completedAt", trip.completedAt().map(Timestamp::from).orElse(null))
                    .update();
        } catch (DuplicateKeyException e) {
            // 要么是行程 id 冲突（对 UUIDv4 来说实际上不可能），要么是
            // uniq_driver_active_trip 触发了。后者是 ADR-0004 的保证在履职，在争用下是
            // 预期之内的，所以给它独立的类型，而不是作为通用的持久化失败露出来。
            throw new DriverAlreadyAssignedException(
                    "Trip %s could not be inserted; a driver may already hold an active trip"
                            .formatted(trip.id()), e);
        }

        // 与行程行同属一个事务：审计追踪不可能与状态脱节。
        for (TripTransition transition : trip.history()) {
            insertEvent(trip.id(), transition);
        }
    }

    private void insertEvent(RideId rideId, TripTransition transition) {
        jdbc.sql("""
                        INSERT INTO trip_events
                            (trip_id, from_status, to_status, driver_id, reason, occurred_at)
                        VALUES
                            (:tripId, CAST(:fromStatus AS trip_status),
                             CAST(:toStatus AS trip_status), :driverId, :reason, :occurredAt)
                        """)
                .param("tripId", rideId.value())
                .param("fromStatus", transition.previousState().map(status -> status.name()).orElse(null))
                .param("toStatus", transition.to().name())
                .param("driverId", transition.driver().map(driver -> driver.value()).orElse(null))
                .param("reason", transition.reason())
                .param("occurredAt", Timestamp.from(transition.occurredAt()))
                .update();
    }

    @Override
    public Optional<Trip> findById(RideId rideId) {
        return jdbc.sql("""
                        SELECT id, rider_id, driver_id, status, vehicle_class,
                               pickup_lat, pickup_lng, dropoff_lat, dropoff_lng, requested_at
                        FROM trips
                        WHERE id = :id
                        """)
                .param("id", rideId.value())
                .query((rs, rowNum) -> {
                    // 经由领域工厂重建，这样无效的行无法产生无效的聚合——记录自身的校验
                    // 会在回程中运行。
                    Trip trip = Trip.request(
                            new RideId(rs.getObject("id", UUID.class)),
                            new RiderId(rs.getObject("rider_id", UUID.class)),
                            new Coordinates(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
                            new Coordinates(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lng")),
                            VehicleClass.valueOf(rs.getString("vehicle_class")),
                            java.time.Clock.systemUTC());
                    return new StoredTrip(
                            trip,
                            TripStatus.valueOf(rs.getString("status")),
                            rs.getObject("driver_id", UUID.class));
                })
                .optional()
                .map(stored -> stored.trip());
    }

    /**
     * 读回一行时的中间载体。
     *
     * <p>把已存储的状态回放到聚合上这件事，推迟到行程生命周期迁移到 trip-service（它拥有
     * 状态转换）时再做。目前这个 API 只会读回它刚刚创建的行程，全都处于 REQUESTED，所以还
     * 不需要重建历史——这里记下而不是默默忽略。
     */
    private record StoredTrip(Trip trip, TripStatus status, UUID driverId) {
    }
}
