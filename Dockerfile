# syntax=docker/dockerfile:1.7
# Smart RAG 应用镜像 —— 多阶段构建
# 设计要点：
#   1. 构建阶段用 JDK，运行阶段只用 JRE，镜像体积从 ~700MB 降到 ~280MB
#   2. 先 COPY pom.xml + mvnw 单独跑 dependency:go-offline，依赖未变时命中 Docker 层缓存
#   3. 运行容器以非 root 用户 app (UID 1000) 跑，符合容器安全基线
#   4. -XX:MaxRAMPercentage + UseContainerSupport 让 JVM 感知 cgroup 内存限制，避免 OOM

# ========== Stage 1: builder ==========
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# 拷构建工具 + pom + 源码
# Windows Docker Desktop COPY 到 Linux 容器会丢失可执行位，必须显式 chmod
# Windows 签出的 mvnw 可能是 CRLF 行尾，Linux 容器里 shebang #!/bin/sh\r 会让内核找不到解释器
# （报错现象是 "./mvnw: not found"，实际是解释器名带 \r）
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
COPY src ./src
RUN chmod +x mvnw && sed -i 's/\r$//' mvnw

# 打包 + 分层 extract 一次完成
# - mount type=cache 让 /root/.m2 在多次构建间持久化，首次下载后续命中缓存
# - 不单独跑 dependency:go-offline：Spring Boot BOM 下它无法 100% 预热传递依赖，
#   与 package 步骤重复下载，省掉这步反而更快（少一次 JVM 冷启动）
# - layertools extract 按依赖/loader/snapshot/application 拆分，供 runtime 阶段分层 COPY
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests clean package \
    && java -Djarmode=layertools -jar target/smart-rag-*.jar extract --destination target/extracted

# ========== Stage 2: runtime ==========
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# 安装 curl 用于容器内 healthcheck（基础镜像没有 wget/curl），然后清理 apt 缓存减小层体积
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 创建非 root 用户：UID/GID 1000 是 Debian/Ubuntu 上首个普通用户的标准位
RUN groupadd --system --gid 1000 app \
    && useradd --system --uid 1000 --gid app --home-dir /app --shell /usr/sbin/nologin app

# 分层拷贝：layertools 会按 dependencies/spring-boot-loader/snapshot-dependencies/application 拆分，
# 业务代码改动只重建最后一层，前几层命中缓存
COPY --from=builder --chown=app:app /build/target/extracted/dependencies/ ./
COPY --from=builder --chown=app:app /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=app:app /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /build/target/extracted/application/ ./

USER app
EXPOSE 8080

# 容器内存上限建议在 docker-compose 里通过 mem_limit 设为 2.5g
# MaxRAMPercentage=75 让 JVM heap 上限 = 2.5g * 75% ≈ 1.9g，剩余给 Metaspace/直接内存/线程栈/代码缓存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
    -XX:+UseContainerSupport \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -Djava.security.egd=file:/dev/./urandom"

# log4j2.xml shutdownHook="disable" + Spring graceful shutdown 双保险，避免日志丢失
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
