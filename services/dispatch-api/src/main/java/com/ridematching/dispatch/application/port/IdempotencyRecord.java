package com.ridematching.dispatch.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * 一条已存储的幂等键。
 *
 * @param key          客户端提供的键
 * @param requestHash  最初抢占它的那个请求的哈希
 * @param inProgress   原始请求是否仍在运行
 * @param rideId       已创建的行程，一旦可知
 * @param responseStatus 已存储的 HTTP 状态，完成后才有
 * @param responseBody 已存储的响应体，重试时原样重放
 */
public record IdempotencyRecord(
        String key,
        String requestHash,
        boolean inProgress,
        UUID rideId,
        Integer responseStatus,
        String responseBody) {

    public boolean matches(String candidateHash) {
        return requestHash.equals(candidateHash);
    }

    public Optional<String> body() {
        return Optional.ofNullable(responseBody);
    }
}
