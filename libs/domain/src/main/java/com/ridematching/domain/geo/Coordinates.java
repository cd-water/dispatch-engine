package com.ridematching.domain.geo;

/**
 * 一个 WGS-84 坐标位置。在构造时即完成校验，因此无效坐标不可能
 * 在系统的任何地方存在——匹配引擎与位置接入路径里都不存在
 * 需要防御的“未校验坐标”状态。
 *
 * @param latitude  北纬度数，-90..90
 * @param longitude 东经度数，-180..180
 */
public record Coordinates(double latitude, double longitude) {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    public Coordinates {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IllegalArgumentException("Coordinates must not be NaN");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
    }

    /**
     * 以米为单位的大圆距离。
     *
     * <p>这是在球体地球上做的直线近似，而不是沿路网的距离。那是一次刻意的简化
     * （见 ARCHITECTURE.md 中的非目标）：它正是 Redis {@code GEOSEARCH} 用来排序的同一个度量，
     * 因此候选者的顺序在索引查询与这里所做的任何重排序之间保持一致。
     */
    public double distanceMetersTo(Coordinates other) {
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = Math.toRadians(other.latitude - this.latitude);
        double deltaLon = Math.toRadians(other.longitude - this.longitude);

        double sinHalfLat = Math.sin(deltaLat / 2);
        double sinHalfLon = Math.sin(deltaLon / 2);

        double a = sinHalfLat * sinHalfLat
                + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;

        // 用 atan2 而非 asin：对正对点（对跖点）在数值上更稳定。
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
