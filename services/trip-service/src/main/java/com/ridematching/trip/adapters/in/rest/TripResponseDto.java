package com.ridematching.trip.adapters.in.rest;

import com.ridematching.domain.trip.Trip;

import java.util.UUID;

/**
 * 调用方在推动或读取行程后拿到的结果。
 *
 * @param rideId   行程
 * @param status   它现在所处的状态——调用方据此确认，真正发生的就是自己请求的那次转换
 * @param driverId 谁持有它；一旦进入终态并释放司机后为 null
 */
public record TripResponseDto(UUID rideId, String status, UUID driverId) {

    public static TripResponseDto of(Trip trip) {
        return new TripResponseDto(
                trip.id().value(),
                trip.status().name(),
                trip.assignedDriver().map(driver -> driver.value()).orElse(null));
    }
}
