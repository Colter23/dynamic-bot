# 多阶段构建：构建阶段
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# 复制 Gradle wrapper 和构建配置（支持 CI 缓存层）
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# 下载依赖（独立缓存层）。Docker 镜像只需要 Linux x64 的 Skiko 原生库。
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies --quiet -PskikoRuntimeTargets=linux-x64

# 复制源代码
COPY src ./src

# 构建 fat jar
RUN ./gradlew --no-daemon fatJar -PskikoRuntimeTargets=linux-x64

# 运行阶段
FROM eclipse-temurin:17-jre

LABEL maintainer="dynamic-bot"
LABEL description="dynamic-bot - 动态转发系统"

ARG APP_UID=1000
ARG APP_GID=1000

ENV APP_UID=${APP_UID}
ENV APP_GID=${APP_GID}
ENV APP_USER=dynamicbot
ENV APP_RUNTIME_DIR=/app/.runtime
ENV DYNAMIC_BOT_WEB_ADMIN_HOST=0.0.0.0
ENV HOME=/app/.runtime/home
ENV XDG_CACHE_HOME=/app/.runtime/cache
ENV TMPDIR=/app/.runtime/tmp

# 健康检查需要 curl；ca-certificates 保障 HTTPS 插件目录和媒体下载可用；gosu 用于初始化权限后降权运行
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates gosu && \
    rm -rf /var/lib/apt/lists/*

# 创建必要的目录结构。构建阶段使用系统 UID/GID，启动时再按 APP_UID/APP_GID 调整，避免基础镜像已占用 1000 导致构建失败。
RUN set -eux; \
    if ! getent group dynamicbot >/dev/null 2>&1; then \
        groupadd --system dynamicbot; \
    fi; \
    if ! id dynamicbot >/dev/null 2>&1; then \
        useradd --system --gid dynamicbot --home-dir /app/.runtime/home --no-create-home --shell /usr/sbin/nologin dynamicbot; \
    fi; \
    mkdir -p /app/.runtime/home /app/.runtime/cache /app/.runtime/tmp /app/data/images/source /app/data/images/draw /app/data/plugins /app/data/videos /app/data/login-qr /app/config /app/plugins /app/defaults/plugins && \
    chown -R dynamicbot:dynamicbot /app

# 内置官方插件目录，远程目录临时不可用时可作为兜底
COPY --chown=dynamicbot:dynamicbot plugins/catalog.json /app/defaults/plugins/catalog.json

WORKDIR /app

# 从构建阶段复制 jar
COPY --from=builder --chown=dynamicbot:dynamicbot /build/build/libs/*-all.jar ./dynamic-bot.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 暴露 Web Admin 默认端口
EXPOSE 2233

# 健康检查（支持自定义端口）
ENV HEALTH_CHECK_PORT=2233
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${HEALTH_CHECK_PORT}/api/health || exit 1

# 设置 JVM 参数
ENV JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 入口脚本会以 root 初始化挂载卷权限，然后用 gosu 降权运行 Java 主进程
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/dynamic-bot.jar"]
