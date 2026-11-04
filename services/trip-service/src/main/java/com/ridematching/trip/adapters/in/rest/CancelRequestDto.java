package com.ridematching.trip.adapters.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 行程被取消的原因。
 *
 * <p>必填而非可选。原因会进入只追加的 {@code trip_events} 追踪和 {@code ride.cancelled.v1}
 * 事件，而没有原因的取消就是一行谁也回答不了的问题记录。
 */
public record CancelRequestDto(
        @NotBlank(message = "a cancellation reason is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String reason) {
}
