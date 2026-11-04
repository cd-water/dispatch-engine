# 系统设计：实时派单引擎（Real-Time Ride-Matching Engine）

> [README](../README.md) 的配套文档。本文是完整设计：组件、数据模型、算法、故障模式，以及容量推导。

---

## 1. 问题陈述

构建一个网约车平台的派单核心：

1. 持续接入数千名移动中司机的 GPS 位置。
2. 当乘客发出叫车请求时，在数秒内找到附近可用的司机。
3. 将恰好一名司机分配给恰好一个行程——**绝不**重复。
4. 让下游关注点（通知、计费）远离请求路径。
5. 在负载下用指标证明上述所有内容。

### 非目标

刻意排除在范围之外，以保持设计聚焦：

- 基于真实路网的路径规划 / ETA（改用 haversine + 平均速度的近似）
- 动态加价、司机结算、真实支付处理
- 除桩化 bearer token 之外的认证
- 多区域复制
- 任何用户界面

---

## 2. 容量模型

本设计面向一座中等规模的单城。数字驱动架构，它们不是装饰。

| 数量 | 数值 | 推导 |
|---|---|---|
| 活跃司机 | 10,000 | 目标负载 |
| 位置上报频率 | 1 Hz | 派单系统的通用标准 |
| **位置写入** | **10,000/s** | 10k × 1 Hz |
| 位置报文大小 | ~120 字节 | id、lat、lng、heading、speed、ts |
| **接入带宽** | ~1.2 MB/s | 10k × 120 B |
| 叫车请求 | 500/s 峰值 | 每秒约每 20 名司机 1 个请求 |
| 每次叫车的 GEOSEARCH | 1–3 | 扩展半径（expanding search）重试 |
| Redis 内存 | ~50 MB | 10k 条地理条目 + 状态哈希，极小 |
| Postgres 写入速率 | ~1,500/s | 行程 + 状态流转 + 发件箱 |

**关键观察：** 位置写入量以 **20:1** 的比例远超行程写入量，而且位置数据是可丢弃的——只有最新的位置有意义。行程数据则是持久化、事务化且可审计的。这两类工作负载的需求截然相反，因此使用不同的存储。这一拆分是整个系统中最重要的单一决策。

---

## 3. 组件设计

### 3.1 位置服务（Location Service）—— WebSocket 接入

**职责：** 终结司机的 WebSocket 连接、校验帧，并以最小的延迟将位置落入地理空间索引。

**连接模型。** Java 25 虚拟线程：在 10k 连接规模下，每个连接一个虚拟线程（初始各约 1 KB 栈）是可承受的，而 10k 平台线程则不然。这带来了直白阻塞式的代码——可读、可调试——同时具备反应式级别的可伸缩性。
参见 [ADR-0006](adr/0006-java21-virtual-threads.md)。

**帧协议**（JSON；Protobuf 是文档化的升级路径）：

```json
{ "t": "loc", "driverId": "d-8842", "lat": 30.0444, "lng": 31.2357,
  "heading": 274.5, "speedKph": 38.2, "ts": 1735689600123 }
```

**写入路径，逐帧执行：**

1. **校验** —— 司机 ID 在连接上完成认证，不信任帧内字段；坐标做边界检查；时间戳偏移超过 30 s 则拒绝。
2. **限流** —— 若司机位移小于 25 m *且* 距其上次持久化位置不足 3 s，则跳过本次写入。在典型城市移动场景下，这能减少约 40–60% 的 Redis 写入，且不损失匹配质量。
3. **批处理** —— 帧在每个分片内有界环形缓冲区中累积；刷新器每 50 ms 或在满 500 条时将其汇入一次 Redis pipeline。这把每秒 10,000 次往返变为每分片每秒约 20 个流水线批次。
4. **背压** —— 缓冲区有界且采用丢弃最旧（drop-oldest）策略。过期位置毫无价值，因此丢弃它是正确行为，而非数据丢失。丢弃数计入 `location_frames_dropped_total`；非零速率是一条告警，而不是静默失败。
5. **镜像** —— 该帧同时以 `acks=1`、`linger.ms=20`、lz4 压缩生产到 `driver.location.v1`。此处失败只记录日志并丢弃；遥测绝不能阻塞派单。

**断连处理。** 套接字关闭时，司机被标记为 `OFFLINE` 并从地理索引中移除。另一个独立的清理器（reaper）每 10 s 扫描一个按 `lastSeenAt` 排序的 `ZSET`，驱逐任何静默超过 30 s 的司机，以覆盖那些永远不会发出关闭帧的半开连接。

### 3.2 派单 API（Dispatch API）—— 请求受理

**职责：** 接收叫车请求、强制幂等，然后移交。它**不做任何匹配**——刻意保持轻薄，因此其延迟可预测。

```
POST /v1/rides
  Idempotency-Key: 8f14e45f-ea6a-4f2b-9f22-7c1e2b3a4d5e   (required)
  { "riderId": "...", "pickup": {...}, "dropoff": {...}, "vehicleClass": "STANDARD" }
  -> 202 Accepted { "rideId": "...", "status": "REQUESTED", "pollUrl": "/v1/rides/{id}" }

GET  /v1/rides/{rideId}          current state + assigned driver
POST /v1/rides/{rideId}/cancel   rider-initiated cancellation
POST /v1/drivers/{id}/offers/{offerId}/accept | /decline
GET  /v1/drivers/nearby?lat=&lng=&radiusKm=   debug/inspection endpoint
```

返回 `202` 而非阻塞到匹配完成，是关键的延迟决策：无论匹配耗时多久，API 的 p99 都保持在几十毫秒量级，匹配负载也永远不会导致请求超时。客户端轮询 `GET /v1/rides/{id}`，或订阅乘客 WebSocket 获取推送。

### 3.3 匹配引擎（Matching Engine）

**职责：** 把一个 `ride.requested` 事件转化为一次司机分配。

以 Kafka 消费者组方式运行。分区数（12）决定了最大有效并行度；每个分区按序处理，不同行程的请求彼此独立，因此水平扩容就是增加实例，直到分区耗尽。

**每个请求的算法：**

```
for radiusKm in [2.0, 3.2, 5.0]:                     # expanding search
    candidates = GEOSEARCH geo:drivers:{city}
                   FROMLONLAT lng lat BYRADIUS radiusKm km ASC COUNT 30 WITHCOORD WITHDIST

    candidates = filter(candidates, status == AVAILABLE
                                 and vehicleClass matches
                                 and lastSeenAt within 30s
                                 and not in rider's decline list)

    rank candidates by score (below)

    for driver in ranked[:5]:
        claim = redis.eval(CLAIM_LUA, driver.id, offerToken, ttl=10s)
        if claim.denied:                              # lost the race
            metrics.claimContention.inc()
            continue
        trip = tripService.assign(rideId, driver.id, claim.fence)   # DB is authority
        if trip.rejected:                             # unique index fired
            redis.release(driver.id, offerToken)
            continue
        offer = dispatchOffer(driver, ttl=8s)
        await offer.outcome                           # ACCEPTED | DECLINED | EXPIRED
        if ACCEPTED: publish ride.matched.v1; return
        else: release claim; record decline; continue

    sleep(backoff)                                    # let the driver pool refresh

publish ride.unmatched.v1                             # 30s SLA exhausted
```

**打分。** 按直线距离算“最近”是“按耗时最近”的弱代理，因此候选者按一个复合分数排序：

```
score = w1 * normalizedEtaSeconds          # haversine / class-average speed
      + w2 * (1 - driverAcceptanceRate)    # historically flaky drivers cost more
      + w3 * headingMismatchPenalty        # a driver pointing away must turn around
      + w4 * idleTimePenalty               # fairness: favor the longest-waiting driver
```

权重是配置而非代码中的常量，因此乘客等待时间与司机公平性之间的权衡可以在不重新部署的情况下调整。

**已文档化的扩展：** 上述贪心循环对单个请求是局部最优的。在高需求密度下，把请求按 2 秒窗口分批，并将分配问题作为二部图匹配（bipartite matching）求解（匈牙利算法），可显著降低平均接驾时间。此处仅作为未来的基准测试记录，不声称已实现。

### 3.4 行程服务（Trip Service）

拥有行程状态机和 Postgres。每次流转都对照一张显式的流转表校验；非法流转直接抛出异常而不是被强行纠正。每次成功的流转都会写入一行 `trip_events`（只追加的审计日志），而下游消费者需要响应的流转，还会在与行程更新**同一个事务**中写入一行 `outbox`。

两个输入，且这一拆分很重要。从 Kafka 它消费 `ride.matched.v1` 并使分配持久化——这正是 `uniq_driver_active_trip` 索引所仲裁的写入，也是系统中唯一会让该保证生效的地方。通过 HTTP 它服务于由司机或乘客直接驱动的四个生命周期流转（`accept`、`start`、`complete`、`cancel`）。

**这里也是司机重回司机池的地方。** 认领意味着一名司机被撤出匹配，而每个终态流转都会释放它——在 Postgres 提交之后，绝不在之前。有两道回路保障这一点：发件箱清空（200 ms）和一个认领对账器（30 s），后者释放 Postgres 认为没有行程持有的认领。该对账器只考虑没有 TTL 的认领，而这一条件只有 `markOnTrip` 会产生；仍带 TTL 的认领要么是一个在途报价，要么会自行过期，释放其中任何一种都会把一名司机从一桩即将落地的匹配中夺走。

Schema 归属：迁移文件位于 `libs/schema`，而不是任何一端服务里。现在有两个服务写这些表，而一份留在其某个消费者内部的 schema，意味着该消费者可以在另一方毫无察觉的情况下改动它。两者都从完全相同的一套文件运行 Flyway；Flyway 的咨询锁使并发启动是安全的。

### 3.5 通知与计费服务（Notification & Billing Services）

同一组主题上的两个独立消费者组。它们的存在是为了*证明*解耦：杀掉其中任何一个都不得影响匹配延迟，且重启后从各自已提交的 offset 重放，不丢失任何工作。负载测试明确包含“在运行中途杀掉通知服务”这一步。

---

## 4. 数据设计

### 4.1 Redis 键空间

| 键 | 类型 | 内容 | TTL |
|---|---|---|---|
| `geo:drivers:{city}` | GEO (zset) | member = driverId，score = geohash | — |
| `driver:{id}:state` | Hash | `status`、`vehicleClass`、`lastSeenAt`、`tripId`、`fence` | 60 s，持续刷新 |
| `driver:seen:{city}` | ZSET | score = lastSeenAt 的 epoch 毫秒——清理器的输入 | — |
| `claim:{driverId}` | String | 当前持有者的 offerToken | 10 s |
| `fence:counter` | String | 围栏令牌（fencing token）的单调 `INCR` 来源 | — |
| `offer:{offerId}` | Hash | rideId、driverId、expiresAt | 15 s |

`{city}` 是一个 hash tag，使一个城市的所有键落在同一个 Redis Cluster slot 上，从而保持 `GEOSEARCH` 单节点执行，并让 Lua 脚本（无法跨 slot）保持有效。

**司机状态：** `OFFLINE → AVAILABLE → RESERVED → ON_TRIP → AVAILABLE`。`RESERVED` 是从赢得认领到司机接受、或报价过期之间的短暂状态。

### 4.2 认领脚本（`claim.lua`）

```lua
-- KEYS[1] = driver:{id}:state   KEYS[2] = claim:{id}   KEYS[3] = fence:counter
-- ARGV[1] = offerToken          ARGV[2] = ttlMillis
if redis.call('HGET', KEYS[1], 'status') ~= 'AVAILABLE' then
  return {0, 'NOT_AVAILABLE'}
end
if redis.call('EXISTS', KEYS[2]) == 1 then
  return {0, 'ALREADY_CLAIMED'}
end
local fence = redis.call('INCR', KEYS[3])
redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
redis.call('HSET', KEYS[1], 'status', 'RESERVED', 'fence', fence)
return {1, fence}
```

Redis 在其单一命令线程上原子地执行 Lua：在检查与写入之间没有其他客户端能观察或修改状态。这正是让快速路径无需锁协议也能保持正确的关键。

### 4.3 PostgreSQL schema（核心部分）

```sql
CREATE TABLE trips (
    id             UUID PRIMARY KEY,
    rider_id       UUID NOT NULL,
    driver_id      UUID,
    status         trip_status NOT NULL,
    fence_token    BIGINT,
    -- Plain columns, not PostGIS GEOGRAPHY. ADR-0002 reserves PostGIS for geofences and
    -- zone analytics, and no such query exists yet; adding the extension before there is a
    -- spatial query to serve costs a database image change and buys nothing.
    pickup_lat     DOUBLE PRECISION NOT NULL,
    pickup_lng     DOUBLE PRECISION NOT NULL,
    dropoff_lat    DOUBLE PRECISION NOT NULL,
    dropoff_lng    DOUBLE PRECISION NOT NULL,
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    matched_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    version        INT NOT NULL DEFAULT 0   -- optimistic locking
);

-- THE correctness guarantee: one active trip per driver, enforced by the database.
CREATE UNIQUE INDEX uniq_driver_active_trip
    ON trips (driver_id)
    WHERE status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS');

CREATE TABLE idempotency_keys (
    key             TEXT PRIMARY KEY,
    request_hash    TEXT        NOT NULL,
    state           TEXT        NOT NULL,   -- IN_PROGRESS | COMPLETED
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    topic        TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE trip_events (          -- append-only audit trail
    id         BIGSERIAL PRIMARY KEY,
    trip_id    UUID NOT NULL REFERENCES trips(id),
    from_state trip_status,
    to_state   trip_status NOT NULL,
    reason     TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`uniq_driver_active_trip` 是让标题级主张成立的那一行。Redis 中的一切都是叠加在其之上的优化。如果评审者只读本仓库中的一行 SQL，就是这一行。

**悲观锁（pessimistic locking）** 在需求说明书要求之处用于司机发起的 accept/decline：`SELECT ... FROM trips WHERE id = ? FOR UPDATE` 将同一行程上的并发 accept 与 cancel 串行化。对一个短暂、有争用、单行的事务来说它是正确的工具——而对匹配搜索来说是错误的工具，这正是匹配改走 Redis 快速路径的原因。

---

## 5. 故障模式

只为正常路径做设计不算设计。下表每一行都有一条集成测试覆盖。

| 故障 | 影响 | 缓解措施 |
|---|---|---|
| Redis 宕机 | 没有地理索引；匹配停摆 | Postgres 约束仍能防止数据损坏。API 对匹配请求保持可用并返回 `503`；司机重连并在约 1 s 的帧内重建索引。AOF `everysec` 支持热重启。 |
| 认领租约在分配中途过期 | 另一个 worker 可能认领同一司机 | 围栏令牌：过期 worker 的 DB 写入携带更旧的令牌而被拒绝；唯一部分索引是最后兜底。 |
| Kafka 不可用 | 叫车请求无法入队 | API 返回 `503` 并带 `Retry-After`，而不是接收它无法处理的工作。位置镜像按设计静默降级。 |
| 消费者处理中途崩溃 | 事件被重新投递 | 消费者按事件 ID 幂等；分配经由唯一部分索引天然幂等。 |
| Postgres 主库故障切换 | 写入失败数秒 | 有界重试 + 带抖动的退避；发件箱保证任何已发布内容不丢失。 |
| 司机在 `RESERVED` 期间掉线 | 行程会卡住 | 报价 TTL（8 s）到期，认领被释放，匹配继续下一个候选者。 |
| 幽灵司机（半开套接字） | 被匹配到一名不可达的司机 | 30 s 心跳清理器将其驱逐；报价过期兜住那扇窄窗口。 |
| 毒消息 | 消费者陷入重新投递循环 | 退避重试 3 次，随后连同完整上下文路由到 `*.dlt`；消费者继续前进。 |
| 重连时的惊群效应 | 部署后的接入尖峰 | 客户端带抖动的重连，加上边缘处的连接速率限制器。 |

---

## 6. 测试策略

| 层级 | 范围 | 工具 |
|---|---|---|
| 单元 | 领域不变量、状态机、打分——不碰基础设施 | JUnit 5、AssertJ |
| 集成 | 每个测试类使用真实的 Redis / Postgres / Kafka | Testcontainers |
| **并发** | 200 个线程争抢 1 名司机；断言恰好 1 个胜出 | JUnit + `CountDownLatch` 屏障 |
| **幂等** | 同一幂等键 50 个并行重试只产生 1 个行程 | Testcontainers |
| 契约 | 事件 schema 向后兼容 | CI 中的 schema 兼容性检查 |
| 韧性 | 运行中途杀掉 Redis / Kafka，断言恢复 | Testcontainers + `docker pause` |
| 负载 | 全栈，10k 司机 + 5k 乘客 | k6 + 模拟器 |

并发测试是旗舰：一个 `CountDownLatch` 同时放行 N 个线程去争抢唯一一名可用司机，然后断言 `SELECT count(*) FROM trips WHERE driver_id = ? AND status IN (...)` 等于 1，且恰好只有一个线程收到成功。这是一个在认领路径上出现任何回归时都会大声失败的测试。

---

## 7. 路线图

| 阶段 | 交付物 |
|---|---|
| 0 | 设计文档、ADR、架构图 ✅ |
| 1 | Compose 基础设施 + Maven 多模块骨架 + 健康检查 ✅ |
| 2 | 领域模型、行程状态机、单元测试 ✅ |
| 3 | 位置服务：WebSocket 接入 → Redis GEO ✅ |
| 4 | 派单 API + 幂等 + Postgres schema ✅ |
| 5 | 匹配引擎 + 认领协议 + 并发测试 ✅ |
| 6 | Kafka 拓扑、发件箱发布器、通知/计费消费者 ✅ |
| 7 | Prometheus + Grafana 仪表盘 ✅ |
| 8 | 模拟器 + k6 负载测试架，发布结果 |

在第 7 与第 8 阶段之间：行程生命周期变得可触达。状态机、认领释放，以及那个把分配持久化的 `RideMatched` 消费者早已全部建成，却没有一个被接到任何调用方可触达的东西上——因此从未有行程离开过 `REQUESTED`，唯一部分索引从未在一个运行中的系统中被真正行使过，也从未有司机被交还给池中。对一个司机池单调流失的引擎做负载测试，测到的将是那道泄漏，而不是这个设计。
