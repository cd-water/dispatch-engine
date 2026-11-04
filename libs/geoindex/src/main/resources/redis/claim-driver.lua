-- 为一个行程报价原子地认领一个司机（ADR-0004，第 1 层）。
--
-- Redis 在其单一命令线程上执行脚本，中间不会插入任何其他操作，所以下面的检查和写入无法被
-- 其他客户端切开。这正是它被写成脚本而不是三次往返的全部原因：在一个 GET 和一个 SET 之间，
-- 一个竞争的工作者可以轻松地挤进来。
--
-- 这是一个竞争优化器（CONTENTION OPTIMISER），不是正确性边界。一个司机至多持有一个进行中
-- 行程的保证，是 PostgreSQL 里的 uniq_driver_active_trip 索引。即使这个脚本被绕过、丢失，
-- 或者它的 key 空间被清空，正确性依然成立——受损的只是吞吐量，因为失败方会在数据库那里、
-- 而不是提前一次 Redis 往返发现冲突。
--
-- KEYS[1] 司机状态哈希            KEYS[2] 认领标记            KEYS[3] 围栏计数器
-- ARGV[1] 报价令牌（不透明）      ARGV[2] 认领 TTL（毫秒）
--
-- 返回 { 1, fence }        表示成功
--      { 0, reason }       表示被拒绝

local status = redis.call('HGET', KEYS[1], 'status')

-- 未知司机应被拒绝而不是被认领。不在索引中的司机要么已下线，要么作为失联司机被清除；
-- 认领他们只会派出一个无人接收的报价，并在乘客拿到下一个候选之前烧掉整个报价 TTL。
if not status then
  return { 0, 'UNKNOWN_DRIVER' }
end

if status ~= 'AVAILABLE' then
  return { 0, 'NOT_AVAILABLE' }
end

-- 存在有效的认领标记意味着另一个工作者刚刚获胜，其报价尚未尘埃落定。
if redis.call('EXISTS', KEYS[2]) == 1 then
  return { 0, 'ALREADY_CLAIMED' }
end

local fence = redis.call('INCR', KEYS[3])

-- TTL 是安全阀：一个在认领与派单之间死去的工作者，不能让司机被永远困住。认领只需自然
-- 过期，司机便会重新变为可用。
redis.call('SET', KEYS[2], ARGV[1], 'PX', tonumber(ARGV[2]))
redis.call('HSET', KEYS[1], 'status', 'RESERVED', 'fence', fence)

return { 1, fence }
