package com.ridematching.dispatch.adapters.in.rest;

import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.RideRequestOutcome;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * 叫车请求入口。
 *
 * <p>返回 {@code 202 Accepted}，而不是阻塞等到找到司机。这是系统中关键的延迟决策：无论匹配
 * 耗时多久，API 的 p99 都保持在数十毫秒，缓慢的派单永远不会变成请求超时。
 */
@RestController
@RequestMapping("/v1/rides")
public class RideController {

    private final RequestRideUseCase requestRide;
    private final IdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public RideController(RequestRideUseCase requestRide,
                          IdempotencyStore idempotency,
                          ObjectMapper objectMapper) {
        this.requestRide = requestRide;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> requestRide(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RideRequestDto request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST,
                    "Missing Idempotency-Key",
                    "Ride creation requires a client-generated Idempotency-Key header."));
        }

        var command = new RequestRideUseCase.RequestRideCommand(
                new RiderId(request.riderId()),
                new Coordinates(request.pickupLat(), request.pickupLng()),
                new Coordinates(request.dropoffLat(), request.dropoffLng()),
                parseVehicleClass(request.vehicleClass()));

        RideRequestOutcome outcome = requestRide.handle(command, idempotencyKey);

        return switch (outcome.kind()) {
            case ACCEPTED -> accepted(outcome.rideId(), idempotencyKey);

            // 原始请求仍在运行。Retry-After 告诉客户端等待而不是猛敲，这正是把重试风暴
            // 变成排队的原因。
            case IN_PROGRESS -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .header("Retry-After", "1")
                    .body(problem(HttpStatus.CONFLICT, "Request in progress",
                            "A request with this Idempotency-Key is still being processed."));

            // 原样重放已存储的响应——客户端拿到的是第一次就会拿到的同一个 rideId，这正是
            // 它继续流程所需要的。
            case REPLAYED -> ResponseEntity.status(HttpStatus.OK)
                    .body(RideResponseDto.accepted(outcome.rideId().value()));

            case KEY_REUSE -> ResponseEntity.unprocessableContent()
                    .body(problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused",
                            "This Idempotency-Key was already used for a different request."));
        };
    }

    private ResponseEntity<RideResponseDto> accepted(RideId rideId, String idempotencyKey) {
        RideResponseDto body = RideResponseDto.accepted(rideId.value());

        // 在返回之前先存储响应，这样一毫秒后到达的重试重放的，恰恰是这位调用方收到的东西。
        idempotency.complete(idempotencyKey, rideId.value(), HttpStatus.ACCEPTED.value(),
                objectMapper.writeValueAsString(body));

        return ResponseEntity.accepted()
                .header("Location", body.pollUrl())
                .body(body);
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<?> getRide(@PathVariable UUID rideId) {
        return requestRide.findById(new RideId(rideId))
                .<ResponseEntity<?>>map(trip -> ResponseEntity.ok(
                        new RideResponseDto(trip.id().value(), trip.status().name(),
                                "/v1/rides/" + trip.id())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(problem(HttpStatus.NOT_FOUND, "Ride not found",
                                "No ride with id " + rideId)));
    }

    private VehicleClass parseVehicleClass(String raw) {
        if (raw == null || raw.isBlank()) {
            return VehicleClass.STANDARD;
        }
        try {
            return VehicleClass.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown vehicleClass: " + raw);
        }
    }

    // 异常处理放在 RestExceptionHandler 中，这样每个端点响应方式一致，新控制器也无须
    // 复制一套处理器进去。

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
