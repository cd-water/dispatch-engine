package com.ridematching.location.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.geoindex.DriverLocationIndex;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摄取热点路径：校验、节流、缓冲、刷写。
 *
 * <p>目标负载下大约每秒有 10,000 帧经过这里，所以每帧的工作被刻意做得很少——一次边界检查、
 * 一次距离计算、一次入队。Redis 往返发生在单独的刷写里，绝不在调用方线程上。
 *
 * <p>用普通参数构造而不是注解注入，这样它可以在没有 Spring 上下文的情况下，针对 fake 索引和
 * {@code SimpleMeterRegistry} 做单元测试；Spring 装配住在 {@link LocationIngestConfiguration}。
 */
public class LocationIngestService {

    private final DriverLocationIndex index;
    private final LocationIngestProperties properties;
    private final Clock clock;

    /**
     * 每个司机最后一次真正写入的位置。
     *
     * <p>按实例持有而非共享，这是正确的，因为一个司机只连一个实例的 WebSocket：该实例能看到
     * 这个司机的每一帧，而其他节点一帧都看不到。共享缓存会在系统最热的路径上增加一次网络
     * 往返，只为同步一个只有单一节点能观察到的状态。
     */
    private final Map<DriverId, DriverLocation> lastPersisted = new ConcurrentHashMap<>();

    private final BlockingQueue<DriverLocation> buffer;

    private final Counter accepted;
    private final Counter throttled;
    private final Counter rejectedSkew;
    private final Counter dropped;
    private final Timer flushTimer;

    public LocationIngestService(DriverLocationIndex index,
                                 LocationIngestProperties properties,
                                 MeterRegistry meters,
                                 Clock clock) {
        this.index = index;
        this.properties = properties;
        this.clock = clock;
        this.buffer = new ArrayBlockingQueue<>(properties.getBufferCapacity());

        this.accepted = Counter.builder("location.frames.accepted")
                .description("Frames queued for persistence").register(meters);
        this.throttled = Counter.builder("location.frames.throttled")
                .description("Frames skipped because the driver barely moved").register(meters);
        this.rejectedSkew = Counter.builder("location.frames.rejected")
                .tag("reason", "clock_skew").register(meters);
        this.dropped = Counter.builder("location.frames.dropped")
                .description("Frames shed because the write buffer was full").register(meters);
        this.flushTimer = Timer.builder("location.ingest.duration")
                .description("Time to flush one batch to the index").register(meters);

        // 显式 lambda 而非 Collection::size。方法引用会把队列作为接收者传入，而 IDE 的空值
        // 分析无法证明它满足 Micrometer 的 @NonNull 函数式描述符——这是一个误报，但一个永远
        // 存在的警告会教人不再看警告面板。
        Gauge.builder("location.buffer.depth", this.buffer, queue -> (double) queue.size())
                .description("Frames waiting to be written to the index")
                .register(meters);
    }

    /**
     * 处理一帧。返回它是否被排入持久化队列。
     *
     * <p>缓冲区满时绝不阻塞、绝不抛异常——调用方是 WebSocket 读取循环，卡住它等于向一个
     * 本来就无法放慢的司机施加背压。
     */
    public boolean ingest(DriverLocation frame) {
        if (frame.isSkewedBeyond(clock.instant(), properties.getMaxClockSkew())) {
            rejectedSkew.increment();
            return false;
        }

        if (shouldThrottle(frame)) {
            throttled.increment();
            return false;
        }

        // 在入队时而不是刷写时记录为已持久化。否则在一个司机的批量刷写尚未完成期间，
        // 一连串帧会全部通过节流。
        lastPersisted.put(frame.driverId(), frame);

        if (!buffer.offer(frame)) {
            // 丢弃最旧：被丢弃的帧按定义比这一帧更陈旧。
            buffer.poll();
            dropped.increment();
            buffer.offer(frame);
        }

        accepted.increment();
        return true;
    }

    private boolean shouldThrottle(DriverLocation frame) {
        DriverLocation previous = lastPersisted.get(frame.driverId());
        if (previous == null) {
            return false;
        }

        boolean intervalElapsed = !java.time.Duration
                .between(previous.recordedAt(), frame.recordedAt())
                .minus(properties.getMaxWriteInterval())
                .isNegative();
        if (intervalElapsed) {
            return false;
        }

        return frame.distanceMetersTo(previous) < properties.getMinMovementMeters();
    }

    /** 排空最多一批并写入。返回写入的帧数。 */
    public int flush() {
        List<DriverLocation> batch = new ArrayList<>(properties.getFlushBatchSize());
        buffer.drainTo(batch, properties.getFlushBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        flushTimer.record(() -> index.upsertAll(batch));
        return batch.size();
    }

    /** 一位司机连接了。 */
    public void onConnect(DriverId driverId) {
        index.markOnline(driverId);
    }

    /** 一位司机断开了：忘掉他的节流状态，并把他从索引中移除。 */
    public void onDisconnect(DriverId driverId) {
        lastPersisted.remove(driverId);
        index.markOffline(driverId);
    }

    /** 等待写入的帧数。 */
    public int bufferDepth() {
        return buffer.size();
    }

    /** 本实例为节流而跟踪的司机数。 */
    public int trackedDrivers() {
        return lastPersisted.size();
    }
}
