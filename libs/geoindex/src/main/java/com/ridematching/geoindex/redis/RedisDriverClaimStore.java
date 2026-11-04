package com.ridematching.geoindex.redis;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 司机认领的 Redis 实现（ADR-0004，第 1 层）。
 *
 * <p>认领本身是一个 Lua 脚本，从 {@code redis/claim-driver.lua} 加载。把它放在文件里而不是
 * Java 字符串中，使它保持可读、有语法高亮，并且可以作为它本来的身份——并发关键代码——被
 * 审阅。
 *
 * <p>Spring Data Redis 会按 SHA 缓存脚本并使用 {@code EVALSHA}，因此脚本体每个连接只跨网络
 * 传输一次，而不是每次认领都传输——当这段代码每秒运行数百次时，这一点很重要。
 */
public class RedisDriverClaimStore implements DriverClaimStore {

    /**
     * Java 中无法写出 {@code List<Object>.class}，所以这个强制转换无法避免。把它隔离在一个
     * 常量里，意味着这条抑制恰好只覆盖这一行，而不是把类中其他真正的 unchecked 警告也一并
     * 掩盖掉。
     */
    @SuppressWarnings("unchecked")
    private static final Class<List<Object>> LIST_TYPE = (Class<List<Object>>) (Class<?>) List.class;

    private static final RedisScript<List<Object>> CLAIM_SCRIPT =
            new DefaultRedisScript<>(readScript("redis/claim-driver.lua"), LIST_TYPE);

    /**
     * 释放也是一个脚本，理由与认领相同：校验令牌和删除 key 不能是两次往返，否则一个在两者
     * 之间过期的认领，它的后继者的标记可能被失败方删除。
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
              return 0
            end
            redis.call('DEL', KEYS[2])
            redis.call('HSET', KEYS[1], 'status', 'AVAILABLE')
            return 1
            """, Long.class);

    private static final RedisScript<Long> ON_TRIP_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'status', 'ON_TRIP')
            redis.call('PERSIST', KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    public RedisDriverClaimStore(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    @Override
    public ClaimResult claim(DriverId driverId, String offerToken, Duration ttl) {
        List<Object> result = redis.execute(
                CLAIM_SCRIPT,
                List.of(keys.driverState(driverId), keys.claim(driverId), keys.fenceCounter()),
                offerToken,
                String.valueOf(ttl.toMillis()));

        if (result == null || result.size() < 2) {
            // null 返回值意味着脚本没有执行——把它当作失败而不是胜利。对认领而言，fail-closed
            // 是唯一安全的方向。
            return ClaimResult.lost("SCRIPT_ERROR");
        }

        boolean won = toLong(result.get(0)) == 1L;
        return won
                ? ClaimResult.won(toLong(result.get(1)))
                : ClaimResult.lost(String.valueOf(result.get(1)));
    }

    @Override
    public boolean release(DriverId driverId, String offerToken) {
        Long released = redis.execute(RELEASE_SCRIPT,
                List.of(keys.driverState(driverId), keys.claim(driverId)),
                offerToken);
        return released != null && released == 1L;
    }

    @Override
    public void markOnTrip(DriverId driverId, String offerToken) {
        redis.execute(ON_TRIP_SCRIPT,
                List.of(keys.driverState(driverId), keys.claim(driverId)),
                offerToken);
    }

    @Override
    public String statusOf(DriverId driverId) {
        Object status = redis.opsForHash().get(keys.driverState(driverId), "status");
        return status == null ? null : status.toString();
    }

    @Override
    public Optional<Long> fenceOf(DriverId driverId) {
        Object fence = redis.opsForHash().get(keys.driverState(driverId), "fence");
        return fence == null ? Optional.empty() : Optional.of(Long.parseLong(fence.toString()));
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static String readScript(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load Lua script " + path, e);
        }
    }
}
