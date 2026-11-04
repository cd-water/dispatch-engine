# ADR-0005：叫车请求的幂等键

**状态：** 已接受 · **日期：** 2026-09-03

## 背景

`POST /v1/rides` 天然不是幂等（idempotent）的——调用两次就会创建两个行程（trip）。但网络决定了重试不可避免：客户端在服务端已提交之后才超时，移动应用在不稳定的连接上重试，负载均衡器重放（replay）请求。没有保护的话，乘客（rider）会被收取两次行程的费用，而两个司机被派往同一个上车点。

微妙之处在于，重试可能在**原始请求仍在处理中**时到达，因此"先读后写"的检查在读与写之间存在一个窗口，两个请求都会看到"未找到键"。

## 决策

要求所有创建行程的请求都携带 `Idempotency-Key` 请求头（由客户端生成的 UUID），并由一张 Postgres 表支撑，由其主键完成仲裁。

```sql
CREATE TABLE idempotency_keys (
    key             TEXT PRIMARY KEY,
    request_hash    TEXT        NOT NULL,   -- SHA-256 of the canonical request body
    state           TEXT        NOT NULL,   -- IN_PROGRESS | COMPLETED
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);
```

**算法**

```
INSERT INTO idempotency_keys (key, request_hash, state, expires_at)
VALUES (?, ?, 'IN_PROGRESS', now() + interval '24 hours')
ON CONFLICT (key) DO NOTHING;
```

- **插入成功** → 这是第一个请求。处理它，然后**在与行程创建相同的事务中**把该行更新为 `COMPLETED`，并写入响应状态与响应体。
- **插入冲突** → 读取已存在的行：

| 已存在的行 | 响应 |
|---|---|
| `request_hash` 不一致 | `422 Unprocessable Entity`——同一个键被复用（键复用）到了不同的请求上；这是客户端的 bug，必须显眼地报出来 |
| `state = IN_PROGRESS` | `409 Conflict` + `Retry-After: 1`——原始请求仍在执行 |
| `state = COMPLETED` | 原样重放（replay）**已存储**的 `response_status` 与 `response_body` |

过期的键由定时任务清理；24 小时之后的重试被视为一个新请求。

## 理由

- **数据库主键解决了竞态。** 两个同时发生的重试都会尝试插入；恰好只有一个成功。不存在窗口期，因为不存在单独的读取步骤。
- **存储的是响应本身，而不只是键**，意味着重试会拿回*同一个* `rideId`，而不是一句泛泛的"已处理过"——这正是客户端继续流程时真正需要的东西。
- **对请求体做哈希**能抓住客户端把同一个键复用到不同请求上这种危险的错误。静默返回第一次的响应会比报错更糟。
- **键状态与行程一起提交**使两者不会分叉。如果行程提交了而键的更新没有提交，重试就会再次执行并创建一个重复的行程——这正是要防止的那个 bug。
- 选 Postgres 而不是 Redis，因为这是一个正确性机制，必须在缓存被清空之后依然存在。

## 后果

**正面**

- 网络重试、负载均衡器重放以及不耐烦的客户端重复点击都无法创建重复的行程。
- 语义与 Stripe 那套被广泛理解的幂等性（idempotency）契约一致，因此 API 的行为符合有经验的集成者的预期。
- `409` 与 `422` 之分让客户端能以机器可读的方式分辨"等待"和"你有 bug"。

**负面**

- 每个行程请求多一次写入。相对于行程插入本身可以忽略不计。
- 键表会不断增长，需要定期清理。
- 客户端必须在多次重试之间生成并保存同一个键。这一点已写入 API 参考；模拟器和 k6 场景都正确地做到了，其中包含一个刻意设计的重复重试场景。

## 验证

`IdempotencyIT`：50 个线程同时发起完全相同的 `(key, body)`。断言——行程行恰好只有一行，每个响应携带相同的 `rideId`，且响应码只取自 `{202, 409, 200}`。
