package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 让 Redis 收敛回 PostgreSQL 关于"谁在开车"的说法。
 *
 * <p>释放认领发生在行程终态提交之后，这就留下了一个窗口：若在两者之间崩溃，则什么也不会交还；
 * 而且由于已接受行程的认领标记已被 {@code markOnTrip} 去掉了 TTL，也没有任何过期机制来清理
 * 它。放任不管的话，每次这样的崩溃都会永久退役一个司机。在一场长时间的负载测试中，这会让
 * 司机池悄悄枯竭，表现为匹配率持续下降，却哪里都没有报错可以解释。
 *
 * <p>同步的方向就是设计本身。PostgreSQL 是记录系统（ADR-0004），所以这里读取 Redis 持有的
 * 认领，再向数据库询问每一个是否仍然合理——而不是反过来。一个信任 Redis 的调和器可能会删掉
 * 一个合法的指派。
 *
 * <p><strong>只有不带 TTL 的认领才是候选。</strong>仅凭这一个条件就消除了这类清扫通常具有
 * 的竞态。仍带 TTL 的认领要么是一个在途报价——可能是其 {@code RideMatched} 事件尚未被消费，
 * 因而还不存在可佐证的行程行——要么是一个会自行过期的遗留认领；动其中任何一个，轻则毫无
 * 意义，重则从即将落地的匹配中抢走司机。不带 TTL 的认领只可能由 {@code markOnTrip} 创建，
 * 而它运行于接受提交之后。如果这样的认领如今没有任何行程持有它，说明行程已经结束而释放被
 * 遗漏了。没有其他情形会产生这种组合。
 */
public class ClaimReconciler {

    private static final Logger log = LoggerFactory.getLogger(ClaimReconciler.class);

    /** Redis 对存在但无过期时间的键，{@code TTL} 会返回该值。 */
    private static final long NO_EXPIRY = -1L;

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final DriverClaimStore claims;
    private final RedisKeys keys;
    private final int scanBatch;

    private final Counter reclaimed;

    public ClaimReconciler(StringRedisTemplate redis,
                           JdbcClient jdbc,
                           DriverClaimStore claims,
                           RedisKeys keys,
                           MeterRegistry meters,
                           int scanBatch) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.claims = claims;
        this.keys = keys;
        this.scanBatch = scanBatch;

        // 健康运行时应当为零。计数上升意味着认领正在上游泄漏，而这里只是在遮掩问题，并非
        // 释放路径在正常工作——值得告警，而不只是看一眼图。
        this.reclaimed = Counter.builder("driver.claim.reclaimed")
                .description("Stranded claims released by the reconciler").register(meters);
    }

    /**
     * 执行一轮。返回回收了多少个认领。
     *
     * <p>用 {@code SCAN} 而不是 {@code KEYS}：这段逻辑跑在为所有司机位置更新提供服务的
     * 同一个 Redis 上，而 {@code KEYS} 会在整个键空间的时长内阻塞唯一的命令线程。游标扫描
     * 可能漏掉在扫描途中出现的键，这没关系——下一轮会看到它。
     */
    public int reconcile() {
        List<Candidate> candidates = new ArrayList<>();

        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(keys.claimPattern())
                .count(scanBatch)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
                if (ttl == null || ttl != NO_EXPIRY) {
                    continue;
                }
                driverIdOf(key).ifPresent(driverId -> candidates.add(new Candidate(driverId, key)));
            }
        }

        if (candidates.isEmpty()) {
            return 0;
        }

        int released = 0;
        for (Candidate candidate : withoutActiveTrip(candidates)) {
            // 现在重新读取令牌，而不是在扫描期间读取：它必须是释放脚本将要比对的同一个
            // 值，而认领可能自那以后已经易主。
            String token = redis.opsForValue().get(candidate.key());
            if (token == null) {
                continue;
            }
            if (claims.release(new DriverId(candidate.driverId()), token)) {
                released++;
                reclaimed.increment();
                log.info("Reclaimed stranded claim for driver {}", candidate.driverId());
            }
        }
        return released;
    }

    /**
     * 过滤出 PostgreSQL 认为不在行程中的司机。
     *
     * <p>整批只发一次查询，而不是每个司机一次：每键一次往返的清扫本身就变成了一个负载源。
     */
    private List<Candidate> withoutActiveTrip(List<Candidate> candidates) {
        List<UUID> ids = candidates.stream().map(candidate -> candidate.driverId()).toList();

        Set<UUID> active = Set.copyOf(jdbc.sql("""
                        SELECT driver_id FROM trips
                        WHERE driver_id IN (:ids)
                          AND status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS')
                        """)
                .param("ids", ids)
                .query(UUID.class)
                .list());

        return candidates.stream()
                .filter(candidate -> !active.contains(candidate.driverId()))
                .toList();
    }

    /** 从 {@code claim:{city}:<uuid>} 键中取出司机 id。 */
    private static Optional<UUID> driverIdOf(String key) {
        int lastColon = key.lastIndexOf(':');
        if (lastColon < 0 || lastColon == key.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(key.substring(lastColon + 1)));
        } catch (IllegalArgumentException e) {
            // 不是司机认领键。跳过而不是让整轮失败：一个恰好匹配模式的无关键，是忽略它的
            // 理由，而不是停止调和的理由。
            return Optional.empty();
        }
    }

    private record Candidate(UUID driverId, String key) {
    }
}
