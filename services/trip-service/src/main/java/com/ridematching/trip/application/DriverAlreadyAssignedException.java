package com.ridematching.trip.application;

/**
 * 当 {@code uniq_driver_active_trip} 拒绝一次写入时抛出——即 ADR-0004 的保证在生效。
 *
 * <p>这是<strong>正常的控制流</strong>，不是错误：另一趟行程已经持有这个司机，正确的反应
 * 是放弃这个司机而不是重试。它被计数而不是按 ERROR 记录，这样争用不会让日志被那些代表系统
 * 正常工作的异常淹没。
 *
 * <p>刻意不与 dispatch-api 中同名类型共享。两个服务发布各自的 API，这个异常属于各自内部的
 * 词汇；为了省下十五行而把它提升到共享模块，只会让两者耦合，毫无收益。
 */
public class DriverAlreadyAssignedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DriverAlreadyAssignedException(String message, Throwable cause) {
        super(message, cause);
    }
}
