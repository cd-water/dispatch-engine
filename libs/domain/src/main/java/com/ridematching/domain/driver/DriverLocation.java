package com.ridematching.domain.driver;

import com.ridematching.domain.geo.Coordinates;

import java.time.Instant;
import java.util.Objects;

/**
 * 来自某位司机的一次 GPS 观测。
 *
 * <p>在构造时即完成校验，因此越界的朝向或负的速度会在边缘处被拒绝，
 * 而不是污染地理空间索引。在目标负载下，这类观测每秒大约会到达一万条，
 * 这正是这个类型被做成 record、除自身字段外不做任何分配的原因。
 *
 * @param driverId       上报者
 * @param coordinates    其所在位置
 * @param headingDegrees 罗盘方位，0..360；0 为正北
 * @param speedKph       对地速度，永不为负
 * @param recordedAt     设备采样的时间，而不是服务器收到它的时间
 */
public record DriverLocation(
        DriverId driverId,
        Coordinates coordinates,
        double headingDegrees,
        double speedKph,
        Instant recordedAt) {

    public DriverLocation {
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(recordedAt, "recordedAt");

        if (Double.isNaN(headingDegrees) || headingDegrees < 0.0 || headingDegrees > 360.0) {
            throw new IllegalArgumentException("Heading out of range: " + headingDegrees);
        }
        if (Double.isNaN(speedKph) || speedKph < 0.0) {
            throw new IllegalArgumentException("Speed must not be negative: " + speedKph);
        }
    }

    /** 本次观测与 {@code other} 之间相距的米数。 */
    public double distanceMetersTo(DriverLocation other) {
        return coordinates.distanceMetersTo(other.coordinates());
    }

    /**
     * 该读数是否已经偏离 {@code now} 太远而不可信——无论朝哪个方向偏离。
     *
     * <p>带着未来时间的帧与过期帧同样重要：否则一台时钟走偏的设备
     * 会永远在“最近位置”的竞争中胜出。
     */
    public boolean isSkewedBeyond(Instant now, java.time.Duration tolerance) {
        java.time.Duration delta = java.time.Duration.between(recordedAt, now).abs();
        return delta.compareTo(tolerance) > 0;
    }
}
