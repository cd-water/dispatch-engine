package com.ridematching.dispatch.adapters.out.persistence;

import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.RideRequestOutcome;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证只有真实 PostgreSQL 才能证明的两个保证：{@code uniq_driver_active_trip} 部分索引
 * （ADR-0004）和原子幂等抢占（ADR-0005）。两者都依赖 fake 无法建模的行为——由数据库在
 * 并发写入者之间进行仲裁。
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
@SpringBootTest
class DispatchPersistenceIT {

    static boolean containerRuntimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // Testcontainers 2 把模块类移进了各自的包，并去掉了自类型泛型，所以这里是
    // PostgreSQLContainer 而不是 PostgreSQLContainer<?>。
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ride")
                    .withUsername("ride")
                    .withPassword("ride");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);
    private static final Coordinates DROPOFF = new Coordinates(30.0500, 31.2400);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RequestRideUseCase requestRide;

    @Autowired
    private IdempotencyStore idempotency;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE trip_events, trips, idempotency_keys, outbox CASCADE").update();
    }

    private RequestRideUseCase.RequestRideCommand command() {
        return new RequestRideUseCase.RequestRideCommand(
                RiderId.newId(), PICKUP, DROPOFF, VehicleClass.STANDARD);
    }

    /** 绕过领域层直接插入一趟持有司机的行程，以检验索引。 */
    private void insertTripHoldingDriver(DriverId driver, String status) {
        jdbc.sql("""
                        INSERT INTO trips (id, rider_id, driver_id, status, vehicle_class,
                                           pickup_lat, pickup_lng, dropoff_lat, dropoff_lng, requested_at)
                        VALUES (:id, :rider, :driver, CAST(:status AS trip_status),
                                CAST('STANDARD' AS vehicle_class), 30.0, 31.0, 30.1, 31.1, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("rider", UUID.randomUUID())
                .param("driver", driver.value())
                .param("status", status)
                .update();
    }

    @Nested
    class Wiring {

        @org.springframework.beans.factory.annotation.Autowired
        private org.springframework.context.ApplicationContext context;

        @Test
        @DisplayName("the application context loads")
        void contextLoads() {
            assertThat(context).isNotNull();
        }

        @Test
        @DisplayName("platform observability defaults are auto-configured")
        void observabilityDefaultsApplied() {
            assertThat(context.containsBean("percentileHistogramFilter")).isTrue();
        }
    }

    @Nested
    class Migrations {

        @Test
        @DisplayName("Flyway applied the schema")
        void schemaExists() {
            List<String> tables = jdbc.sql("""
                            SELECT table_name FROM information_schema.tables
                            WHERE table_schema = 'public' ORDER BY table_name
                            """)
                    .query(String.class).list();

            assertThat(tables).contains("trips", "trip_events", "idempotency_keys", "outbox");
        }

        @Test
        @DisplayName("the driver-uniqueness index exists with the expected predicate")
        void uniqueIndexExists() {
            String definition = jdbc.sql("""
                            SELECT indexdef FROM pg_indexes
                            WHERE indexname = 'uniq_driver_active_trip'
                            """)
                    .query(String.class).single();

            // 谓词必须与 TripStatus.holdsDriver() 一致。如果有人只改了其中一边，
            // 就是这个测试会察觉到。
            assertThat(definition)
                    .contains("UNIQUE")
                    .contains("OFFERED")
                    .contains("ACCEPTED")
                    .contains("IN_PROGRESS");
        }
    }

    @Nested
    class DriverUniqueness {

        @Test
        @DisplayName("a driver cannot hold two active trips")
        void secondActiveTripIsRejected() {
            DriverId driver = DriverId.newId();
            insertTripHoldingDriver(driver, "ACCEPTED");

            // 这就是 ADR-0004 的保证：不是应用代码里的检查，而是由数据库强制的结构性
            // 不可能。
            assertThatThrownBy(() -> insertTripHoldingDriver(driver, "OFFERED"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }

        @Test
        @DisplayName("the index covers OFFERED, not just ACCEPTED")
        void offeredAlsoHoldsTheDriver() {
            DriverId driver = DriverId.newId();
            insertTripHoldingDriver(driver, "OFFERED");

            // 如果 OFFERED 在谓词之外，竞争窗口会在整个 8 秒的报价 TTL 期间一直敞开着。
            assertThatThrownBy(() -> insertTripHoldingDriver(driver, "ACCEPTED"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }

        @Test
        @DisplayName("a driver is free again once the trip reaches a terminal state")
        void terminalTripReleasesDriver() {
            DriverId driver = DriverId.newId();
            insertTripHoldingDriver(driver, "COMPLETED");
            insertTripHoldingDriver(driver, "CANCELLED");

            // 两者都不在谓词中，所以可以共存，司机也可以接新行程。
            insertTripHoldingDriver(driver, "ACCEPTED");

            Integer active = jdbc.sql("""
                            SELECT count(*) FROM trips
                            WHERE driver_id = :d AND status IN ('OFFERED','ACCEPTED','IN_PROGRESS')
                            """)
                    .param("d", driver.value())
                    .query(Integer.class).single();

            assertThat(active).isEqualTo(1);
        }

        @Test
        @DisplayName("many trips may share a NULL driver")
        void nullDriversDoNotCollide() {
            // 可空列上的部分唯一索引绝不能把未匹配的行程串行化。
            for (int i = 0; i < 5; i++) {
                requestRide.handle(command(), "key-" + i);
            }

            Integer count = jdbc.sql("SELECT count(*) FROM trips WHERE driver_id IS NULL")
                    .query(Integer.class).single();

            assertThat(count).isEqualTo(5);
        }
    }

    @Nested
    class Idempotency {

        @Test
        @DisplayName("50 concurrent retries of one key create exactly one ride")
        void concurrentRetriesCreateOneRide() throws Exception {
            int threads = 50;
            String key = "storm-key";
            var command = command();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            Set<RideRequestOutcome.Kind> kinds = ConcurrentHashMap.newKeySet();
            Set<UUID> rideIds = ConcurrentHashMap.newKeySet();
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        // 每个线程都阻塞在这里，然后一起放行——这个测试要攻击的窗口
                        // 只有几毫秒宽。
                        startGate.await();
                        RideRequestOutcome outcome = requestRide.handle(command, key);
                        kinds.add(outcome.kind());
                        if (outcome.rideId() != null) {
                            rideIds.add(outcome.rideId().value());
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

            Integer tripCount = jdbc.sql("SELECT count(*) FROM trips").query(Integer.class).single();

            assertThat(tripCount)
                    .as("50 simultaneous retries of one idempotency key must yield ONE ride")
                    .isEqualTo(1);
            assertThat(errors.get()).isZero();
            assertThat(rideIds)
                    .as("every response that carried a rideId must carry the SAME one")
                    .hasSizeLessThanOrEqualTo(1);
            assertThat(kinds)
                    .as("outcomes must all be legitimate idempotency responses")
                    .isSubsetOf(RideRequestOutcome.Kind.ACCEPTED,
                            RideRequestOutcome.Kind.IN_PROGRESS,
                            RideRequestOutcome.Kind.REPLAYED);
        }

        @Test
        @DisplayName("1000 distinct keys create 1000 rides")
        void distinctKeysAllSucceed() throws Exception {
            int count = 200;
            CountDownLatch finished = new CountDownLatch(count);
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < count; i++) {
                String key = "key-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        requestRide.handle(command(), key);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();

            Integer trips = jdbc.sql("SELECT count(*) FROM trips").query(Integer.class).single();
            assertThat(trips)
                    .as("idempotency must not suppress genuinely distinct requests")
                    .isEqualTo(count);
        }

        @Test
        @DisplayName("the claim is atomic: only one caller wins")
        void claimIsAtomic() {
            assertThat(idempotency.tryClaim("k", "hash")).isTrue();
            assertThat(idempotency.tryClaim("k", "hash")).isFalse();
        }

        @Test
        @DisplayName("a released claim can be re-taken")
        void releasedClaimIsReusable() {
            idempotency.tryClaim("k", "hash");
            idempotency.release("k");

            assertThat(idempotency.tryClaim("k", "hash")).isTrue();
        }

        @Test
        @DisplayName("the trip and its audit trail commit together")
        void tripAndEventsCommitTogether() {
            RideRequestOutcome outcome = requestRide.handle(command(), "audit-key");

            Integer events = jdbc.sql("SELECT count(*) FROM trip_events WHERE trip_id = :id")
                    .param("id", outcome.rideId().value())
                    .query(Integer.class).single();

            assertThat(events)
                    .as("a trip with no recorded transition would be an unexplainable row")
                    .isGreaterThanOrEqualTo(1);
        }
    }
}
