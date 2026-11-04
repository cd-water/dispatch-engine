package com.ridematching.dispatch.adapters.out.persistence;

import com.ridematching.dispatch.application.port.IdempotencyRecord;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的幂等键（ADR-0005）。
 *
 * <p>通过 {@link JdbcClient} 写普通 SQL，而不是 JPA。整个机制取决于一条语句的确切语义——
 * {@code INSERT ... ON CONFLICT DO NOTHING}——而 ORM 会遮掩掉让它正确的那个东西。
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    /** 已完成的响应在多长时间内仍可重放。 */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcIdempotencyStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean tryClaim(String key, String requestHash) {
        // ON CONFLICT DO NOTHING 让它成为一次原子决定。两个并发重试都会执行它；恰好有一个
        // 报告影响 1 行。这里刻意不先做 SELECT——那会打开这个机制存在的意义就是要关上的
        // 那个窗口。
        //
        // DO NOTHING 目标上的 WHERE 子句还让认领可以在上一个过期后被重新取得，这样键就不会
        // 被永久毒化。
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (key, request_hash, state, created_at, expires_at)
                        VALUES (:key, :hash, 'IN_PROGRESS', :now, :expires)
                        ON CONFLICT (key) DO NOTHING
                        """)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("expires", java.sql.Timestamp.from(clock.instant().plus(RETENTION)))
                .update();

        return inserted == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT key, request_hash, state, ride_id, response_status, response_body
                        FROM idempotency_keys
                        WHERE key = :key AND expires_at > :now
                        """)
                .param("key", key)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("key"),
                        rs.getString("request_hash"),
                        "IN_PROGRESS".equals(rs.getString("state")),
                        rs.getObject("ride_id", UUID.class),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body")))
                .optional();
    }

    @Override
    public void complete(String key, UUID rideId, int responseStatus, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET state = 'COMPLETED',
                            ride_id = :rideId,
                            response_status = :status,
                            response_body = CAST(:body AS JSONB)
                        WHERE key = :key
                        """)
                .param("key", key)
                .param("rideId", rideId)
                .param("status", responseStatus)
                .param("body", responseBody)
                .update();
    }

    @Override
    public void release(String key) {
        // 只释放仍处于 IN_PROGRESS 的认领。已完成的记录绝不能被迟到的失败处理器删除，
        // 否则一个合法的已存储响应就消失了。
        jdbc.sql("DELETE FROM idempotency_keys WHERE key = :key AND state = 'IN_PROGRESS'")
                .param("key", key)
                .update();
    }

    @Override
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at <= :now")
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .update();
    }
}
