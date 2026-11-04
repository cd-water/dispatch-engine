package com.ridematching.location.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.geoindex.DriverLocationIndex;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不带 Redis、不带 Spring、不带容器的摄取路径——这正是把索引藏在端口后面的意义
 * （ADR-0001）。毫秒级运行。
 */
class LocationIngestServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-04T10:00:00Z");
    private static final Coordinates ORIGIN = new Coordinates(30.0444, 31.2357);

    /** 记录本来会被写入什么，这样断言针对的是行为而不是 mock。 */
    private static final class RecordingIndex implements DriverLocationIndex {
        final List<DriverLocation> written = new ArrayList<>();
        final List<DriverId> online = new ArrayList<>();
        final List<DriverId> offline = new ArrayList<>();

        @Override
        public void upsertAll(List<DriverLocation> locations) {
            written.addAll(locations);
        }

        @Override
        public void markOnline(DriverId driverId) {
            online.add(driverId);
        }

        @Override
        public void markOffline(DriverId driverId) {
            offline.add(driverId);
        }

        @Override
        public List<NearbyDriver> search(Coordinates center, double radiusMeters, int limit) {
            return List.of();
        }

        @Override
        public int evictStaleOlderThan(Instant cutoff) {
            return 0;
        }

        @Override
        public long size() {
            return written.size();
        }
    }

    private RecordingIndex index;
    private LocationIngestProperties properties;
    private MeterRegistry meters;
    private LocationIngestService service;
    private final DriverId driver = DriverId.newId();

    @BeforeEach
    void setUp() {
        index = new RecordingIndex();
        properties = new LocationIngestProperties();
        meters = new SimpleMeterRegistry();
        service = new LocationIngestService(index, properties, meters,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    /** 原点以东 {@code metersEast} 米、T0 之后 {@code offset} 时刻的位置。 */
    private DriverLocation frameAt(double metersEast, Duration offset) {
        // 赤道上每度经度约 111,320 米，再按 cos(纬度) 缩放。
        double degreesPerMeter = 1.0 / (111_320.0 * Math.cos(Math.toRadians(ORIGIN.latitude())));
        Coordinates moved = new Coordinates(
                ORIGIN.latitude(), ORIGIN.longitude() + metersEast * degreesPerMeter);
        return new DriverLocation(driver, moved, 90.0, 40.0, T0.plus(offset));
    }

    private double counter(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Nested
    class Throttling {

        @Test
        @DisplayName("the first frame from a driver is always written")
        void firstFrameAlwaysAccepted() {
            assertThat(service.ingest(frameAt(0, Duration.ZERO))).isTrue();
        }

        @Test
        @DisplayName("a driver that barely moved is throttled")
        void smallMovementIsThrottled() {
            service.ingest(frameAt(0, Duration.ZERO));

            // 10 米低于 25 米阈值，且只过了 1 秒。
            assertThat(service.ingest(frameAt(10, Duration.ofSeconds(1)))).isFalse();
            assertThat(counter("location.frames.throttled")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a driver that moved far enough is written even within the interval")
        void largeMovementIsAccepted() {
            service.ingest(frameAt(0, Duration.ZERO));

            assertThat(service.ingest(frameAt(100, Duration.ofSeconds(1)))).isTrue();
        }

        @Test
        @DisplayName("a stationary driver is still written once the max interval elapses")
        void stationaryDriverStillRefreshed() {
            service.ingest(frameAt(0, Duration.ZERO));

            // 停着没动，但心跳必须继续，否则清理器会驱逐他。
            assertThat(service.ingest(frameAt(0, Duration.ofSeconds(3)))).isTrue();
        }

        @Test
        @DisplayName("throttling is per driver, not global")
        void throttlingIsPerDriver() {
            service.ingest(frameAt(0, Duration.ZERO));

            DriverId other = DriverId.newId();
            DriverLocation otherFrame = new DriverLocation(other, ORIGIN, 0, 0, T0);

            assertThat(service.ingest(otherFrame)).isTrue();
        }

        @Test
        @DisplayName("throttle state is measured from the last WRITTEN frame, not the last seen")
        void throttleAnchorsOnPersistedPosition() {
            service.ingest(frameAt(0, Duration.ZERO));
            service.ingest(frameAt(10, Duration.ofSeconds(1)));   // 被节流
            service.ingest(frameAt(20, Duration.ofSeconds(2)));   // 距原点仍 < 25 米

            // 如果锚点跟着被节流的帧移动，这次看起来就只有 10 米移动而被丢弃——司机将永远
            // 以每次 20 米的幅度漂移。
            assertThat(service.ingest(frameAt(30, Duration.ofSeconds(2)))).isTrue();
        }
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("a frame from the far past is rejected as clock skew")
        void rejectsStaleFrame() {
            DriverLocation old = new DriverLocation(driver, ORIGIN, 0, 0, T0.minusSeconds(120));

            assertThat(service.ingest(old)).isFalse();
            assertThat(counter("location.frames.rejected")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a future-dated frame is rejected too")
        void rejectsFutureFrame() {
            // 否则一个时钟走快的设备会永远赢得"最近一条"。
            DriverLocation future = new DriverLocation(driver, ORIGIN, 0, 0, T0.plusSeconds(120));

            assertThat(service.ingest(future)).isFalse();
        }
    }

    @Nested
    class Buffering {

        @Test
        @DisplayName("frames are buffered, not written on the caller's thread")
        void ingestDoesNotWriteSynchronously() {
            service.ingest(frameAt(0, Duration.ZERO));

            assertThat(index.written)
                    .as("the WebSocket read loop must not wait for Redis")
                    .isEmpty();
            assertThat(service.bufferDepth()).isEqualTo(1);
        }

        @Test
        @DisplayName("flush drains the buffer into the index")
        void flushWrites() {
            service.ingest(frameAt(0, Duration.ZERO));
            service.ingest(frameAt(100, Duration.ofSeconds(1)));

            assertThat(service.flush()).isEqualTo(2);
            assertThat(index.written).hasSize(2);
            assertThat(service.bufferDepth()).isZero();
        }

        @Test
        @DisplayName("flushing an empty buffer does nothing")
        void flushEmptyIsNoOp() {
            assertThat(service.flush()).isZero();
            assertThat(index.written).isEmpty();
        }

        @Test
        @DisplayName("a flush never exceeds the configured batch size")
        void flushRespectsBatchSize() {
            properties.setFlushBatchSize(3);
            properties.setMinMovementMeters(0);
            for (int i = 0; i < 10; i++) {
                service.ingest(new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0));
            }

            assertThat(service.flush()).isEqualTo(3);
            assertThat(service.bufferDepth()).isEqualTo(7);
        }

        @Test
        @DisplayName("a full buffer sheds the oldest frame instead of blocking")
        void fullBufferDropsOldest() {
            LocationIngestProperties tiny = new LocationIngestProperties();
            tiny.setBufferCapacity(2);
            tiny.setMinMovementMeters(0);
            LocationIngestService small = new LocationIngestService(index, tiny, meters,
                    Clock.fixed(T0, ZoneOffset.UTC));

            DriverLocation first = new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0);
            DriverLocation second = new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0);
            DriverLocation third = new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0);

            small.ingest(first);
            small.ingest(second);
            small.ingest(third);

            assertThat(small.bufferDepth()).isEqualTo(2);
            assertThat(counter("location.frames.dropped")).isEqualTo(1.0);

            small.flush();
            assertThat(index.written)
                    .as("the oldest frame is the one shed")
                    .doesNotContain(first)
                    .contains(second, third);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        @DisplayName("connect registers the driver as online")
        void connectMarksOnline() {
            service.onConnect(driver);

            assertThat(index.online).containsExactly(driver);
        }

        @Test
        @DisplayName("disconnect removes the driver and forgets its throttle state")
        void disconnectClearsState() {
            service.ingest(frameAt(0, Duration.ZERO));
            assertThat(service.trackedDrivers()).isEqualTo(1);

            service.onDisconnect(driver);

            assertThat(index.offline).containsExactly(driver);
            assertThat(service.trackedDrivers())
                    .as("stale throttle entries would leak memory across reconnects")
                    .isZero();
        }

        @Test
        @DisplayName("a reconnecting driver is not throttled against their pre-disconnect position")
        void reconnectResetsThrottle() {
            service.ingest(frameAt(0, Duration.ZERO));
            service.onDisconnect(driver);

            assertThat(service.ingest(frameAt(1, Duration.ofSeconds(1)))).isTrue();
        }
    }
}
