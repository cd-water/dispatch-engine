#!/usr/bin/env bash
# 按 ADR-0003 创建主题拓扑。幂等：重复执行是空操作。
#
# broker 上的自动创建主题被刻意关闭——主题名拼错应当大声失败，
# 而不是按默认分区数悄悄新建一个主题。
#
# 在开发容器内运行（或任何 PATH 上带有 Kafka CLI 的主机）：
#     bash ops/kafka/create-topics.sh
#
# 或从 Windows 主机通过管道送入 broker 容器：
#     podman exec -i ride-matching-engine-kafka-1 bash -s < ops/kafka/create-topics.sh
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"

# 名称 : 分区数 : retention_ms : 额外配置
# 分区数决定了消费者并行度的上限；可丢弃的遥测数据与可审计的行程事件
# 在保留时间上差异很大。
TOPICS=(
  "driver.location.v1:12:3600000:compression.type=lz4"
  "ride.requested.v1:12:604800000:"
  "ride.matched.v1:12:604800000:"
  "ride.unmatched.v1:6:604800000:"
  "ride.completed.v1:12:604800000:"
  "ride.cancelled.v1:6:604800000:"
  "driver.location.v1.dlt:3:2592000000:"
  "ride.requested.v1.dlt:3:2592000000:"
  "ride.matched.v1.dlt:3:2592000000:"
)

echo "==> Broker: ${BOOTSTRAP}"

for entry in "${TOPICS[@]}"; do
  IFS=':' read -r name partitions retention extra <<< "$entry"

  args=(
    --bootstrap-server "$BOOTSTRAP"
    --create
    --if-not-exists
    --topic "$name"
    --partitions "$partitions"
    # 开发环境只有单个 broker；生产环境会将其提升为 3，并设置 min.insync.replicas=2。
    --replication-factor 1
    --config "retention.ms=${retention}"
  )
  [ -n "$extra" ] && args+=(--config "$extra")

  "${KAFKA_BIN}/kafka-topics.sh" "${args[@]}" >/dev/null
  printf "    %-28s partitions=%-3s retention=%sh\n" "$name" "$partitions" "$((retention / 3600000))"
done

echo
echo "==> Topics on broker:"
"${KAFKA_BIN}/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list | sed 's/^/    /'
