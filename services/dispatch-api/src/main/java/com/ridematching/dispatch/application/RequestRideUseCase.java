package com.ridematching.dispatch.application;

import com.ridematching.dispatch.application.port.IdempotencyRecord;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.dispatch.application.port.TripRepository;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.RideRequested;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 按每个幂等键恰好创建一次叫车请求（ADR-0005）。
 *
 * <p>这个 API 刻意做得很薄：校验、持久化、返回。它不做任何匹配，所以延迟可预测，且与派单
 * 耗时无关。这正是让 p99 API 预算（120ms）与 p99 匹配预算（500ms）相互独立的原因。
 *
 * <p>普通构造器注入，没有 Spring 注解——整个算法都可以针对 fake 做单元测试，包括并发重试
 * 竞争。
 */
public class RequestRideUseCase {

    private final TripRepository trips;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    private final Counter created;
    private final Counter replayed;
    private final Counter inFlightConflicts;
    private final Counter keyReuse;

    /**
     * 刻意不在这里注入 {@code OutboxWriter}：事件由 {@link TripRepository#insertWithEvent}
     * 写入，从而与行程共享同一事务。单独注入写入器会诱使调用方在该边界之外追加，而这正是
     * 发件箱要防止的双重写入。
     */
    public RequestRideUseCase(TripRepository trips,
                              IdempotencyStore idempotency,
                              MeterRegistry meters,
                              Clock clock) {
        this.trips = trips;
        this.idempotency = idempotency;
        this.clock = clock;

        this.created = Counter.builder("rides.requested")
                .description("Ride requests that created a new trip").register(meters);
        this.replayed = Counter.builder("rides.idempotent.replayed")
                .description("Retries served from a stored response").register(meters);
        this.inFlightConflicts = Counter.builder("rides.idempotent.in_flight")
                .description("Retries that arrived while the original was still running").register(meters);
        this.keyReuse = Counter.builder("rides.idempotent.key_reuse")
                .description("Same key presented with a different request body").register(meters);
    }

    /**
     * 处理一条叫车请求。
     *
     * @param command     乘客提出的请求
     * @param idempotencyKey 客户端生成的键；必填
     */
    public RideRequestOutcome handle(RequestRideCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        String requestHash = hash(command);

        // 一条原子语句决定赢家。下面的一切都只是对那个决定的反应，绝不再重新检查它。
        if (idempotency.tryClaim(idempotencyKey, requestHash)) {
            return createTrip(command, idempotencyKey);
        }

        Optional<IdempotencyRecord> existing = idempotency.find(idempotencyKey);
        if (existing.isEmpty()) {
            // 这个键被抢占后，在两次调用之间过期或被释放了。这种情况罕见，按全新尝试处理
            // 是安全的。
            return idempotency.tryClaim(idempotencyKey, requestHash)
                    ? createTrip(command, idempotencyKey)
                    : RideRequestOutcome.inProgress();
        }

        IdempotencyRecord record = existing.get();

        if (!record.matches(requestHash)) {
            // 同一个键用于不同请求是客户端 bug。重放第一次的响应会悄悄丢弃这个请求；
            // 大声失败更仁慈。
            keyReuse.increment();
            return RideRequestOutcome.keyReuse();
        }

        if (record.inProgress()) {
            inFlightConflicts.increment();
            return RideRequestOutcome.inProgress();
        }

        replayed.increment();
        return RideRequestOutcome.replay(
                new RideId(record.rideId()),
                record.responseStatus(),
                record.body().orElse(null));
    }

    /** 读回一趟行程。控制器依赖这一层，绝不直接依赖仓库。 */
    public Optional<Trip> findById(RideId rideId) {
        return trips.findById(rideId);
    }

    private RideRequestOutcome createTrip(RequestRideCommand command, String idempotencyKey) {
        RideId rideId = RideId.newId();
        try {
            Trip trip = Trip.request(
                    rideId,
                    command.riderId(),
                    command.pickup(),
                    command.dropoff(),
                    command.vehicleClass(),
                    clock);

            // 行程行与发件箱行一起提交（ADR-0003）。如果改在这里直接发布到 Kafka，就会
            // 犯下双重写入的 bug：两次写入之间崩溃，要么留下一个指向不存在行程的事件，
            // 要么留下一趟无人知晓的行程。
            trips.insertWithEvent(trip, new RideRequested(
                    java.util.UUID.randomUUID(),
                    rideId.value(),
                    command.riderId().value(),
                    command.pickup().latitude(),
                    command.pickup().longitude(),
                    command.dropoff().latitude(),
                    command.dropoff().longitude(),
                    command.vehicleClass().name(),
                    clock.instant()));
            created.increment();
            return RideRequestOutcome.accepted(rideId);
        } catch (RuntimeException e) {
            // 释放抢占，让客户端重试成为真正的重试而不是永久的 409。没有这一步，一次
            // 暂时性的数据库错误就会在这个键整整 24 小时的生命期里把它毒化。
            idempotency.release(idempotencyKey);
            throw e;
        }
    }

    /**
     * 对语义上有意义的字段计算 SHA-256。
     *
     * <p>对解析后的命令而非原始字节做哈希，意味着空白和 JSON 字段顺序不影响哈希——同一个
     * 请求的两种字节不同的编码会被正确地当作同一个请求。
     */
    private String hash(RequestRideCommand command) {
        String canonical = "%s|%s,%s|%s,%s|%s".formatted(
                command.riderId(),
                command.pickup().latitude(), command.pickup().longitude(),
                command.dropoff().latitude(), command.dropoff().longitude(),
                command.vehicleClass());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }

    /** 乘客提出的请求，经过解析与校验。 */
    public record RequestRideCommand(
            RiderId riderId,
            Coordinates pickup,
            Coordinates dropoff,
            VehicleClass vehicleClass) {
    }
}
