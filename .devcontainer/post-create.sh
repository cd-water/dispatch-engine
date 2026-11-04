#!/usr/bin/env bash
# 在 Dev Container 创建完成后运行一次。校验工具链，并确认每一条基础设施依赖都真正可达，
# 这样损坏的环境会在这里以清晰的消息失败，而不是稍后在令人费解的构建错误中失败。
set -euo pipefail

# Podman 创建的命名卷挂载点归 root 所有，但这个容器以 "vscode"（uid 1000）运行。
# 没有这一步，第一次运行 `mvn` 就会失败，报
# "Could not create local repository at /home/vscode/.m2/repository"。
# 这一步开销极小且幂等，因此无条件执行，而不是写成一篇文档里的手动步骤、
# 让人在每次重建时都撞上一次。
if [ ! -w "${HOME}/.m2" ]; then
  echo "==> Taking ownership of ${HOME}/.m2 (root-owned volume mount)"
  sudo chown -R "$(id -u):$(id -g)" "${HOME}/.m2"
fi

echo "==> Toolchain"
java -version 2>&1 | sed 's/^/    /'
mvn -version 2>&1 | head -1 | sed 's/^/    /'
git --version | sed 's/^/    /'

echo
echo "==> Dependency reachability"

check() {
  local name="$1" host="$2" port="$3"
  if timeout 5 bash -c "cat < /dev/null > /dev/tcp/${host}/${port}" 2>/dev/null; then
    echo "    OK    ${name} (${host}:${port})"
  else
    echo "    FAIL  ${name} (${host}:${port}) unreachable"
    return 1
  fi
}

failed=0
check "PostgreSQL" postgres 5432 || failed=1
check "Redis"      redis    6379 || failed=1
check "Kafka"      kafka    9092 || failed=1

echo
if [ "$failed" -ne 0 ]; then
  echo "One or more dependencies are unreachable. The containers may still be starting;"
  echo "re-run with:  bash .devcontainer/post-create.sh"
  exit 1
fi

echo "==> Environment ready."
echo "    Postgres  jdbc:postgresql://postgres:5432/ride  (user/pass: ride/ride)"
echo "    Redis     redis:6379"
echo "    Kafka     kafka:9092        (from Windows: localhost:29092)"
echo
echo "    Observability is not running by default. To start it:"
echo "      podman compose -f ops/docker-compose.yml --profile observability up -d"
