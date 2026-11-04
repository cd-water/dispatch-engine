package com.ridematching.trip.adapters.out.persistence;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripSnapshot;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.domain.trip.TripTransition;
import com.ridematching.events.DomainEvent;
import com.ridematching.platform.outbox.OutboxWriter;
import com.ridematching.trip.application.ConcurrentTripModificationException;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.port.TripStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 行程生命周期的 PostgreSQL 持久化。
 *
 * <p>手工映射而非 JPA，原因与系统其余部分一致：承载并发保证的 SQL 应当可读，而不是被生成。
 * 这里没有任何东西是可有可无的——{@code WHERE version} 子句和 {@code DuplicateKeyException}
 * 分支都是承重结构。
 */
public class JdbcTripStore implements TripStore {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final Clock clock;

    public JdbcTripStore(JdbcClient jdbc, OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public Optional<Versioned> load(RideId rideId) {
        return jdbc.sql("""
                        SELECT id, rider_id, driver_id, status, vehicle_class, fence_token,
                               offer_token, pickup_lat, pickup_lng, dropoff_lat, dropoff_lng,
                               requested_at, matched_at, completed_at, version
                        FROM trips
                        WHERE id = :id
                        """)
                .param("id", rideId.value())
                .query((rs, rowNum) -> new Versioned(
                        Trip.rehydrate(toSnapshot(rs), clock), rs.getInt("version")))
                .optional();
    }

    @Override
    @Transactional
    public void save(Trip trip, int expectedVersion, DomainEvent event) {
        TripSnapshot state = trip.snapshot();
        int updated;
        try {
            updated = jdbc.sql("""
                            UPDATE trips SET
                                driver_id    = :driverId,
                                status       = CAST(:status AS trip_status),
                                fence_token  = :fenceToken,
                                offer_token  = :offerToken,
                                matched_at   = :matchedAt,
                                completed_at = :completedAt,
                                version      = version + 1
                            WHERE id = :id AND version = :expectedVersion
                            """)
                    .param("driverId", state.driverId() == null ? null : state.driverId().value())
                    .param("status", state.status().name())
                    .param("fenceToken", state.fenceToken())
                    .param("offerToken", state.offerToken())
                    .param("matchedAt", timestamp(state.matchedAt()))
                    .param("completedAt", timestamp(state.completedAt()))
                    .param("id", state.id().value())
                    .param("expectedVersion", expectedVersion)
                    .update();
        } catch (DuplicateKeyException e) {
            // uniq_driver_active_trip：另一趟行程已经持有这个司机。这是 ADR-0004 的保证在
            // 生效，也是本服务中唯一可能发生的地方。
            throw new DriverAlreadyAssignedException(
                    "Driver %s already holds an active trip".formatted(state.driverId()), e);
        }

        if (updated == 0) {
            // 更新零行意味着版本号被改动了，而不是行程消失了：调用方刚刚在同一个用例里读过
            // 它。用额外一次 SELECT 来区分两者，只会换来更好的错误信息和在争用路径上的第二
            // 次往返，这是一笔不划算的交换。
            throw new ConcurrentTripModificationException(state.id(), expectedVersion);
        }

        // 只处理自行程重建以来发生的转换。Trip.rehydrate 有意不恢复历史，因此这里追加新
        // 的条目，而不是在每次状态变更时重新插入整条审计追踪。
        for (TripTransition transition : trip.history()) {
            insertEvent(state.id(), transition);
        }

        if (event != null) {
            // 与状态变更处于同一事务。这正是全部意义所在（ADR-0003）。
            outbox.append(event);
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

    private static TripSnapshot toSnapshot(ResultSet rs) throws SQLException {
        UUID driverId = rs.getObject("driver_id", UUID.class);

        // 对 SQL NULL，getLong 返回 0，而 0 不是合法的围栏令牌——领域层会拒绝它。wasNull()
        // 是区分两者的唯一办法，且它报告的是最近一次读取，因此必须在这里消费，而不能放到
        // 下面构造参数中间——那里后面的列会覆盖掉答案。
        long fence = rs.getLong("fence_token");
        Long fenceToken = rs.wasNull() ? null : fence;

        return new TripSnapshot(
                new RideId(rs.getObject("id", UUID.class)),
                new RiderId(rs.getObject("rider_id", UUID.class)),
                new Coordinates(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
                new Coordinates(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lng")),
                VehicleClass.valueOf(rs.getString("vehicle_class")),
                TripStatus.valueOf(rs.getString("status")),
                driverId == null ? null : new DriverId(driverId),
                fenceToken,
                rs.getString("offer_token"),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("matched_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
