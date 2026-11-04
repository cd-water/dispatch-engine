# ADR-0001：多模块 Maven 构建中的六边形架构（hexagonal architecture）

**状态：** 已采纳 · **日期：** 2026-09-03

## 背景

系统有六个可部署的服务共享同一个领域模型（domain），并且必须能在 Redis、Kafka、Postgres 都未运行的情况下完成测试。这类项目有两种常见的失败模式：（a）把所有东西塞进一个 Spring 包，领域逻辑与 `@Entity`、`@KafkaListener` 注解纠缠在一起；（b）过早地微服务蔓延，每个服务都有自己独立的仓库、构建和漂移。

## 决策

采用**多模块 Maven 构建**，其中包含可独立部署的 Spring Boot 服务，且每个服务内部按**端口与适配器（ports and adapters）**组织：

```
domain/        entities, value objects, invariants     — zero framework imports
application/   use cases + port interfaces             — orchestration only
adapters/in/   REST controllers, WS handlers, Kafka consumers
adapters/out/  Redis, Postgres, Kafka producer implementations
```

依赖规则指向内部：`adapters → application → domain`。领域模块的 classpath 上没有 Spring、没有 Jackson、没有 JPA——这由 CI 中的 ArchUnit 强制执行，而不是靠自觉。

## 后果

**正面**

- `TripStateMachine`、司机打分和认领决策逻辑都是毫秒级跑完的纯 Java 单元测试。缓慢的 Testcontainers 套件只覆盖适配器。
- 把 Redis GEO 换成 PostGIS 只是 `DriverLocationIndex` 背后的适配器改动，而不是重写——这也让针对同一个端口（port）*对比评测*两套实现成为可能。
- 一条 `mvn verify` 构建并测试全部内容；无需跨仓库的版本协调。

**负面**

- 相比扁平布局，包和接口更多；对小型代码库而言是真实的开销，之所以接受，是因为展示边界纪律本身就是本项目的一部分。
- 共享的 `libs/domain` 在服务之间制造了耦合。缓解手段是严格限制它只包含与基础设施无关的纯模型类型，并在 `libs/events` 中单独对事件 schema 做版本管理。

## 考虑过的备选方案

- **单个 Spring Boot 单体。** 构建最简单，但 Kafka 解耦的故事就成了一句空话——如果通知消费者与匹配共享同一个进程，你就无法演示"杀掉通知消费者不影响匹配"。
- **每个服务独立仓库。** 在公司规模下是现实做法，但对想在一处读完整个系统的评审者并不友好。
