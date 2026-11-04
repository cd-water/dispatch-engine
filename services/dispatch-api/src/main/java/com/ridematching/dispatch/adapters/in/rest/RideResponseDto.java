package com.ridematching.dispatch.adapters.in.rest;

import java.util.UUID;

/**
 * 已创建或已重放的叫车请求的响应。
 *
 * @param rideId  客户端用于轮询的标识符
 * @param status  响应发出时的行程状态
 * @param pollUrl 到哪里查询进度；匹配是异步的
 */
public record RideResponseDto(UUID rideId, String status, String pollUrl) {

    public static RideResponseDto accepted(UUID rideId) {
        return new RideResponseDto(rideId, "REQUESTED", "/v1/rides/" + rideId);
    }
}
