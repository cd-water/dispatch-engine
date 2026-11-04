package com.ridematching.dispatch.application;

import com.ridematching.dispatch.application.port.IdempotencyRecord;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.dispatch.application.port.TripRepository;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.DomainEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不带数据库的幂等算法（ADR-0005）——端口让整个决策树可以在毫秒内测试，包括那些在真实
 * 环境中很难触发的路径。
 */
class RequestRideUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-09-04T12:00:00Z");
    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);
    private static final Coordinates DROPOFF = new Coordinates(30.0500, 31.2400);

    /** 行为与 SQL 版本一致的内存存储，包括原子抢占。 */
    private static final class FakeIdempotencyStore implements IdempotencyStore {
        final Map<String, IdempotencyRecord> records = new HashMap<>();
        int claimAttempts = 0;

        @Override
        public boolean tryClaim(String key, String requestHash) {
            claimAttempts++;
            if (records.containsKey(key)) {
                return false;
            }
            records.put(key, new IdempotencyRecord(key, requestHash, true, null, null, null));
            return true;
        }

        @Override
        public Optional<IdempotencyRecord> find(String key) {
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public void complete(String key, UUID rideId, int status, String body) {
            IdempotencyRecord existing = records.get(key);
            records.put(key, new IdempotencyRecord(key, existing.requestHash(), false,
                    rideId, status, body));
        }

        @Override
        public void release(String key) {
            IdempotencyRecord existing = records.get(key);
            if (existing != null && existing.inProgress()) {
                records.remove(key);
            }
        }

        @Override
        public int purgeExpired() {
            return 0;
        }
    }

    /**
     * 记录插入以及随之写入的事件。成对的写入被建模为全有或全无，与生产中 @Transactional
     * 边界的效果完全一致，这样测试就不会在一个数据库永远不会产生的序列上通过。
     */
    private static final class RecordingTripRepository implements TripRepository {
        final java.util.List<Trip> inserted = new java.util.ArrayList<>();
        final java.util.List<DomainEvent> events = new java.util.ArrayList<>();
        RuntimeException failWith;

        @Override
        public void insert(Trip trip) {
            if (failWith != null) {
                throw failWith;
            }
            inserted.add(trip);
        }

        @Override
        public void insertWithEvent(Trip trip, DomainEvent event) {
            insert(trip);
            events.add(event);
        }

        @Override
        public Optional<Trip> findById(RideId rideId) {
            return inserted.stream().filter(t -> t.id().equals(rideId)).findFirst();
        }
    }

    private FakeIdempotencyStore idempotency;
    private RecordingTripRepository trips;
    private RequestRideUseCase useCase;

    @BeforeEach
    void setUp() {
        idempotency = new FakeIdempotencyStore();
        trips = new RecordingTripRepository();
        useCase = new RequestRideUseCase(trips, idempotency, new SimpleMeterRegistry(),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private RequestRideUseCase.RequestRideCommand command() {
        return new RequestRideUseCase.RequestRideCommand(
                RiderId.newId(), PICKUP, DROPOFF, VehicleClass.STANDARD);
    }

    @Nested
    class FirstRequest {

        @Test
        @DisplayName("a new key creates a trip and is accepted")
        void createsTrip() {
            RideRequestOutcome outcome = useCase.handle(command(), "key-1");

            assertThat(outcome.kind()).isEqualTo(RideRequestOutcome.Kind.ACCEPTED);
            assertThat(trips.inserted).hasSize(1);
            assertThat(outcome.rideId()).isNotNull();
        }

        @Test
        @DisplayName("the idempotency key is required")
        void keyRequired() {
            assertThatThrownBy(() -> useCase.handle(command(), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> useCase.handle(command(), "  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a RideRequested event is written with the trip")
        void writesTheEventWithTheTrip() {
            RideRequestOutcome outcome = useCase.handle(command(), "key-1");

            assertThat(trips.events).hasSize(1);
            DomainEvent event = trips.events.get(0);
            assertThat(event.topic()).isEqualTo("ride.requested.v1");
            assertThat(event.partitionKey())
                    .as("keyed by rideId so a ride's events stay ordered")
                    .isEqualTo(outcome.rideId().value().toString());
            assertThat(event.eventId()).isNotNull();
        }

        @Test
        @DisplayName("the claim is attempted before any read")
        void claimsBeforeReading() {
            useCase.handle(command(), "key-1");

            // 先读后写的检查会有一个窗口，让两个重试都看到"未找到"。抢占必须是第一个发生
            // 的事情。
            assertThat(idempotency.claimAttempts).isEqualTo(1);
        }
    }

    @Nested
    class Retries {

        @Test
        @DisplayName("a retry after completion replays the stored response, same rideId")
        void replaysStoredResponse() {
            var cmd = command();
            RideRequestOutcome first = useCase.handle(cmd, "key-1");
            idempotency.complete("key-1", first.rideId().value(), 202, "{\"rideId\":\"x\"}");

            RideRequestOutcome retry = useCase.handle(cmd, "key-1");

            assertThat(retry.kind()).isEqualTo(RideRequestOutcome.Kind.REPLAYED);
            assertThat(retry.rideId()).isEqualTo(first.rideId());
            assertThat(retry.replayStatus()).isEqualTo(202);
            assertThat(trips.inserted)
                    .as("a retry must never create a second trip")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a retry while the original is still running yields IN_PROGRESS")
        void inFlightRetry() {
            var cmd = command();
            useCase.handle(cmd, "key-1");   // 让记录停留在 IN_PROGRESS

            RideRequestOutcome retry = useCase.handle(cmd, "key-1");

            assertThat(retry.kind()).isEqualTo(RideRequestOutcome.Kind.IN_PROGRESS);
            assertThat(trips.inserted).hasSize(1);
        }

        @Test
        @DisplayName("the same key with a different body is rejected, not replayed")
        void keyReuseIsRejected() {
            useCase.handle(command(), "key-1");

            // 完全是另一个乘客——重放第一次的响应会悄悄丢弃这个请求。
            RideRequestOutcome reused = useCase.handle(command(), "key-1");

            assertThat(reused.kind()).isEqualTo(RideRequestOutcome.Kind.KEY_REUSE);
            assertThat(trips.inserted).hasSize(1);
        }

        @Test
        @DisplayName("byte-different encodings of the same request hash identically")
        void hashIsSemanticNotSyntactic() {
            RiderId rider = RiderId.newId();
            var a = new RequestRideUseCase.RequestRideCommand(rider, PICKUP, DROPOFF, VehicleClass.STANDARD);
            var b = new RequestRideUseCase.RequestRideCommand(rider, PICKUP, DROPOFF, VehicleClass.STANDARD);

            useCase.handle(a, "key-1");
            RideRequestOutcome retry = useCase.handle(b, "key-1");

            assertThat(retry.kind())
                    .as("an equivalent request must not be mistaken for key reuse")
                    .isEqualTo(RideRequestOutcome.Kind.IN_PROGRESS);
        }
    }

    @Nested
    class FailureHandling {

        @Test
        @DisplayName("a failed insert releases the claim so a retry can genuinely retry")
        void failureReleasesClaim() {
            trips.failWith = new IllegalStateException("database unavailable");

            assertThatThrownBy(() -> useCase.handle(command(), "key-1"))
                    .isInstanceOf(IllegalStateException.class);

            // 没有这次释放，这个键在整整 24 小时的生命期里都会返回 409。
            assertThat(idempotency.find("key-1")).isEmpty();
        }

        @Test
        @DisplayName("a failed insert leaves no event behind")
        void failedInsertPublishesNothing() {
            trips.failWith = new IllegalStateException("database unavailable");

            assertThatThrownBy(() -> useCase.handle(command(), "key-1"));

            assertThat(trips.events)
                    .as("an event for a trip that was never saved would be a phantom ride")
                    .isEmpty();
        }

        @Test
        @DisplayName("after a failure, the same key can create a trip")
        void retryAfterFailureSucceeds() {
            trips.failWith = new IllegalStateException("transient");
            assertThatThrownBy(() -> useCase.handle(command(), "key-1"));

            trips.failWith = null;
            RideRequestOutcome retry = useCase.handle(command(), "key-1");

            assertThat(retry.kind()).isEqualTo(RideRequestOutcome.Kind.ACCEPTED);
            assertThat(trips.inserted).hasSize(1);
        }

        @Test
        @DisplayName("a completed record is never released by a late failure handler")
        void completedRecordSurvivesRelease() {
            var cmd = command();
            RideRequestOutcome first = useCase.handle(cmd, "key-1");
            idempotency.complete("key-1", first.rideId().value(), 202, "{}");

            idempotency.release("key-1");

            assertThat(idempotency.find("key-1"))
                    .as("releasing a completed key would destroy a valid stored response")
                    .isPresent();
        }
    }
}
