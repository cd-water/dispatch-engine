# ADR-0007：基于 Podman 的容器化（containerized）开发环境

**状态：** 已接受 · **日期：** 2026-09-03

## 背景

这套技术栈需要 JDK 21、Maven、Postgres、Redis 和 Kafka。把它们装到宿主机上意味着开发者机器与 CI 之间会出现版本漂移，会在注册表与 PATH 上留下一串难以清理的卸载残留，而且克隆这个项目的评审者无法复现它。

要求是宿主机上**只**运行 VS Code 和一个容器引擎；其余一切都生活在容器里，并随项目删除而消失。

## 决策

1. **以 Podman 作为容器引擎**，而不是 Docker Desktop。无 root、无守护进程、商用没有许可条款限制，宿主机上也没有需要特权的后台服务。
2. **一个 Dev Container**（`.devcontainer/devcontainer.json`），其中装有 JDK 21、Maven 和 Java 相关扩展。VS Code 附加（attach）到它之上；宿主机永远看不到 JDK。
3. **使用 compose 平级容器（compose siblings），而不是 Docker-in-Docker。**开发容器与基础设施容器是同一个 compose 网络（`ride-net`）上的对等体，分别声明在 `ops/docker-compose.yml` 与 `.devcontainer/docker-compose.dev.yml` 中。

## 为什么选择 siblings 而不是 `docker-in-docker`

最直观的做法——一个普通的开发容器加上 `docker-in-docker` feature，在沙箱*内部*启动 Postgres 和它的伙伴们——被否决了：

- **它与 rootless Podman 相互较劲。**DinD 需要一个特权容器来运行嵌套的守护进程。在 rootless Podman 下，这意味着嵌套用户命名空间加 `--privileged`，而这正是这套方案本来要避免的那种对宿主机的侵入，也是存储驱动与 cgroup 故障频出的著名根源。
- **每次重建容器都要重建整个世界。**嵌套容器及其卷都活在外层容器内部。重建开发容器会丢掉 Kafka 的日志目录和 Postgres 的数据目录。
- **compose 文件反正都得存在。**`ops/docker-compose.yml` 在 CI、负载测试以及 README 承诺的一条命令启动中都需要。把同一个文件复用为开发容器的基础，意味着开发环境与 CI 跑的是逐字节一致的服务定义，而不是两份会漂移的定义。
- **隔离性没有任何变化。**这些容器在同一个 rootless Podman machine 下互为平级。无论哪种方式都不会有任何东西落到宿主机上——唯一的区别是由哪个进程来监管它们。

当工作负载自身就要构建或运行容器（一个 CI runner、一套 Testcontainers 测试）时，Docker-in-Docker 的代价才是值得付出的。**Testcontainers 在本项目的测试策略之中**，它是唯一一个需要从开发容器内部拿到容器端点的东西。这一点通过把 Podman socket 挂载进开发容器来解决，而不是嵌套第二个守护进程——这一点推迟到 Phase 2 写出第一个集成测试时再做。

## 后果

**正面**

- 宿主机上只保留 VS Code 和 Podman。删掉项目目录并执行 `podman system prune -a`，所有痕迹即被清除。
- 同一个 compose 文件驱动开发容器、本地运行和 CI。
- 服务数据能在开发容器重建后保留下来，因为卷属于外层引擎。
- 贡献者得到完全一致的工具链，而 README 不必塞满安装说明。

**负面**

- 在 Windows 上要求 WSL 2：Podman 运行在一个 Linux 虚拟机（`podman machine`）里。这是一个真实的宿主机前提条件，也是这套方案唯一无法用容器化方式消掉的东西。
- Podman 的 Docker API 兼容性不错但并不完美；VS Code 需要把 `dev.containers.dockerPath` 指向 `podman`，而 rootless 的绑定挂载有时需要 `userns_mode: keep-id`。两者都记录在 [DEV_ENVIRONMENT.md](../DEV_ENVIRONMENT.md) 中。
- 绑定挂载的源码要跨越 Windows → 虚拟机的文件系统边界，比原生 I/O 慢。缓解办法是把 Maven 仓库放在命名卷里，而不是放在绑定挂载上。

## 考虑过的备选方案

- **Docker Desktop**——Dev Containers 体验遥遥领先地最顺滑，但会安装一个需要特权的后台服务，且商用带有许可条款。基于隔离性目标被否决。
- **宿主机安装 JDK + Maven，容器只跑基础设施**——更简单，也是大多数人的做法。被否决，因为它把工具链放回了宿主机，并重新引入与 CI 的版本漂移。
- **完整虚拟机（Vagrant/Multipass）**——隔离更强，但沉重得多，而且失去了让 Dev Containers 值得一用的编辑器集成。
