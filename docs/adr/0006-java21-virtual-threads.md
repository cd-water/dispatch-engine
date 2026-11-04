# ADR-0006：以 Java 21 虚拟线程（virtual threads）替代响应式栈

**状态：** 已接受 · **日期：** 2026-09-03

## 背景

位置服务（Location Service）必须维持约 10,000 个并发 WebSocket 连接，而匹配路径是一串阻塞式 I/O 调用（Redis → Postgres → Kafka）。按传统做法这会逼出一个二选一：每个连接一个平台线程（10k × 约 1 MB 栈内存，且调度器承受压力），或者一个从不阻塞但把所有业务逻辑重写成操作符链的响应式栈（Project Reactor / WebFlux）。

## 决策

**采用 Java 21 与虚拟线程**，运行在 Spring Boot MVC 上并开启 `spring.threads.virtual.enabled=true`；司机模拟器同样为每个模拟司机运行一个虚拟线程。

## 理由

虚拟线程起步只占约 1 KB 堆内存，其栈按需增长；当它在 I/O 上阻塞时，JVM 会把它从载体线程（carrier thread）上卸载，让载体线程去运行别的任务。它的可扩展性特征正是响应式那一套——一小池 OS 线程复用大量逻辑任务——但代码是普通的顺序式 Java：

```java
// Reads top to bottom. Stack traces are complete. A debugger steps through it.
var candidates = geoIndex.search(pickup, radiusKm);      // blocks
var claim      = claimService.tryClaim(candidate.id());  // blocks
var trip       = tripRepository.assign(rideId, claim);   // blocks
eventPublisher.publish(new RideMatched(trip));           // blocks
```

上面那段代码的响应式等价物是一条 `flatMap` 链：堆栈跟踪近乎无用，异常处理自成一套词汇，而且只要操作符内部不小心出现一次阻塞调用，就会悄悄卡住一条事件循环线程并拖垮整个服务。对于一个以*被评审者读懂并理解*为存在目的的代码库来说，为同等的吞吐量付出这个代价并不值得。

虚拟线程也让模拟器变得诚实：10,000 个模拟司机各自占用一个线程，各自跑一段直白的 `while (running) { move(); send(); sleep(1s); }` 循环，这正是对 10,000 个独立客户端的忠实建模。若在事件循环上模拟这一切，就意味着负载生成器自身会成为一个不容小觑的并发问题。

> **更新（2026-09-04）：**本 ADR 的决策不变，但运行时已从 Java 21 迁移到 Java 25 LTS，这解决了下文提到的固定（pinned）约束。测量数据见 [ADR-0008](0008-dependency-currency.md)。原文按原样保留。

## 已知的约束及其处理方式

- **固定（Pinning）。** 虚拟线程在 `synchronized` 块内阻塞时会固定（pinned）住它的载体线程。所有应用层加锁都使用 `ReentrantLock`，并且 `-Djdk.tracePinnedThreads=full` 在负载测试 profile 中运行以捕获回归。（JDK 24+ 移除了大多数固定场景；无论如何，这条纪律仍然保留。）
- **无界并发。** 虚拟线程很廉价，这让压垮下游系统变得容易。每一个出站依赖都位于一个显式的 `Semaphore` 或有界线程池之后——Redis 与 Postgres 的连接池才是真正的上限，并且是刻意设定大小的。
- **ThreadLocal 代价。** 当线程变成每请求一个时，按线程缓存就失去了意义。链路追踪上下文通过 Micrometer 的上下文传播来传递，而不是靠裸的 `ThreadLocal` 累积。

## 后果

**正面**

- 无需响应式重写即可支撑 10k WebSocket 连接；这一结论来自负载测试的实测，而非假设。
- 完整且有意义的堆栈跟踪——包括跨越 Kafka 消费者边界时。
- Testcontainers 集成测试是普通的阻塞式代码，不需要 `StepVerifier` 这类脚手架。

**负面**

- 要求 Java 21+；没有回退到 Java 17 LTS 的路径。
- 虚拟线程帮助的是*阻塞型*并发，而非 CPU 密集型工作。候选司机打分循环是 CPU 密集型的，因此它仍然运行在有界的平台线程池上。
- 经过实战检验的生产案例比 WebFlux 少。通过把 I/O 层藏在端口（ports）之后来缓解（[ADR-0001](0001-hexagonal-architecture.md)），因此响应式适配器仍然是一个可行的选项。

## 考虑过的备选方案

- **WebFlux / Reactor**——在这个规模上已被验证，如果团队本来就以响应式思维工作，它就是正确答案。因可读性与可调试性而被否决，而这两点在这里更重要。
- **平台线程加大线程池**——10k 连接约需 10 GB 栈内存。不可行。
- **直接使用 Netty**——控制力最大，代码量多得多，而且其中没有一点是本项目有意思的部分。
