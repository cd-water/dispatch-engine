package com.ridematching.domain.trip;

/**
 * 当行程被要求迁移到一个它无法合法到达的状态时抛出。
 *
 * <p>这类情况确实属于例外——一次重复的 Kafka 投递、一条迟到的报价响应
 * 与一次接受相互竞争，或者一个 bug。适配器会把它转换为 {@code 409 Conflict}
 * 而不是 {@code 500}：请求本身是格式良好的，只是行程并不处于
 * 让这个操作有意义的状态。
 */
public class IllegalTripTransitionException extends RuntimeException {

    /**
     * 显式声明，而不是留给编译器去生成。如今这些异常从不在网络上
     * 被序列化，但一旦类的形态发生变化，隐式生成的值就会跟着变，
     * 这会把一次无害的重构，在某天真有东西序列化它的那天，变成一次反序列化失败。
     */
    private static final long serialVersionUID = 1L;

    private final TripStatus from;
    private final TripStatus to;

    public IllegalTripTransitionException(RideId rideId, TripStatus from, TripStatus to) {
        super("Trip %s cannot move from %s to %s; allowed from %s: %s"
                .formatted(rideId, from, to, from, from.allowedTargets()));
        this.from = from;
        this.to = to;
    }

    public TripStatus from() {
        return from;
    }

    public TripStatus to() {
        return to;
    }
}
