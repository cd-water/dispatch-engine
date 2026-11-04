package com.ridematching.trip.adapters.in.rest;

import com.ridematching.domain.trip.RideId;
import com.ridematching.trip.application.TripLifecycle;
import com.ridematching.trip.application.TripNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 通过 HTTP 暴露行程生命周期。
 *
 * <p>四个转换，各自一个端点，而不是用一个接收目标状态的 {@code PATCH}。这个区别很重要：
 * {@code POST /accept} 永远只可能表示一个转换，因此合法请求的集合就是合法转换的集合，一次
 * 非法迁移由路由拒绝，而不是把一个字符串拿去和状态机做校验。这也让 URL 诚实地表达自己是
 * 动作——完成一趟行程并不是编辑某个状态字段。
 *
 * <p>与叫车请求不同，这里是同步的。叫车请求返回 {@code 202}，因为匹配耗时不定，也不该有人
 * 为它一直占着连接；而这些转换只是一次单行更新，调用方确实需要知道它有没有赢。司机点了
 * 接受，如果别人先到，必须立刻告诉他。
 *
 * <p>这里刻意不使用幂等键。状态机已经提供了它们本要买到的性质：对一趟已 {@code ACCEPTED}
 * 的行程重复 {@code accept}，会被转换表拒绝，而不是被执行两次。再加一个键存储，就是在为
 * 一个已经在更下层强制的不变量添加机器。
 */
@RestController
@RequestMapping("/v1/trips")
public class TripController {

    private final TripLifecycle lifecycle;

    public TripController(TripLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** 司机接受了报价。{@code OFFERED -> ACCEPTED}。 */
    @PostMapping("/{rideId}/accept")
    public ResponseEntity<TripResponseDto> accept(@PathVariable UUID rideId) {
        lifecycle.accept(new RideId(rideId));
        return current(rideId);
    }

    /** 乘客已上车。{@code ACCEPTED -> IN_PROGRESS}。 */
    @PostMapping("/{rideId}/start")
    public ResponseEntity<TripResponseDto> start(@PathVariable UUID rideId) {
        lifecycle.start(new RideId(rideId));
        return current(rideId);
    }

    /** 乘客已送达下车。{@code IN_PROGRESS -> COMPLETED}，司机被释放。 */
    @PostMapping("/{rideId}/complete")
    public ResponseEntity<TripResponseDto> complete(@PathVariable UUID rideId) {
        lifecycle.complete(new RideId(rideId));
        return current(rideId);
    }

    /**
     * 行程被取消。终态，司机被释放。
     *
     * <p>行程一旦进入 {@code IN_PROGRESS} 即被拒绝——见 {@code TripStatus}。这会被作为
     * 指名该状态的 409 返回，而不是一个静默的空操作。
     */
    @PostMapping("/{rideId}/cancel")
    public ResponseEntity<TripResponseDto> cancel(@PathVariable UUID rideId,
                                                  @Valid @RequestBody CancelRequestDto request) {
        lifecycle.cancel(new RideId(rideId), request.reason());
        return current(rideId);
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<TripResponseDto> get(@PathVariable UUID rideId) {
        return current(rideId);
    }

    /**
     * 在转换之后重新读取行程。
     *
     * <p>再读一次，而不是直接返回内存中的聚合。它在一次已经提交的请求上多花一次往返，换来
     * 的保证是：响应描述的是实际存储的内容——包括期间并发写入者做的任何事，而这恰恰是检查
     * 结果的调用方所关心的。
     */
    private ResponseEntity<TripResponseDto> current(UUID rideId) {
        RideId id = new RideId(rideId);
        return lifecycle.find(id)
                .map(trip -> ResponseEntity.ok(TripResponseDto.of(trip)))
                .orElseThrow(() -> new TripNotFoundException(id));
    }
}
