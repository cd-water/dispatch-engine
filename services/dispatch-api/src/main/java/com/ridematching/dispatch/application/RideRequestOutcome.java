package com.ridematching.dispatch.application;

import com.ridematching.domain.trip.RideId;

/**
 * 叫车请求的结果，与 ADR-0005 的响应表一一对应。
 *
 * <p>对非错误路径用类似密封的枚举结果而不是异常：在途时到达的重试是普通行为，而不是异常
 * 状况；把它建模为返回值，就让它不会出现在堆栈和错误仪表盘里。
 */
public record RideRequestOutcome(Kind kind, RideId rideId, Integer replayStatus, String replayBody) {

    public enum Kind {
        /** 该键的第一次请求：工作已入队。HTTP 202。 */
        ACCEPTED,
        /** 原始请求仍在运行时的重试。HTTP 409 + Retry-After。 */
        IN_PROGRESS,
        /** 完成之后的重试：原样重放已存储的响应。 */
        REPLAYED,
        /** 相同的键、不同的请求体。HTTP 422——客户端 bug。 */
        KEY_REUSE
    }

    public static RideRequestOutcome accepted(RideId rideId) {
        return new RideRequestOutcome(Kind.ACCEPTED, rideId, null, null);
    }

    public static RideRequestOutcome inProgress() {
        return new RideRequestOutcome(Kind.IN_PROGRESS, null, null, null);
    }

    public static RideRequestOutcome replay(RideId rideId, Integer status, String body) {
        return new RideRequestOutcome(Kind.REPLAYED, rideId, status, body);
    }

    public static RideRequestOutcome keyReuse() {
        return new RideRequestOutcome(Kind.KEY_REUSE, null, null, null);
    }
}
