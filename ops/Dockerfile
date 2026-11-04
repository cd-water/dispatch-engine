# 所有服务共用一份镜像定义；服务名作为构建参数传入。
#
# 这里打包的是 Maven 已经构建好的 jar，而不是在镜像内部构建。
# 多阶段构建虽然自包含，但每构建一个服务都要重新编译整个 reactor 一次——
# 共六次——而 jar 是构建产物，并非源码输入。只构建一次、打包六次
# 既更快，也更符合 CI 的实际工作方式。
#
#   ./mvnw package -DskipTests
#   podman compose -f ops/docker-compose.yml --profile app up -d --build

FROM eclipse-temurin:25-jre-noble

ARG SERVICE
ENV SERVICE=${SERVICE}

# 刻意不安装任何软件包。构建时去请求 apt 会让镜像构建依赖某个镜像源可用
# ——这里已经因为包索引过期失败过一次——而且只为一次 HTTP 调用就多加一层。
# 因此 docker-compose.yml 里的健康检查改用 bash 内建的 /dev/tcp。

# 以非 root 运行：这里没有任何东西需要特权，而以 root 运行的容器
# 离"逃逸后殃及宿主机"只差一个容器逃逸漏洞。
RUN useradd --system --create-home --uid 10001 app
USER app
WORKDIR /app

COPY --chown=app:app services/${SERVICE}/target/${SERVICE}-*-exec.jar /app/app.jar
COPY --chown=app:app --chmod=755 ops/healthcheck.sh /app/healthcheck.sh

# 只是默认值，不是最终决定：docker-compose.yml 会按服务覆盖 JAVA_OPTS，
# 为三个有 p99 SLO 的服务选择 ZGC，为只做日志和计数的三个服务选择 SerialGC。
#
# MaxRAMPercentage 能感知容器——JVM 会读取 cgroup 限制——但这只有在
# 限制确实存在时才有用。没有限制时，JVM 会看到整台虚拟机，并认为自己可以
# 增长到好几 GB，六个服务正是这样把机器内存耗尽的。compose 中每个服务都
# 正是为此设置了 mem_limit。
#
# 用 50 而不是 70：该限制覆盖的是堆加上 metaspace、代码缓存、GC 元数据、
# 线程栈和直接内存缓冲区。一个小型 Spring Boot 服务的非堆内存约为 150-200MB，
# 所以在 320MB 限制里放 70% 的堆会直接被 OOM 杀掉，而不只是紧张。
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=50"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
