package com.ridematching.matching.adapters.in.kafka;

import com.ridematching.domain.geo.Coordinates;
import com.ridematching.events.RideMatched;
import com.ridematching.events.RideUnmatched;
import com.ridematching.events.RideRequested;
import com.ridematching.matching.application.DriverMatcher;
import com.ridematching.platform.messaging.ProcessedEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * 消费叫车请求并运行匹配循环。
 *
 * <p>请求路径与派单路径最终在这里分道扬镳：API 早就返回了 {@code 202}，这里的一切都按自己
 * 的节奏、在自己的消费者组里进行。
 */
public class RideRequestedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideRequestedConsumer.class);

    private final DriverMatcher matcher;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;
    private final Clock clock;

    public RideRequestedConsumer(DriverMatcher matcher,
                                 KafkaTemplate<String, String> kafka,
                                 ObjectMapper objectMapper,
                                 ProcessedEvents processed,
                                 Clock clock) {
        this.matcher = matcher;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.clock = clock;
    }

    @KafkaListener(topics = "${ridematching.topics.ride-requested:ride.requested.v1}",
            groupId = "matching-engine")
    public void onRideRequested(String payload) {
        RideRequested request = objectMapper.readValue(payload, RideRequested.class);

        // 投递是至少一次的，所以重新投递的请求绝不能为一趟已经有司机的行程再认领第二个司机。
        if (!processed.markIfFirst(request.eventId().toString())) {
            log.debug("Skipping duplicate ride request event {}", request.eventId());
            return;
        }

        try {
            handle(request);
        } catch (RuntimeException e) {
            // 撤销标记，让重新投递真正重试。保留标记会把一次暂时性失败变成一趟永久丢失的
            // 行程。
            processed.forget(request.eventId().toString());
            throw e;
        }
    }

    private void handle(RideRequested request) {
        Coordinates pickup = new Coordinates(request.pickupLat(), request.pickupLng());
        // 报价令牌由行程派生，而不是随机的，所以同一趟行程的重试会产生相同的令牌，并能
        // 认出自己先前的认领。
        String offerToken = "offer-" + request.rideId();

        Optional<DriverMatcher.MatchedDriver> match = matcher.match(pickup, offerToken);

        if (match.isPresent()) {
            DriverMatcher.MatchedDriver driver = match.get();
            publish(new RideMatched(
                    UUID.randomUUID(),
                    request.rideId(),
                    driver.driverId().value(),
                    driver.fenceToken(),
                    driver.offerToken(),
                    driver.distanceMeters(),
                    clock.instant()));
        } else {
            // 发布而不是丢弃：请求悄悄蒸发的乘客处境，比被告知没有可用司机的乘客更糟。
            publish(new RideUnmatched(
                    UUID.randomUUID(),
                    request.rideId(),
                    "no claimable driver within the configured radii",
                    clock.instant()));
        }
    }

    private void publish(com.ridematching.events.DomainEvent event) {
        kafka.send(event.topic(), event.partitionKey(), objectMapper.writeValueAsString(event));
    }
}
