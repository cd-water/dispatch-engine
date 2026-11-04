package com.ridematching.domain.driver;

import com.ridematching.domain.geo.Coordinates;

import java.util.Objects;

/**
 * 一次邻近搜索返回的候选司机，带有索引计算出的距离。
 *
 * <p>这个距离来自 Redis {@code GEOSEARCH}，而不是在这里重新计算：它正是索引实际用来排序的
 * 那个值，因此把它一路携带下去能让匹配引擎的排序与产生它的那次查询保持一致。
 *
 * @param driverId       候选司机
 * @param coordinates    其最近一次上报的位置
 * @param distanceMeters 距搜索原点的直线距离
 */
public record NearbyDriver(DriverId driverId, Coordinates coordinates, double distanceMeters) {

    public NearbyDriver {
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(coordinates, "coordinates");
        if (distanceMeters < 0.0) {
            throw new IllegalArgumentException("Distance must not be negative: " + distanceMeters);
        }
    }
}
