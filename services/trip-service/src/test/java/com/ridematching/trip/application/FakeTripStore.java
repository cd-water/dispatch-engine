package com.ridematching.trip.application;

import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripSnapshot;
import com.ridematching.events.DomainEvent;
import com.ridematching.trip.application.port.TripStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存中的 {@link TripStore}，强制执行版本检查。
 *
 * <p>存快照而不是聚合，所以每次 {@code load} 都像 JDBC 适配器一样精确地重建。一个返回同一个
 * 可变对象的 fake 会掩盖"某个字段在往返中丢失"的可能性——而这正是这种形态的测试值得存在的
 * 那个具体 bug。
 */
class FakeTripStore implements TripStore {

    private final Map<RideId, TripSnapshot> rows = new HashMap<>();
    private final Map<RideId, Integer> versions = new HashMap<>();
    private final List<DomainEvent> outbox = new ArrayList<>();
    private final Clock clock;
    private RideId bumpAfterLoad;

    FakeTripStore(Clock clock) {
        this.clock = clock;
    }

    void put(Trip trip) {
        rows.put(trip.id(), trip.snapshot());
        versions.put(trip.id(), 0);
    }

    /**
     * 让另一个写入者在下一次加载与其保存之间的间隙里提交。
     *
     * <p>提前上调版本号什么也复现不了：调用方只会读到新版本并成功地写入。这里防范的丢失更新
     * 只存在于读取之后的窗口里，所以干扰必须发生在那时。
     */
    void bumpVersionAfterNextLoad(RideId rideId) {
        bumpAfterLoad = rideId;
    }

    List<DomainEvent> published() {
        return List.copyOf(outbox);
    }

    @Override
    public Optional<Versioned> load(RideId rideId) {
        Optional<Versioned> loaded = Optional.ofNullable(rows.get(rideId))
                .map(snapshot -> new Versioned(Trip.rehydrate(snapshot, clock), versions.get(rideId)));

        if (rideId.equals(bumpAfterLoad)) {
            bumpAfterLoad = null;
            versions.computeIfPresent(rideId, (id, version) -> version + 1);
        }
        return loaded;
    }

    @Override
    public void save(Trip trip, int expectedVersion, DomainEvent event) {
        int current = versions.getOrDefault(trip.id(), -1);
        if (current != expectedVersion) {
            throw new ConcurrentTripModificationException(trip.id(), expectedVersion);
        }
        rows.put(trip.id(), trip.snapshot());
        versions.put(trip.id(), current + 1);
        if (event != null) {
            outbox.add(event);
        }
    }
}
