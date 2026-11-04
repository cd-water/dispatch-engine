package com.ridematching.trip;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import com.ridematching.trip.application.ClaimReconciler;
import com.ridematching.domain.trip.IllegalTripTransitionException;
import com.ridematching.trip.application.ConcurrentTripModificationException;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.TripLifecycle;
import com.ridematching.trip.application.port.TripStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 针对真实数据库与真实 Redis 的生命周期测试。
 *
 * <p>有三件事无法用 fake 来测，而它们正是设计所依赖的：{@code uniq_driver_active_trip} 在
 * 物理上拒绝为同一司机再开第二趟活跃行程、Lua 释放脚本只把认领释放给拥有它的令牌、以及调和器
 * 能区分滞留的认领与仅仅是在途的认领。这三样都活在引擎里，而不是 Java 里。
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
@SpringBootTest
class TripLifecycleIT {

    static boolean containerRuntimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // @SuppressWarnings("resource") 是必需的，不是装饰：编译器看到赋给字段的 Closeable，
    // 且从未被关闭。Testcontainers 的 JUnit 扩展掌管 @Container 字段的生命周期，并在整个
    // 测试类结束后停止它们，所以不存在泄漏。
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ride").withUsername("ride").withPassword("ride");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // 把两个后台循环都停到很远。这些测试直接驱动生命周期，并在用例之间截断表；一个
        // 在同一批表上轮询的发布者在 TRUNCATE 期间会失败，回滚错误与受测代码毫无关系。
        // 当调和器本身是被测对象时，测试会显式调用它。
        registry.add("ridematching.trip.outbox-interval", () -> "PT1H");
        registry.add("ridematching.trip.reconcile-interval", () -> "PT1H");
    }

    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);
    private static final Coordinates DROPOFF = new Coordinates(30.0561, 31.2394);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TripStore trips;

    @Autowired
    private TripLifecycle lifecycle;

    @Autowired
    private ClaimReconciler reconciler;

    @Autowired
    private DriverClaimStore claims;

    @Autowired
    private RedisKeys keys;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE trip_events, trips, idempotency_keys, outbox CASCADE").update();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** 按 dispatch-api 的方式插入一趟 REQUESTED 行程，并返回其 id。 */
    private RideId givenRequestedTrip() {
        Trip trip = Trip.request(new RideId(UUID.randomUUID()), RiderId.newId(),
                PICKUP, DROPOFF, VehicleClass.STANDARD, clock);
        jdbc.sql("""
                        INSERT INTO trips (id, rider_id, status, vehicle_class,
                                           pickup_lat, pickup_lng, dropoff_lat, dropoff_lng,
                                           requested_at, version)
                        VALUES (:id, :riderId, CAST('REQUESTED' AS trip_status),
                                CAST('STANDARD' AS vehicle_class),
                                :pickupLat, :pickupLng, :dropoffLat, :dropoffLng, :requestedAt, 0)
                        """)
                .param("id", trip.id().value())
                .param("riderId", trip.riderId().value())
                .param("pickupLat", PICKUP.latitude())
                .param("pickupLng", PICKUP.longitude())
                .param("dropoffLat", DROPOFF.latitude())
                .param("dropoffLng", DROPOFF.longitude())
                .param("requestedAt", Timestamp.from(trip.requestedAt()))
                .update();
        return trip.id();
    }

    /** 在 Redis 中把司机置为 AVAILABLE，然后为这趟行程认领他。 */
    private DriverId givenClaimedDriver(RideId rideId) {
        DriverId driverId = DriverId.newId();
        redis.opsForHash().put(keys.driverState(driverId), "status", "AVAILABLE");

        String offerToken = "offer-" + rideId;
        var claim = claims.claim(driverId, offerToken, java.time.Duration.ofSeconds(8));
        assertThat(claim.won()).isTrue();

        TripStore.Versioned loaded = trips.load(rideId).orElseThrow();
        Trip trip = loaded.trip();
        trip.beginMatching();
        trip.offerTo(driverId, claim.fenceToken(), offerToken);
        trips.save(trip, loaded.version(), null);
        return driverId;
    }

    @Nested
    @DisplayName("the full journey")
    class HappyPath {

        @Test
        @DisplayName("accept, start, complete — and the driver comes back")
        void driverIsReturnedToThePool() {
            RideId rideId = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(rideId);

            assertThat(lifecycle.accept(rideId)).isEqualTo(TripStatus.ACCEPTED);
            // 固定住了：报价 TTL 已消失，所以现在只有显式释放才能让他回到池中。
            assertThat(redis.getExpire(keys.claim(driverId))).isEqualTo(-1);
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");

            assertThat(lifecycle.start(rideId)).isEqualTo(TripStatus.IN_PROGRESS);
            assertThat(lifecycle.complete(rideId)).isEqualTo(TripStatus.COMPLETED);

            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
            assertThat(redis.hasKey(keys.claim(driverId))).isFalse();
        }

        @Test
        @DisplayName("writes the audit trail and the outbox row in the same transaction")
        void completionIsDurableAndAnnounced() {
            RideId rideId = givenRequestedTrip();
            givenClaimedDriver(rideId);
            lifecycle.accept(rideId);
            lifecycle.start(rideId);
            lifecycle.complete(rideId);

            Integer events = jdbc.sql(
                            "SELECT count(*) FROM trip_events WHERE trip_id = :id AND to_status = 'COMPLETED'")
                    .param("id", rideId.value()).query(Integer.class).single();
            Integer outbox = jdbc.sql(
                            "SELECT count(*) FROM outbox WHERE topic = 'ride.completed.v1'")
                    .query(Integer.class).single();

            assertThat(events).isEqualTo(1);
            assertThat(outbox).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the unique index")
    class TheGuarantee {

        @Test
        @DisplayName("refuses to let one driver hold two active trips")
        void secondActiveTripForOneDriverIsRejected() {
            RideId first = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(first);
            lifecycle.accept(first);

            // 第二趟行程试图抢同一个司机。Redis 会允许——在许多真实的故障模式下，认领会被
            // 释放并重新取得——但索引才是边界，这个尝试必须死在这里。
            RideId second = givenRequestedTrip();
            TripStore.Versioned loaded = trips.load(second).orElseThrow();
            Trip trip = loaded.trip();
            trip.beginMatching();
            trip.offerTo(driverId, 9999L, "offer-" + second);

            assertThatThrownBy(() -> trips.save(trip, loaded.version(), null))
                    .isInstanceOf(DriverAlreadyAssignedException.class);
        }

        @Test
        @DisplayName("lets the driver be reused once the first trip has finished")
        void driverIsReusableAfterCompletion() {
            RideId first = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(first);
            lifecycle.accept(first);
            lifecycle.start(first);
            lifecycle.complete(first);

            RideId second = givenRequestedTrip();
            TripStore.Versioned loaded = trips.load(second).orElseThrow();
            Trip trip = loaded.trip();
            trip.beginMatching();
            trip.offerTo(driverId, 9999L, "offer-" + second);

            // COMPLETED 在索引谓词之外，所以这一行不再持有司机。
            // 谓词与 TripStatus.holdsDriver() 保持一致，正是这行代码能工作的原因。
            trips.save(trip, loaded.version(), null);
            assertThat(trips.load(second).orElseThrow().trip().status()).isEqualTo(TripStatus.OFFERED);
        }
    }

    @Nested
    @DisplayName("contention")
    class Contention {

        @Test
        @DisplayName("only one of many simultaneous accepts wins")
        void concurrentAcceptsProduceExactlyOneWinner() throws Exception {
            RideId rideId = givenRequestedTrip();
            givenClaimedDriver(rideId);

            int threads = 32;
            var start = new java.util.concurrent.CountDownLatch(1);
            var winners = new java.util.concurrent.atomic.AtomicInteger();
            var losers = new java.util.concurrent.atomic.AtomicInteger();
            var unexpected = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            // 一起放行，所以这些加载真正重叠。错开启动会让每个请求在下一个
                            // 开始前就结束，测试即使从未触发竞争也会通过。
                            start.await();
                            lifecycle.accept(rideId);
                            winners.incrementAndGet();
                        } catch (ConcurrentTripModificationException
                                 | IllegalTripTransitionException expected) {
                            // 同一次落败的两种形态。落后者是被版本检查拒绝，还是被状态机
                            // 拒绝，取决于它是在赢家提交之前还是之后读取的，两者都正确。
                            losers.incrementAndGet();
                        } catch (Throwable t) {
                            unexpected.add(t);
                        }
                    });
                }
                start.countDown();
            }

            assertThat(unexpected).isEmpty();
            assertThat(winners.get()).isEqualTo(1);
            assertThat(losers.get()).isEqualTo(threads - 1);

            // 而数据库也同意：只有一次接受，而不是三十二次。
            Integer acceptances = jdbc.sql("""
                            SELECT count(*) FROM trip_events
                            WHERE trip_id = :id AND to_status = 'ACCEPTED'
                            """)
                    .param("id", rideId.value()).query(Integer.class).single();
            assertThat(acceptances).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the reconciler")
    class Reconciliation {

        @Test
        @DisplayName("frees a driver stranded by a crash after the trip finished")
        void strandedClaimIsReclaimed() {
            RideId rideId = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(rideId);
            lifecycle.accept(rideId);
            lifecycle.start(rideId);

            // 直接在数据库里完成行程，而不走生命周期：这正是提交与释放之间崩溃后会留下的
            // 状态。认领已经被固定，所以永远不会有 TTL 来清理它。
            jdbc.sql("UPDATE trips SET status = CAST('COMPLETED' AS trip_status) WHERE id = :id")
                    .param("id", rideId.value()).update();
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");

            assertThat(reconciler.reconcile()).isEqualTo(1);
            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("leaves an offer in flight alone")
        void unexpiredClaimIsNotTouched() {
            // 一个刚被取走、匹配尚未记录的认领：它仍带 TTL，也没有任何行程行持有该司机。
            // 只看数据库的调和器会释放一个即将被合法指派的司机。
            DriverId driverId = DriverId.newId();
            redis.opsForHash().put(keys.driverState(driverId), "status", "AVAILABLE");
            claims.claim(driverId, "offer-in-flight", java.time.Duration.ofSeconds(30));

            assertThat(reconciler.reconcile()).isZero();
            assertThat(claims.statusOf(driverId)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("leaves a driver who is genuinely on a trip alone")
        void activeTripIsNotReclaimed() {
            RideId rideId = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(rideId);
            lifecycle.accept(rideId);

            assertThat(reconciler.reconcile()).isZero();
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");
        }
    }
}
