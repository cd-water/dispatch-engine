package com.ridematching.events;

/**
 * Topic 名称，集中声明一次（ADR-0003）。
 *
 * <p>{@code .v1} 后缀是刻意成为名称的一部分：破坏性的模式（schema）变更是通过一个带
 * 双写发布（dual-publish）窗口的新 topic 来落地，而不是靠生产者与消费者必须同时重启的
 * 一锤子式协同部署。
 *
 * <p>这里的拼写错误在 broker 端不会是编译错误——{@code auto.create.topics.enable} 被设为
 * false，正是为了让错误的名称响亮地失败，而不是悄悄创建一个无人消费的 topic。
 * 常量从一开始就杜绝了这种拼写错误。
 */
public final class Topics {

    public static final String DRIVER_LOCATION = "driver.location.v1";
    public static final String RIDE_REQUESTED = "ride.requested.v1";
    public static final String RIDE_MATCHED = "ride.matched.v1";
    public static final String RIDE_UNMATCHED = "ride.unmatched.v1";
    public static final String RIDE_COMPLETED = "ride.completed.v1";
    public static final String RIDE_CANCELLED = "ride.cancelled.v1";

    /** 消费者组（consumer group）。彼此独立的组正是扇出（fan-out）互不干扰的原因。 */
    public static final class Groups {
        public static final String MATCHING_ENGINE = "matching-engine";
        public static final String NOTIFICATION = "notification-service";
        public static final String BILLING = "billing-service";

        private Groups() {
        }
    }

    /** {@code topic} 对应的死信（DLT）topic，遵循 {@code *.dlt} 命名约定。 */
    public static String deadLetter(String topic) {
        return topic + ".dlt";
    }

    private Topics() {
    }
}
