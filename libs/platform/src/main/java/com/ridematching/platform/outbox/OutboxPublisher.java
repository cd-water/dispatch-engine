package com.ridematching.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 将发件箱（outbox）中的事件排空到 Kafka（ADR-0003）。
 *
 * <p>以较短的时间间隔轮询运行，而不是对事务提交做出反应。Debezium 变更数据捕获（CDC）
 * 可以消除轮询延迟，代价是要额外运维 Debezium；对于一个匹配 SLA 为 500ms 的系统而言，
 * 200ms 的轮询完全在预算之内，而且简单得多。
 *
 * <p><strong>刻意选择至少一次（at-least-once）。</strong>只有当 Kafka 确认之后，才会把该行标记为已发布。
 * 如果进程在发送与标记之间崩溃，事件会在下一轮再次发送——这正是每个消费者都按 {@code eventId}
 * 去重的原因。反过来先标记的顺序在同样的崩溃下会丢失事件，而丢失事件无法恢复，重复只是麻烦一点。
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final int batchSize;

    private final MeterRegistry meters;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(JdbcClient jdbc,
                           KafkaTemplate<String, String> kafka,
                           MeterRegistry meters,
                           Clock clock,
                           int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;
        this.meters = meters;

        this.published = Counter.builder("outbox.published")
                .description("Events drained from the outbox to Kafka").register(meters);
        this.failed = Counter.builder("outbox.publish.failed")
                .description("Outbox rows whose send failed and will be retried").register(meters);

    }

    /**
     * 在构造完成后注册积压（backlog）仪表。
     *
     * <p>不放在构造函数里：在构造函数内部把 {@code this} 交给注册器，等于发布了一个尚未初始化完整的对象，
     * javac 会将其标记为可能的 'this' 逃逸。把类标记为 final 也能消除这个告警——曾经也确实这么做过，
     * 但由此无法创建 CGLIB 代理，进而破坏了 {@link #publishBatch()} 上的 {@code @Transactional}。
     *
     * <p>积压深度是这里最关键的健康信号：发件箱持续增长意味着 Kafka 不可达或发布器已停滞，
     * 它远早于有人发现通知缺失之前就会暴露出来。
     */
    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        Gauge.builder("outbox.backlog", this, publisher -> publisher.backlogDepth())
                .description("Events written but not yet published to Kafka")
                .register(meters);
    }

    /**
     * 发布一个批次。返回已发送的事件数量。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 让多个实例可以并发地排空同一个发件箱，而不会在别的实例
     * 已经取走的行上阻塞。没有 SKIP LOCKED，第二个发布器只能串行排在第一个之后，毫无增益。
     */
    @Transactional
    public int publishBatch() {
        List<OutboxRow> batch = jdbc.sql("""
                        SELECT id, event_id, topic, partition_key, payload::text AS payload
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", batchSize)
                .query((rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("partition_key"),
                        rs.getString("payload")))
                .list();

        if (batch.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (OutboxRow row : batch) {
            try {
                // 阻塞等待确认：在 Kafka 确认之前就把该行标记为已发布，
                // 正是发件箱本来要消除的双重写入（dual-write）缺陷，只是被往后挪了一步。
                kafka.send(row.topic(), row.partitionKey(), row.payload()).get();
                markPublished(row.id());
                published.increment();
                sent++;
            } catch (InterruptedException e) {
                // 只有在真正的中断时才恢复标志位，并立即停止——
                // 吞掉中断会让该线程再也无法被关闭。
                Thread.currentThread().interrupt();
                failed.increment();
                break;
            } catch (Exception e) {
                failed.increment();
                // 刻意保持未发布状态：下一轮会重试。分区内的顺序得以保留，因为我们在第一次失败时就停止，
                // 而不是跳过去继续——在第 N 行失败后发布第 N+1 行会打乱事件流的顺序。
                log.warn("Outbox row {} failed to publish, will retry: {}", row.id(), e.toString());
                break;
            }
        }
        return sent;
    }

    private void markPublished(long id) {
        jdbc.sql("UPDATE outbox SET published_at = :now WHERE id = :id")
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
    }

    /** 尚未发布、等待被排空的行。 */
    public double backlogDepth() {
        Integer depth = jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Integer.class).single();
        return depth == null ? 0 : depth;
    }

    private record OutboxRow(long id, UUID eventId, String topic, String partitionKey, String payload) {
    }
}
