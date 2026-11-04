package com.ridematching.geoindex;

import java.util.Optional;

/**
 * 尝试认领一个司机的结果（ADR-0004）。
 *
 * @param won        本次调用方是否持有该认领
 * @param fenceToken 证明哪个认领是最新的单调令牌；仅在获胜时存在
 * @param reason     认领被拒绝的原因；仅在失败时存在
 */
public record ClaimResult(boolean won, Long fenceToken, String reason) {

    /** 该司机已被其他工作者认领。 */
    public static final String ALREADY_CLAIMED = "ALREADY_CLAIMED";
    /** 该司机不处于 AVAILABLE 状态（正在行程中，或已被预留）。 */
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    /** 没有状态哈希：司机已下线，或作为失联司机被清除。 */
    public static final String UNKNOWN_DRIVER = "UNKNOWN_DRIVER";

    public static ClaimResult won(long fenceToken) {
        return new ClaimResult(true, fenceToken, null);
    }

    public static ClaimResult lost(String reason) {
        return new ClaimResult(false, null, reason);
    }

    public Optional<Long> fence() {
        return Optional.ofNullable(fenceToken);
    }
}
