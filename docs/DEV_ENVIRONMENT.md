# 开发环境

宿主机上**只安装 VS Code 和 Podman**。JDK 25、Maven、Postgres、Redis 和 Kafka 全部运行在容器中，不会在机器上留下任何痕迹。设计依据见 [ADR-0007](adr/0007-containerized-dev-environment.md)。

```
[ Windows host ]
      │  VS Code + Podman CLI only
      ▼
[ podman machine — a WSL 2 Linux VM ]
      │
      ├── workspace container   JDK 25, Maven, Git   <- VS Code attaches here
      ├── postgres:16-alpine    :5432
      ├── redis:7-alpine        :6379
      ├── kafka (KRaft)         :9092 internal / :29092 from Windows
      └── prometheus + grafana  (profile: observability)
```

这些容器都是**同一网络上的兄弟节点（sibling）**，而不是彼此嵌套 —— 为什么拒绝 Docker-in-Docker 见 ADR-0007。

---

## 一次性宿主机准备

已于 2026-09-03 在 Podman 6.0.2（rootless，WSL 后端，4 CPU / 8 GiB）和 Docker Compose v5.5.1 上验证可用：
Postgres 16.15、Redis 7（已确认 `GEOSEARCH` 可用）和 Kafka 3.8.1 全部健康，完整主题拓扑（topic topology）已创建，
一次 produce/consume 往返通过。

### 1. WSL 2

Podman 在 Windows 上需要一个 Linux 虚拟机。在**管理员** PowerShell 中执行：

```powershell
wsl --install --no-distribution
```

`--no-distribution` 只安装 WSL 平台，而不安装一个你永远不会用到的 Ubuntu —— `podman machine` 会创建它自己的最小发行版。
**之后请重启**，然后确认：

```powershell
wsl --status
```

### 2. Podman 引擎

Podman Desktop 只是图形界面，并不包含引擎。安装 CLI：

```powershell
winget install --exact --id RedHat.Podman --source winget
```

`--source winget` 很重要：不加的话，winget 会停下来要求你接受本软件包并不需要的 `msstore` 源协议。

打开一个**新的**终端（PATH 变更需要全新的 shell 才生效），然后创建并启动虚拟机：

```powershell
podman machine init --cpus 4 --memory 8192 --disk-size 60
podman machine start
podman info
```

Kafka 加 Postgres 再加一个 JVM 同处一个虚拟机非常吃内存；8 GB 是现实的下限，4 GB 会在负载测试（load testing）时导致 broker 被 OOM 杀掉。

### 3. 一个 compose 实现

`podman compose` 只是一个垫片（shim）—— 它会把工作委托给真正的 compose 二进制，如果没装任何 compose 就会报
"looking up compose provider failed"。

```powershell
winget install --exact --id Docker.DockerCompose --source winget
```

相比 `podman-compose`，Compose v2 才是正确的选择：本项目的 compose 文件依赖
`depends_on: condition: service_healthy` 和 `profiles`，这两者 Compose v2 完整支持，而第三方 `podman-compose` 历史上并不支持。
安装后，`podman compose` 会自动检测到它，并把 `DOCKER_HOST` 指向 machine 的 socket；直接调用 `docker-compose` 是找不到 Podman 的。

### 4. 让 VS Code 指向 Podman

在 VS Code 的设置 JSON 中（`Ctrl+Shift+P` → *Preferences: Open User Settings (JSON)*）：

```json
{
  "dev.containers.dockerPath": "podman",
  "dev.containers.dockerComposePath": "podman-compose"
}
```

如果没有 `podman-compose`，请安装它（`pip install podman-compose`），或者把该值设为 `podman compose` 以使用 Podman
内置的 `podman compose` 垫片。

---

## 日常使用

在 VS Code 中打开项目文件夹 → 出现提示时选择 *Reopen in Container*（或 `Ctrl+Shift+P` →
**Dev Containers: Reopen in Container**）。

首次构建会拉取镜像并安装 Maven —— 需要几分钟。之后的打开只需几秒。

成功后，`post-create.sh` 会打印工具链版本并对全部三个服务做 TCP 检查。如果它报告 `FAIL`，多半是容器还在启动中；重新运行它即可：

```bash
bash .devcontainer/post-create.sh
```

### 不用 VS Code

基础设施可以独立运行：

```bash
podman compose -f ops/docker-compose.yml up -d
```

加上可观测性（默认关闭，以保持启动轻量）：

```bash
podman compose -f ops/docker-compose.yml --profile observability up -d
```

### 两种模式，绝不同时运行

虚拟机的内存预算是固定的，而填满它有两种方式。**同时运行两种会把预算耗尽** ——
这已经把机器搞挂过一次，症状是容器卡在 "closing" 状态，而不是优雅地变慢。

| 模式 | 运行内容 | 预算 |
|---|---|---|
| **开发** | 基础设施 + 开发容器 | ~1.2 GB + ~2.8 GB = **~4.0 GB** |
| **完整系统** | 基础设施 + 应用服务 + 可观测性 | 1216 + 2560 + 704 = **~4.5 GB** |
| 两者都开 | 全部 | **~7.3 GB** — 超出上限 |

每个服务都带显式的 `mem_limit`。这不是为了整洁：`MaxRAMPercentage` 是容器感知的，如果不设限制，JVM 会读取虚拟机的总内存，
并得出它可以把堆增长到数 GB 的结论。六个服务都这么做就是那次 OOM 的原因。

**启动完整系统之前先关闭 VS Code 窗口。** `shutdownAction: stopCompose` 会停止开发容器，而下面那条 compose 命令会把基础设施作为依赖重新拉起来。

### 完整系统

服务放在 `app` profile 后面，这样日常的开发容器工作就不必重建六个镜像。先打 jar，再建镜像：

```bash
./mvnw package -DskipTests
podman compose -f ops/docker-compose.yml --profile app --profile observability up -d --build
```

| | |
|---|---|
| Grafana | `localhost:3000` — 匿名 admin，"Ride Matching" 文件夹 |
| Prometheus | `localhost:9090` — 如果某个面板是空的，查看 Status → Targets |
| 服务 | `localhost:8080`–`8085` |

### 手动驱动一个行程

`dispatch-api` 创建行程（trip）；`trip-service` 推动它流转。先发起叫车请求（ride request），再拿它的
`rideId` 走完生命周期：

```bash
curl -X POST localhost:8083/v1/trips/$RIDE_ID/accept
```

```bash
curl -X POST localhost:8083/v1/trips/$RIDE_ID/start
```

```bash
curl -X POST localhost:8083/v1/trips/$RIDE_ID/complete
```

取消需要一个原因，并且一旦行程处于 `IN_PROGRESS` 就会被拒绝：

```bash
curl -X POST localhost:8083/v1/trips/$RIDE_ID/cancel -H 'Content-Type: application/json' -d '{"reason":"rider changed their mind"}'
```

`accept` 只对已到达 `OFFERED` 状态的行程生效，这意味着匹配引擎（matching engine）必须已经找到司机 —— 所以 Redis 索引中必须先有至少一个司机。`GET
/v1/trips/{id}` 显示当前状态和被指派的司机。

全部六个服务都在 `app` profile 中。`trip-service` 还只是脚手架时曾放在 `app-full` 后面；现在它拥有行程生命周期和认领（claim）释放逻辑，
如果运行这套栈时缺了它，被匹配到的司机永远不会被归还 —— 司机池会被耗尽，匹配率悄悄下降。

代码改动后重建单个服务：

```bash
./mvnw package -DskipTests -pl services/dispatch-api -am
podman compose -f ops/docker-compose.yml --profile app up -d --build dispatch-api
```

### Kafka 主题

broker 设置了 `auto.create.topics.enable=false`，因此主题名拼错时会大声失败，而不是悄悄创建一个使用默认分区（partition）数的新主题。按
[ADR-0003](adr/0003-kafka-topology-and-outbox.md) 创建拓扑：

```bash
podman exec -i ride-matching-engine-kafka-1 bash -s < ops/kafka/create-topics.sh
```

该脚本是幂等的（`--if-not-exists`），所以在新增主题后重跑是安全的。

**请从 Git Bash 运行，而不是 PowerShell。** 在 PowerShell 5.1 中把文件管道输入容器会前置一个 UTF-8 BOM 并把换行改写为 CRLF，
这会导致 shebang 行报错 `#!/usr/bin/env: No such file or directory`，并留下零散的 `$'\r'` 错误。在开发容器内，
直接运行 `bash ops/kafka/create-topics.sh` 即可。

另请注意 Git Bash 会改写容器绝对路径：`podman exec ... /opt/kafka/bin/...` 会变成
`C:/Program Files/Git/opt/kafka/...`。向容器内传绝对路径时，请给命令加前缀 `MSYS_NO_PATHCONV=1`。

---

## 连接参考

| 服务 | 从容器内部 | 从 Windows |
|---|---|---|
| PostgreSQL | `postgres:5432` | `localhost:5432` |
| Redis | `redis:6379` | `localhost:6379` |
| Kafka | `kafka:9092` | `localhost:29092` |
| Prometheus | `prometheus:9090` | `localhost:9090` |
| Grafana | — | `localhost:3000`（匿名 admin） |

凭据是 `ride` / `ride` / 数据库 `ride`。这是刻意硬编码的：这套栈只在本机运行且随时可丢弃，为它发明一套密钥管理纯属做戏。

**两个 Kafka 端口不可互换。** 连到错误 listener 上的客户端会收到一个元数据响应，其中广播的是它无法解析的主机名，然后一直挂起直到超时 ——
一个起因很无聊、症状却很令人困惑的失败。

---

## 故障排查

**`podman machine start` 失败，或 WSL 报错**
确认 BIOS/UEFI 中已启用虚拟化，并且 `wsl --status` 报告的是版本 2。在某些机器上 Hyper-V
与 WSL 2 可能冲突；本项目支持的后端是 WSL 2。

**`EACCES` / 写入 `/workspace` 时权限被拒**
Rootless Podman 会把容器内的 root 映射到你的宿主机用户，但开发容器以 `vscode`（uid 1000）运行。取消
[.devcontainer/docker-compose.dev.yml](../.devcontainer/docker-compose.dev.yml) 中 `userns_mode: "keep-id"` 的注释并重建。
这是 Podman 独有的；Docker 会拒绝该值。

**在 SELinux 主机上 bind mount 为空或不可读**
卷挂载上的 `:z` 后缀负责重打标签。已经设置好了；如果你新增挂载点，请保持一致。

**Kafka 容器不断重启**
几乎总是内存问题。用 `podman machine inspect` 查看虚拟机的分配，并确认 replication-factor 相关变量都是 `1` ——
单节点 broker 无法满足 replication factor 为 3 的要求，broker 会直接退出而不是降级运行。

**VS Code 找不到容器引擎**
必须设置 `dev.containers.dockerPath`（第 3 步）。修改后请彻底重启 VS Code；扩展是在激活时读取该设置的。

**集成测试挂起 60 秒后报 "Timed out waiting for container port to open" 失败**
测试启动的容器是开发容器的兄弟节点，所以它们发布的端口出现在 Podman 虚拟机的宿主网卡上，而不是开发容器的 loopback 上 ——
而 Testcontainers 默认查找的正是 loopback。在
[docker-compose.dev.yml](../.devcontainer/docker-compose.dev.yml) 中设置 `TESTCONTAINERS_HOST_OVERRIDE=host.containers.internal` 可解决。
如果你遇到这个错误，说明该容器早于这项设置：重建它即可。

**测试输出中出现 "Ryuk has been disabled"**
这是预期行为。Ryuk 是 Testcontainers 用来在 JVM 崩溃后做清理的清理器（reaper）容器，它需要 rootless Podman 不会授予的权限。
容器改由 JVM 的 shutdown hook 来移除。被硬杀掉的测试运行可能留下零散容器；`podman ps -a` 可以看到它们。

**一切都坏了，你想要一个干净的起点**

```bash
podman compose -f ops/docker-compose.yml down -v
podman system prune -a
```

`down -v` 会删除命名卷，因此 Postgres 数据和 Kafka 日志会一并消失。在开发环境这通常正是你想要的，
而在任何其他环境这永远不是你想要的。
