package com.ridematching.location.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 摄取路径的调优参数。这里的每个值都是写量与匹配新鲜度之间的权衡，所以它们全都是配置而不是
 * 常量——负载测试会扫描这些值。
 */
@ConfigurationProperties(prefix = "ridematching.location")
public class LocationIngestProperties {

    /**
     * 如果司机自上次持久化位置以来移动的距离小于此值，该帧就被丢弃。在城市速度下这能去掉
     * 很大一部分写入；设得太高候选距离会变陈旧，设得太低节流就失去了意义。
     */
    private double minMovementMeters = 25.0;

    /** 只要过了这么久，无论司机移动多小，该帧总是会被持久化。 */
    private Duration maxWriteInterval = Duration.ofSeconds(3);

    /** 与当前时间相差超过此值的帧——无论过去还是未来——都作为时钟偏移被拒绝。 */
    private Duration maxClockSkew = Duration.ofSeconds(30);

    /**
     * 写缓冲区的上限。满了意味着丢弃最旧：陈旧的位置没有价值，所以把它丢掉正是正确行为，
     * 而不是数据丢失。
     */
    private int bufferCapacity = 50_000;

    /** 缓冲区达到这么多帧时刷写。 */
    private int flushBatchSize = 500;

    /** 至少以这个频率刷写一次，即使批量未满。 */
    private Duration flushInterval = Duration.ofMillis(50);

    /** 静默超过此时间的司机会被清理器驱逐。 */
    private Duration staleAfter = Duration.ofSeconds(30);

    /** 清理器清扫的频率。 */
    private Duration reaperInterval = Duration.ofSeconds(10);

    /** Redis 键命名空间；hash tag 让同一座城市固定在一个集群槽上。 */
    private String city = "default";

    public double getMinMovementMeters() {
        return minMovementMeters;
    }

    public void setMinMovementMeters(double minMovementMeters) {
        this.minMovementMeters = minMovementMeters;
    }

    public Duration getMaxWriteInterval() {
        return maxWriteInterval;
    }

    public void setMaxWriteInterval(Duration maxWriteInterval) {
        this.maxWriteInterval = maxWriteInterval;
    }

    public Duration getMaxClockSkew() {
        return maxClockSkew;
    }

    public void setMaxClockSkew(Duration maxClockSkew) {
        this.maxClockSkew = maxClockSkew;
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }

    public void setBufferCapacity(int bufferCapacity) {
        this.bufferCapacity = bufferCapacity;
    }

    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public void setFlushBatchSize(int flushBatchSize) {
        this.flushBatchSize = flushBatchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public Duration getReaperInterval() {
        return reaperInterval;
    }

    public void setReaperInterval(Duration reaperInterval) {
        this.reaperInterval = reaperInterval;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }
}
