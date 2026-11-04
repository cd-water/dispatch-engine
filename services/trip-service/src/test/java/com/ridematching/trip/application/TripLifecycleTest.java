package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.IllegalTripTransitionException;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.events.RideCancelled;
import com.ridematching.events.RideCompleted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生命周期，尤其是行程结束时司机的去向。
 *
 * <p>在这些转换出现之前，被认领的司机从未被任何代码路径交还——{@code DriverClaimStore.release}
 * 被实现、被装配，却无人调用。司机池单调地流失，唯一把司机送回去的是认领 TTL，而那是一个
 * 崩溃安全阀，不是生命周期。因此这里断言的一半不是"状态变了"，而是"司机又可用"，
 * 因为状态从来就不是坏掉的那部分。
 */
class TripLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T10:15:00Z"), ZoneOffset.UTC);
    private static final String OFFER_TOKEN = "offer-abc";

    private FakeTripStore trips;
    private FakeClaimStore claims;
    private TripLifecycle lifecycle;

    private RideId rideId;
    private DriverId driverId;

    @BeforeEach
    void setUp() {
        trips = new FakeTripStore(CLOCK);
        claims = new FakeClaimStore();
        lifecycle = new TripLifecycle(trips, claims, new SimpleMeterRegistry(), CLOCK);

        driverId = DriverId.newId();
        rideId = new RideId(java.util.UUID.randomUUID());
    }

    /** 把行程推进到 {@code target}，司机处于匹配器认领后的状态。 */
    private void givenTripAt(TripStatus target) {
        Trip trip = Trip.request(rideId, RiderId.newId(),
                new Coordinates(30.0444, 31.2357), new Coordinates(30.0561, 31.2394),
                VehicleClass.STANDARD, CLOCK);

        if (target != TripStatus.REQUESTED) {
            trip.beginMatching();
        }
        if (target.holdsDriver()) {
            trip.offerTo(driverId, 42L, OFFER_TOKEN);
            claims.reserved(driverId, OFFER_TOKEN);
        }
        if (target == TripStatus.ACCEPTED || target == TripStatus.IN_PROGRESS) {
            trip.accept();
        }
        if (target == TripStatus.IN_PROGRESS) {
            trip.startTrip();
        }
        trips.put(trip);
    }

    @Nested
    @DisplayName("finishing a trip")
    class Completion {

        @Test
        @DisplayName("puts the driver back in the pool")
        void completionReleasesTheDriver() {
            givenTripAt(TripStatus.IN_PROGRESS);

            TripStatus status = lifecycle.complete(rideId);

            assertThat(status).isEqualTo(TripStatus.COMPLETED);
            // 真正重要的断言。没有 TripLifecycle 里的释放调用，这一行会失败，而本文件里
            // 所有状态断言仍然全部通过。
            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
            assertThat(claims.holdsClaim(driverId)).isFalse();
        }

        @Test
        @DisplayName("publishes a completion event through the outbox")
        void completionPublishesAnEvent() {
            givenTripAt(TripStatus.IN_PROGRESS);

            lifecycle.complete(rideId);

            assertThat(trips.published())
                    .singleElement()
                    .isInstanceOfSatisfying(RideCompleted.class, event -> {
                        assertThat(event.rideId()).isEqualTo(rideId.value());
                        assertThat(event.driverId()).isEqualTo(driverId.value());
                    });
        }

        @Test
        @DisplayName("cannot be completed twice")
        void completingTwiceIsRejected() {
            givenTripAt(TripStatus.IN_PROGRESS);
            lifecycle.complete(rideId);

            // COMPLETED 是终态。重复请求——司机重复点击、消息重新投递——绝不能把一个此后
            // 已被新行程认领的司机再次释放。
            assertThatThrownBy(() -> lifecycle.complete(rideId))
                    .isInstanceOf(IllegalTripTransitionException.class);
        }
    }

    @Nested
    @DisplayName("cancelling")
    class Cancellation {

        @Test
        @DisplayName("releases a driver who had been offered the trip")
        void cancellingAnOfferedTripReleasesTheDriver() {
            givenTripAt(TripStatus.OFFERED);

            lifecycle.cancel(rideId, "rider changed their mind");

            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("names the driver on the event even though the trip has let them go")
        void cancellationEventKeepsTheDriver() {
            givenTripAt(TripStatus.OFFERED);

            lifecycle.cancel(rideId, "rider changed their mind");

            // Trip.cancel() 会清空指派，所以这只在司机于转换之前被读取时才成立。
            // 消费者需要知道被撤下的是谁。
            assertThat(trips.published())
                    .singleElement()
                    .isInstanceOfSatisfying(RideCancelled.class, event -> {
                        assertThat(event.driverId()).isEqualTo(driverId.value());
                        assertThat(event.reason()).isEqualTo("rider changed their mind");
                    });
        }

        @Test
        @DisplayName("is fine when no driver was ever held")
        void cancellingWhileMatchingReleasesNothing() {
            givenTripAt(TripStatus.MATCHING);

            TripStatus status = lifecycle.cancel(rideId, "rider changed their mind");

            assertThat(status).isEqualTo(TripStatus.CANCELLED);
            assertThat(trips.published())
                    .singleElement()
                    .isInstanceOfSatisfying(RideCancelled.class,
                            event -> assertThat(event.driverId()).isNull());
        }

        @Test
        @DisplayName("is refused once the rider is in the vehicle")
        void cancellingInProgressIsRefused() {
            givenTripAt(TripStatus.IN_PROGRESS);

            assertThatThrownBy(() -> lifecycle.cancel(rideId, "changed my mind"))
                    .isInstanceOf(IllegalTripTransitionException.class);

            // 而且司机仍被持有。一次被拒绝的转换绝不能产生副作用。
            assertThat(claims.holdsClaim(driverId)).isTrue();
        }
    }

    @Nested
    @DisplayName("accepting")
    class Acceptance {

        @Test
        @DisplayName("pins the claim so it cannot expire mid-journey")
        void acceptancePinsTheClaim() {
            givenTripAt(TripStatus.OFFERED);

            lifecycle.accept(rideId);

            // 认领 TTL 是按秒级的报价来定长的。行程持续得久得多，而过期的标记会让匹配引擎
            // 不停给一个已经承诺的司机派单——每次都索引拒绝，但每次尝试都烧掉一个候选名额。
            assertThat(claims.isPinned(driverId)).isTrue();
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");
        }

        @Test
        @DisplayName("is refused when the trip is not under offer")
        void acceptingAMatchingTripIsRefused() {
            givenTripAt(TripStatus.MATCHING);

            assertThatThrownBy(() -> lifecycle.accept(rideId))
                    .isInstanceOf(IllegalTripTransitionException.class);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("a write loses if the trip moved since it was read")
        void staleWriteIsRejected() {
            givenTripAt(TripStatus.OFFERED);
            trips.bumpVersionAfterNextLoad(rideId);

            assertThatThrownBy(() -> lifecycle.accept(rideId))
                    .isInstanceOf(ConcurrentTripModificationException.class);
        }
    }

    @Nested
    @DisplayName("when Redis is unreachable")
    class RedisFailure {

        @Test
        @DisplayName("the trip still completes")
        void releaseFailureDoesNotFailTheRequest() {
            givenTripAt(TripStatus.IN_PROGRESS);
            claims.failWith(new IllegalStateException("connection refused"));

            // 完成已经提交，乘客的行程确实结束了。在这里让请求失败，等于把一趟成功的行程
            // 报告成错误；回收认领的是调和器。
            TripStatus status = lifecycle.complete(rideId);

            assertThat(status).isEqualTo(TripStatus.COMPLETED);
        }
    }

    @Test
    @DisplayName("a missing trip is not found rather than a server error")
    void unknownTripIsNotFound() {
        assertThatThrownBy(() -> lifecycle.accept(new RideId(java.util.UUID.randomUUID())))
                .isInstanceOf(TripNotFoundException.class);
    }
}
