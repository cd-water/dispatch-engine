-- Dispatch Engine（派单引擎）数据库初始 Schema

-- 行程状态机：REQUESTED → MATCHING → OFFERED → ACCEPTED → IN_PROGRESS → COMPLETED
-- 终态：COMPLETED / CANCELLED / UNMATCHED
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

-- 车型等级
CREATE TYPE vehicle_class AS ENUM (
    'STANDARD',
    'XL',
    'PREMIUM'
    );

-- 行程主表
CREATE TABLE trips
(
    id            UUID             NOT NULL,           -- 行程 ID
    rider_id      UUID             NOT NULL,           -- 乘客 ID
    driver_id     UUID,                                -- 司机 ID（OFFERED 起才有值）
    status        trip_status      NOT NULL,           -- 当前状态
    vehicle_class vehicle_class    NOT NULL,           -- 请求的车型
    fence_token   BIGINT,                              -- 围栏令牌，被 offer 前为 NULL
    offer_token   TEXT,                                -- 占用 driver_id 的凭据，释放时须携带比对
    pickup_lat    DOUBLE PRECISION NOT NULL,           -- 上车点纬度
    pickup_lng    DOUBLE PRECISION NOT NULL,           -- 上车点经度
    dropoff_lat   DOUBLE PRECISION NOT NULL,           -- 下车点纬度
    dropoff_lng   DOUBLE PRECISION NOT NULL,           -- 下车点经度
    requested_at  TIMESTAMPTZ      NOT NULL,           -- 请求时间
    matched_at    TIMESTAMPTZ,                         -- 匹配成功时间
    completed_at  TIMESTAMPTZ,                         -- 完成时间
    version       INTEGER          NOT NULL DEFAULT 0, -- 乐观锁，用于接单/拒单/取消等高竞争更新

    CONSTRAINT trips_pk PRIMARY KEY (id),
    -- 占用态必须有司机
    CONSTRAINT trips_driver_required_when_held
        CHECK (status NOT IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS') OR driver_id IS NOT NULL),
    CONSTRAINT trips_pickup_lat_range CHECK (pickup_lat BETWEEN -90 AND 90),
    CONSTRAINT trips_pickup_lng_range CHECK (pickup_lng BETWEEN -180 AND 180),
    CONSTRAINT trips_dropoff_lat_range CHECK (dropoff_lat BETWEEN -90 AND 90),
    CONSTRAINT trips_dropoff_lng_range CHECK (dropoff_lng BETWEEN -180 AND 180)
);

-- 一个司机在占用态最多一行，杜绝重复派单
CREATE UNIQUE INDEX uniq_driver_active_trip
    ON trips (driver_id) WHERE status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS');

-- 乘客历史行程
CREATE INDEX idx_trips_rider_requested_at
    ON trips (rider_id, requested_at DESC);

-- 部分索引，只含活跃行
CREATE INDEX idx_trips_status
    ON trips (status) WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'UNMATCHED');

-- 行程状态流转审计表（只追加）
CREATE TABLE trip_events
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY, -- 自增主键
    trip_id     UUID        NOT NULL,                -- 所属行程
    from_status trip_status,                         -- 流转前状态（首个事件为 NULL）
    to_status   trip_status NOT NULL,                -- 流转后状态
    driver_id   UUID,                                -- 涉及的司机（如有）
    reason      TEXT,                                -- 原因说明（如取消理由）
    occurred_at TIMESTAMPTZ NOT NULL,                -- 发生时间

    CONSTRAINT trip_events_pk PRIMARY KEY (id),
    CONSTRAINT trip_events_trip_fk FOREIGN KEY (trip_id) REFERENCES trips (id)
);

CREATE INDEX idx_trip_events_trip
    ON trip_events (trip_id, occurred_at);

-- 幂等键表：主键即仲裁机制，并发重试同时插入恰好一个成功
CREATE TABLE idempotency_keys
(
    key             TEXT        NOT NULL, -- 幂等键（客户端提供）
    request_hash    TEXT        NOT NULL, -- 请求体 SHA-256，同一 key 发不同请求必须报错
    state           TEXT        NOT NULL, -- 请求处理状态
    ride_id         UUID,                 -- 成功后关联的行程 ID
    response_status INTEGER,              -- 缓存的响应状态码
    response_body   JSONB,                -- 缓存的响应体
    created_at      TIMESTAMPTZ NOT NULL, -- 创建时间
    expires_at      TIMESTAMPTZ NOT NULL, -- 过期时间，供清理任务使用

    CONSTRAINT idempotency_keys_pk PRIMARY KEY (key),
    CONSTRAINT idempotency_keys_state_check CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
);

-- 清理任务用
CREATE INDEX idx_idempotency_expiry
    ON idempotency_keys (expires_at);

-- 事务性发件箱：与状态变更同事务写入，行程已保存则事件必将发布
CREATE TABLE outbox
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY, -- 自增主键
    aggregate_id  UUID        NOT NULL,                -- 聚合根 ID（如行程 ID）
    event_id      UUID        NOT NULL,                -- 事件 ID，用于去重
    topic         TEXT        NOT NULL,                -- 目标 Kafka 主题
    partition_key TEXT        NOT NULL,                -- Kafka 分区键
    payload       JSONB       NOT NULL,                -- 事件负载
    created_at    TIMESTAMPTZ NOT NULL,                -- 创建时间
    published_at  TIMESTAMPTZ,                         -- 发布时间，NULL 表示待发布

    CONSTRAINT outbox_pk PRIMARY KEY (id),
    CONSTRAINT outbox_event_id_uk UNIQUE (event_id)
);

-- 部分索引：体积始终是积压量而非历史总量
CREATE INDEX idx_outbox_unpublished
    ON outbox (id) WHERE published_at IS NULL;
