#!/usr/bin/env bash
# 容器健康检查。用 bash 的 /dev/tcp 走 HTTP，因此镜像无需 curl 或 wget：
# 为一次 HTTP 调用安装软件包会多加一层，还会让镜像构建依赖 apt 镜像源可用，
# 这里已经因此失败过一次。
#
# 断言的是响应体内容为 UP，而不只是端口能接受连接——数据库连接已断开的
# 服务依然会在 8080 上应答并报告 DOWN。
set -euo pipefail

PORT="${HEALTHCHECK_PORT:-8080}"

exec 3<>"/dev/tcp/127.0.0.1/${PORT}"
printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
grep -q '"status":"UP"' <&3
