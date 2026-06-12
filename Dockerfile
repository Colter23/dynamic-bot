# 多阶段构建：构建阶段
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# 复制 Gradle wrapper 和构建配置
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./

# 复制源代码
COPY src ./src

# 构建 fat jar
RUN ./gradlew --no-daemon fatJar

# 运行阶段
FROM eclipse-temurin:17-jre

LABEL maintainer="dynamic-bot"
LABEL description="dynamic-bot - 动态转发系统"

# 创建非 root 用户
RUN groupadd -r dynamicbot && useradd -r -g dynamicbot dynamicbot

WORKDIR /app

# 创建必要的目录结构
RUN mkdir -p data/images data/plugins config plugins && \
    chown -R dynamicbot:dynamicbot /app

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
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar dynamic-bot.jar"]
