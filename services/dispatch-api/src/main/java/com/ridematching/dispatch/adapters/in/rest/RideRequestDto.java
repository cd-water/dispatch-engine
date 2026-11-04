package com.ridematching.dispatch.adapters.in.rest;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * POST /v1/rides 的线上格式。
 *
 * <p>刻意与领域类型分离（ADR-0001）：校验注解和 JSON 相关关注点属于边缘，而不属于
 * {@code Coordinates}。
 */
public record RideRequestDto(
        @NotNull UUID riderId,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double pickupLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double pickupLng,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double dropoffLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double dropoffLng,
        String vehicleClass) {
}
