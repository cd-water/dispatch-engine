-- 叫车匹配引擎的初始模式（schema）。
--
-- 模式所有权说明：这些模式迁移（Flyway/V1/V2）放在独立模块中，因为现在有两个服务都会写入这些表
-- —— dispatch-api 创建行程，trip-service 推动它走完生命周期。把模式放在某一个消费方内部，
-- 等于允许该消费方在另一个服务毫无察觉的情况下改动模式。两者启动时都从同一套脚本运行 Flyway，这是安全的：
-- Flyway 会获取一把 PostgreSQL 咨询锁（advisory lock），因此后启动的一方会先等待，随后发现无事可做。
-- 参见 libs/schema/pom.xml。
--
-- 刻意暂不引入 PostGIS。ADR-0002 把 PostGIS 留给地理围栏与区域分析，而这些查询目前都不存在。
-- 在出现需要空间索引的查询之前，坐标先用普通数值列存放；在此之前就加装扩展，只会徒增一次镜像变更，
-- 却换不来任何好处。

CREATE TYPE trip_status AS ENUM (
    'REQUESTED',
    'MATCHING',
    'OFFERED',
    'ACCEPTED',
    'IN_PROGRESS',
    'COMPLETED',
    'CANCELLED',
    'UNMATCHED'
);

CREATE TYPE vehicle_class AS ENUM ('STANDARD', 'XL', 'PREMIUM');

CREATE TABLE trips (
    id             UUID PRIMARY KEY,
    rider_id       UUID          NOT NULL,
    driver_id      UUID,
    status         trip_status   NOT NULL,
    vehicle_class  vehicle_class NOT NULL,

    -- 来自 Redis 认领（claim）的围栏令牌（fence token）（ADR-0004）。在司机被报价之前为 NULL。
    fence_token    BIGINT,

    pickup_lat     DOUBLE PRECISION NOT NULL,
    pickup_lng     DOUBLE PRECISION NOT NULL,
    dropoff_lat    DOUBLE PRECISION NOT NULL,
    dropoff_lng    DOUBLE PRECISION NOT NULL,

    requested_at   TIMESTAMPTZ   NOT NULL,
    matched_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,

    -- 乐观锁（optimistic locking），用于那些短暂且存在竞争的更新（接单/拒单/取消）。
    version        INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT trips_driver_required_when_held
        CHECK (status NOT IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS') OR driver_id IS NOT NULL),
    CONSTRAINT trips_pickup_lat_range  CHECK (pickup_lat  BETWEEN -90  AND 90),
    CONSTRAINT trips_pickup_lng_range  CHECK (pickup_lng  BETWEEN -180 AND 180),
    CONSTRAINT trips_dropoff_lat_range CHECK (dropoff_lat BETWEEN -90  AND 90),
    CONSTRAINT trips_dropoff_lng_range CHECK (dropoff_lng BETWEEN -180 AND 180)
);

-- =====================================================================================
-- 正确性保证本身（ADR-0004）。
--
-- 在任何一行"占用司机"状态的记录里，同一个司机最多只能出现一次。Redis 认领只是缓解竞争的优化手段；
-- 真正让重复指派在物理上不可能发生的，是这个唯一部分索引（unique partial index）。
-- 它能扛住 Redis 数据丢失、租约过期、网络分区以及 GC 停顿。
--
-- 谓词必须与 domain 模块中的 TripStatus.holdsDriver() 保持完全一致。
-- 若某个状态在领域语义上被视为占用司机，而这个索引却未覆盖，保证就出现了漏洞；
-- TripStatusTest 断言两者一致。
-- =====================================================================================
CREATE UNIQUE INDEX uniq_driver_active_trip
    ON trips (driver_id)
    WHERE status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS');

CREATE INDEX idx_trips_rider_requested_at ON trips (rider_id, requested_at DESC);
CREATE INDEX idx_trips_status ON trips (status) WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'UNMATCHED');

-- 只追加（append-only）的审计追踪：把内存中的 Trip.history() 持久化。
CREATE TABLE trip_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trip_id     UUID        NOT NULL REFERENCES trips (id),
    from_status trip_status,
    to_status   trip_status NOT NULL,
    driver_id   UUID,
    reason      TEXT,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_trip_events_trip ON trip_events (trip_id, occurred_at);

-- =====================================================================================
-- 幂等（ADR-0005）。
--
-- 主键就是仲裁机制：两个同时到达的重试都会尝试插入，而恰好只有一个能成功。
-- 若采用先读后写的检查，两次操作之间会存在一个窗口，双方都读到"不存在"。
-- =====================================================================================
CREATE TABLE idempotency_keys (
    key             TEXT PRIMARY KEY,

    -- 规范化请求体的 SHA-256。用于发现客户端把同一个键复用在不同请求上的情况，
    -- 这必须是一次响亮的报错，而不是一次静默的重放。
    request_hash    TEXT        NOT NULL,

    state           TEXT        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    ride_id         UUID,
    response_status INTEGER,
    response_body   JSONB,

    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL
);

-- 为清理任务提供支持；部分索引（partial）使其保持较小的体积。
CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);

-- =====================================================================================
-- 事务性发件箱（transactional outbox）（ADR-0003）。
--
-- 与它所描述的状态变更写在同一个事务里，因此"行程已保存"与"事件必将发布"会一起提交。
-- 由发布器把它排空到 Kafka。
-- =====================================================================================
CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    event_id     UUID        NOT NULL UNIQUE,
    topic        TEXT        NOT NULL,
    partition_key TEXT       NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

-- 部分索引：发布器只扫描未发布的行，因此索引始终只有积压（backlog）的规模，
-- 而不是全部历史的规模。
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
