# Dispatch Engine（派单引擎）

## 快速开始

前置要求：JDK 21、Maven 3.9+、Docker（含 compose）。

```bash
make up      # 构建 + 启动中间件 + 启动全部微服务
```

启动顺序：`dispatch-api` 先行（完成 Flyway 建表），就绪后其余服务并行启动。

| 服务 | 端口 |
|---|---|
| dispatch-api | 8081 |
| location-service | 8082 |
| matching-engine | 8083 |
| trip-service | 8084 |
| notification-service | 8085 |
| billing-service | 8086 |

常用命令：

```bash
make up                        # 启动中间件 + 全部微服务（含构建）
make down                      # 停止中间件 + 全部微服务（数据卷保留）
make app                       # 只启动全部微服务（中间件需已就绪）
make app-down                  # 只停止全部微服务
make infra                     # 只启动中间件（Postgres/Redis/Kafka）
make infra-down                # 只停止中间件（数据卷保留）
make infra-clean               # 清理数据卷（清空全部数据，慎用）
make build                     # 整体打包（跳过测试）
make start S=trip-service      # 只启动单个服务（先 make build）
make logs S=trip-service       # 跟看单个服务日志
make restart S=trip-service    # 重启单个服务
```

日志输出在 `logs/` 目录。