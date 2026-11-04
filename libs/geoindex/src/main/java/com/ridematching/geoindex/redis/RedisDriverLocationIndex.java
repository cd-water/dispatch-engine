package com.ridematching.geoindex.redis;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.DriverStatus;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.geoindex.DriverLocationIndex;
import com.ridematching.geoindex.RedisKeys;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 在线司机索引的 Redis 实现（ADR-0002）。
 *
 * <p>两个 key，都以城市作为 hash tag，从而让集群把它们放在同一个槽里——{@code
 * GEOSEARCH} 无法跨槽，匹配引擎对同一命名空间运行的 Lua 认领脚本同样不行：
 *
 * <ul>
 *   <li>{@code geo:drivers:{city}} —— 地理空间有序集合</li>
 *   <li>{@code driver:seen:{city}} —— 以最后一次上报的 epoch 毫秒数为分数的有序集合，它把
 *       "谁已经没有动静了？"变成一次 {@code ZRANGEBYSCORE}，而不是一次扫描</li>
 * </ul>
 */
public class RedisDriverLocationIndex implements DriverLocationIndex {

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final String geoKey;
    private final String seenKey;

    public RedisDriverLocationIndex(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
        this.geoKey = keys.geoIndex();
        this.seenKey = keys.lastSeen();
    }

    @Override
    public void upsertAll(List<DriverLocation> locations) {
        if (locations.isEmpty()) {
            return;
        }

        // 无论批次多大，整个批次只需要一次 GEOADD 和一次 ZADD。这就是
        // 每秒约 20 次往返和每秒约 10,000 次往返之间的差别。
        Map<String, Point> positions = new HashMap<>(locations.size());
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> seen =
                new java.util.HashSet<>(locations.size());

        for (DriverLocation location : locations) {
            String member = location.driverId().toString();
            // Redis 先接收经度。把这两个参数调换不会有任何报错，却会让所有司机落进
            // 错误的半球，所以值得写明。
            positions.put(member, new Point(location.coordinates().longitude(),
                    location.coordinates().latitude()));
            seen.add(org.springframework.data.redis.core.ZSetOperations.TypedTuple.of(
                    member, (double) location.recordedAt().toEpochMilli()));
        }

        redis.opsForGeo().add(geoKey, positions);
        redis.opsForZSet().add(seenKey, seen);
    }

    @Override
    public void markOnline(DriverId driverId) {
        redis.opsForHash().put(keys.driverState(driverId), "status", DriverStatus.AVAILABLE.name());
    }

    @Override
    public void markOffline(DriverId driverId) {
        String member = driverId.toString();
        redis.opsForZSet().remove(geoKey, member);
        redis.opsForZSet().remove(seenKey, member);
        redis.delete(keys.driverState(driverId));
    }

    @Override
    public List<NearbyDriver> search(Coordinates center, double radiusMeters, int limit) {
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs
                .newGeoSearchArgs()
                .includeCoordinates()
                .includeDistance()
                .sortAscending()
                .limit(limit);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                geoKey,
                GeoReference.fromCoordinate(new Point(center.longitude(), center.latitude())),
                new Distance(radiusMeters, Metrics.NEUTRAL),
                args);

        if (results == null) {
            return List.of();
        }

        List<NearbyDriver> nearby = new ArrayList<>(results.getContent().size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            RedisGeoCommands.GeoLocation<String> location = result.getContent();
            Point point = location.getPoint();
            nearby.add(new NearbyDriver(
                    new DriverId(UUID.fromString(location.getName())),
                    new Coordinates(point.getY(), point.getX()),
                    result.getDistance().getValue()));
        }
        return nearby;
    }

    @Override
    public int evictStaleOlderThan(Instant cutoff) {
        Set<String> stale = redis.opsForZSet()
                .rangeByScore(seenKey, 0, (double) cutoff.toEpochMilli());
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        Object[] members = stale.toArray();
        redis.opsForZSet().remove(geoKey, members);
        redis.opsForZSet().remove(seenKey, members);
        stale.forEach(id -> redis.delete(keys.driverState(id)));
        return stale.size();
    }

    @Override
    public long size() {
        Long count = redis.opsForZSet().zCard(geoKey);
        return count == null ? 0L : count;
    }

}
