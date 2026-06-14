# 多阶段构建：构建阶段
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# 复制 Gradle wrapper 和构建配置
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# 复制源代码
COPY src ./src

# 构建 fat jar
RUN chmod +x ./gradlew && ./gradlew --no-daemon fatJar

# 运行阶段
FROM eclipse-temurin:17-jre

LABEL maintainer="dynamic-bot"
LABEL description="dynamic-bot - 动态转发系统"

ARG APP_UID=1000
ARG APP_GID=1000

# 创建非 root 用户
RUN groupadd --gid ${APP_GID} dynamicbot && \
    useradd --uid ${APP_UID} --gid dynamicbot --create-home --shell /usr/sbin/nologin dynamicbot

# 健康检查需要 curl；ca-certificates 保障 HTTPS 插件目录和媒体下载可用
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# 创建必要的目录结构
RUN mkdir -p /app/data/images /app/data/plugins /app/config /app/plugins /app/defaults/plugins && \
    chown -R dynamicbot:dynamicbot /app

# 内置官方插件目录，远程目录临时不可用时可作为兜底
COPY --chown=dynamicbot:dynamicbot plugins/catalog.json /app/defaults/plugins/catalog.json

WORKDIR /app

# 从构建阶段复制 jar
COPY --from=builder /build/build/libs/*-all.jar ./dynamic-bot.jar

# 切换到非 root 用户
USER dynamicbot

# 暴露 Web Admin 默认端口
EXPOSE 2233

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:2233/api/health || exit 1

# 设置 JVM 参数
ENV JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 启动应用
ENTRYPOINT ["sh", "-c", "if [ ! -f /app/plugins/catalog.json ] && [ -f /app/defaults/plugins/catalog.json ]; then cp /app/defaults/plugins/catalog.json /app/plugins/catalog.json || true; fi; exec java $JAVA_OPTS -jar dynamic-bot.jar"]
