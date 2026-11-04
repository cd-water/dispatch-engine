package com.ridematching.dispatch.application;

/**
 * 当 {@code uniq_driver_active_trip} 拒绝一次写入时抛出——即 ADR-0004 的保证在生效。
 *
 * <p>这是<strong>正常的控制流</strong>，不是错误：它意味着另一个 worker 在同一个司机的竞争中
 * 赢了，调用方应当转向下一个候选。它被计数而不是按 ERROR 记录，这样繁忙的派单小时不会让日志
 * 被那些代表系统正常工作的异常淹没。
 */
public class DriverAlreadyAssignedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DriverAlreadyAssignedException(String message, Throwable cause) {
        super(message, cause);
    }
}
