-- 记录是哪一次 Redis 认领（claim）在某条行程上占用了该司机。
--
-- 若没有这一列，认领就只能靠 TTL 过期来归还，而 markOnTrip() 会对认领标记调用 PERSIST
-- ——于是开始行程的司机不再有可过期的 TTL，将永远卡在 RESERVED 状态。释放必须依赖令牌：
-- 释放脚本会把令牌与标记进行比对，这样认领早已失效的工作节点就无法删除随后合法接管该司机的
-- 那个认领。
--
-- 允许为 NULL，因为行程只在 OFFERED 与其终止状态之间持有一个令牌。现有的每一行都是 REQUESTED，
-- 因此没有需要回填的数据。
--
-- 本迁移不伴随新增索引。按司机查询已由 uniq_driver_active_trip 覆盖，它是 (driver_id) 上的
-- 一个唯一索引，且恰好只覆盖那些占用司机的状态——在同一列同一谓词上再建第二个索引，
-- 只会是查询规划器永远不会选择的死重。
ALTER TABLE trips ADD COLUMN offer_token TEXT;

COMMENT ON COLUMN trips.offer_token IS
    'Opaque token of the Redis claim holding driver_id; required to release that claim.';
