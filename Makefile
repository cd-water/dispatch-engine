SERVICES := dispatch-api location-service matching-engine trip-service notification-service billing-service
REST     := $(filter-out dispatch-api,$(SERVICES))
COMPOSE  := docker compose -f deploy/docker/docker-compose.yml
JVM_OPTS := -Xmx512m

.DEFAULT_GOAL := help

## build: 整体打包（跳过测试）
build:
	mvn -q package -DskipTests

## up: 启动中间件 + 全部微服务（含构建，dispatch-api 先行完成 Flyway）
up: build infra app

## down: 停止中间件 + 全部微服务（数据卷保留）
down: app-down infra-down

## app: 启动全部微服务（不构建，需中间件已就绪）
app:
	@mkdir -p logs
	@echo "starting dispatch-api..."
	@nohup java $(JVM_OPTS) -jar services/dispatch-api/target/dispatch-api-*.jar >logs/dispatch-api.log 2>&1 &
	@n=0; until bash -c 'echo > /dev/tcp/localhost/8081' 2>/dev/null; do \
	  n=$$((n+1)); \
	  if [ $$n -gt 60 ]; then echo "dispatch-api 60s 未就绪，查看 logs/dispatch-api.log"; exit 1; fi; \
	  sleep 1; \
	done
	@echo "dispatch-api ready, starting the rest..."
	@for s in $(REST); do \
	  nohup java $(JVM_OPTS) -jar services/$$s/target/$$s-*.jar >logs/$$s.log 2>&1 & \
	  echo "  started $$s"; \
	done
	@echo "all up. ports 8081-8086, logs in ./logs/"

## app-down: 停止全部微服务（不动中间件）
app-down:
	@pkill -f 'services/.*-0[.]0[.]1-SNAPSHOT[.]jar' && echo "apps stopped" || echo "no running services"

## infra: 启动中间件（Postgres/Redis/Kafka）
infra:
	$(COMPOSE) up -d

## infra-down: 停止中间件（数据卷保留）
infra-down:
	$(COMPOSE) down

## infra-clean: 清理数据卷（先停微服务，再清空 Postgres/Redis/Kafka 数据，慎用）
infra-clean: app-down
	$(COMPOSE) down -v

## start: 只启动单个服务（先 make build）make start S=trip-service
start:
	@if [ -z "$(S)" ]; then echo "用法: make start S=trip-service"; exit 1; fi
	@mkdir -p logs
	@nohup java $(JVM_OPTS) -jar services/$(S)/target/$(S)-*.jar >logs/$(S).log 2>&1 & \
	  echo "started $(S)"

## logs: 跟看单个服务日志 make logs S=trip-service
logs:
	@if [ -z "$(S)" ]; then echo "用法: make logs S=trip-service"; exit 1; fi
	@tail -f logs/$(S).log

## restart: 重启单个服务 make restart S=trip-service
restart:
	@if [ -z "$(S)" ]; then echo "用法: make restart S=trip-service"; exit 1; fi
	@pkill -f "$(S)-[0-9].*\.jar" || true
	@sleep 1
	@$(MAKE) --no-print-directory start S=$(S)

## help: 显示可用目标
help:
	@awk '/^## /{gsub(/^## /,"");print "  "$$0}' $(MAKEFILE_LIST)

.PHONY: build up down app app-down infra infra-down infra-clean start logs restart help
