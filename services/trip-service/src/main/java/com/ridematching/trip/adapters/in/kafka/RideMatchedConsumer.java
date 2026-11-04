package com.ridematching.trip.adapters.in.kafka;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.RideMatched;
import com.ridematching.events.RideUnmatched;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.platform.messaging.ProcessedEvents;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.port.TripStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 让匹配结果变得持久。
 *
 * <p>整个并发设计都围绕这一次写入展开。匹配引擎在 Redis 中认领一个司机并宣告结果；在这个
 * 消费者运行之前，该认领就是这次指派的唯一记录，而它还会过期。到这里，指派进入 PostgreSQL，
 * 由 {@code uniq_driver_active_trip} 决定接受还是拒绝——拒绝意味着保证在生效，而不是 bug。
 *
 * <p>两层确实可能不一致。Redis 把认领授予最先发问的人；索引则拒绝已经记录在另一趟行程上的
 * 司机。Redis 被清空、租约过期、某个 worker 被暂停，都会造成快速路径说"是"而持久层说"否"。
 * 一旦发生，正确的动作是立刻把司机还回去，下面的失败分支正是这么做的——否则一个司机就会在
 * Redis 中为一次从未落到他头上的行程保持预留。
 */
public class RideMatchedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideMatchedConsumer.class);

    private final TripStore trips;
    private final DriverClaimStore claims;
    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;

    private final Counter recorded;
    private final Counter rejected;
    private final Counter unmatched;

    public RideMatchedConsumer(TripStore trips,
                               DriverClaimStore claims,
                               ObjectMapper objectMapper,
                               ProcessedEvents processed,
                               MeterRegistry meters) {
        this.trips = trips;
        this.claims = claims;
        this.objectMapper = objectMapper;
        this.processed = processed;

        this.recorded = Counter.builder("trip.assignment.recorded")
                .description("Matches made durable in PostgreSQL").register(meters);
        // 负载测试中要看的就是这一个。非零意味着 Redis 与 PostgreSQL 不一致、由索引来
        // 仲裁——正是 S3 场景设计要触发的事件。
        this.rejected = Counter.builder("trip.assignment.rejected")
                .description("Matches the unique index refused").register(meters);
        this.unmatched = Counter.builder("trip.unmatched")
                .description("Requests that found no driver").register(meters);
    }

    @KafkaListener(topics = "${ridematching.topics.ride-matched:ride.matched.v1}",
            groupId = "trip-service")
    public void onRideMatched(String payload) {
        RideMatched event = objectMapper.readValue(payload, RideMatched.class);

        if (!processed.markIfFirst(event.eventId().toString())) {
            log.debug("Skipping duplicate match event {}", event.eventId());
            return;
        }

        try {
            record(event);
        } catch (DriverAlreadyAssignedException e) {
            // 不重抛。重新投递会以完全相同的方式失败，所以永远重试只会让分区卡在一个结果
            // 已定的事件上：这趟行程拿不到这个司机。去重标记同样出于这个原因保持设置。
            rejected.increment();
            releaseLostClaim(event);
            log.info("Match for ride {} refused by the unique index; driver {} released",
                    event.rideId(), event.driverId());
        } catch (RuntimeException e) {
            // 其他任何异常都是暂时性的。忘掉标记，让重新投递成为一次真正的重试，而不是
            // 静默丢弃。
            processed.forget(event.eventId().toString());
            throw e;
        }
    }

    private void record(RideMatched event) {
        RideId rideId = new RideId(event.rideId());
        Optional<TripStore.Versioned> loaded = trips.load(rideId);
        if (loaded.isEmpty()) {
            // 匹配结果在其行程存在之前就到了，而发件箱让这不可能发生：请求事件只在行程行
            // 提交之后才发布。值得大声说出来而不是静默忽略，因为一旦发生，就说明发件箱的
            // 顺序假设已经被打破。
            log.warn("Match for unknown ride {}; ignoring", event.rideId());
            return;
        }

        Trip trip = loaded.get().trip();
        // 匹配引擎消费了请求，所以这里行程仍是 REQUESTED——MATCHING 是搜索运行期间它所在
        // 的状态，而搜索发生在另一个服务里。记录它，让审计追踪诚实地反映实际发生了什么。
        trip.beginMatching();
        trip.offerTo(new DriverId(event.driverId()), event.fenceToken(), event.offerToken());

        trips.save(trip, loaded.get().version(), null);
        recorded.increment();
    }

    /**
     * 交还一个索引不肯让这趟行程得到的司机。
     *
     * <p>没有这一步，司机会在 Redis 中保持 RESERVED 直到认领 TTL 失效，期间无法被报价给
     * 任何人——一个被没有真正发生的匹配移出池子的司机。
     */
    private void releaseLostClaim(RideMatched event) {
        try {
            claims.release(new DriverId(event.driverId()), event.offerToken());
        } catch (RuntimeException e) {
            log.warn("Could not release claim for driver {}: {}", event.driverId(), e.toString());
        }
    }

    @KafkaListener(topics = "${ridematching.topics.ride-unmatched:ride.unmatched.v1}",
            groupId = "trip-service")
    public void onRideUnmatched(String payload) {
        RideUnmatched event = objectMapper.readValue(payload, RideUnmatched.class);

        if (!processed.markIfFirst(event.eventId().toString())) {
            return;
        }

        try {
            RideId rideId = new RideId(event.rideId());
            trips.load(rideId).ifPresent(loaded -> {
                Trip trip = loaded.trip();
                trip.beginMatching();
                trip.markUnmatched(event.reason());
                trips.save(trip, loaded.version(), null);
                unmatched.increment();
            });
        } catch (RuntimeException e) {
            processed.forget(event.eventId().toString());
            throw e;
        }
    }
}
