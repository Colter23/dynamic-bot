# Docker 部署指南

本文档介绍如何使用 Docker 部署 dynamic-bot。

## 快速开始

### 使用 Docker Compose（推荐）

1. **前置要求**
   - Docker 20.10+
   - Docker Compose 2.0+

2. **启动服务**

   ```bash
   # 在 dynamic-bot 目录执行
   docker-compose up -d
   ```

3. **查看日志**

   ```bash
   docker-compose logs -f dynamic-bot
   ```

4. **停止服务**

   ```bash
   docker-compose down
   ```

5. **访问 Web 管理后台**

   首次启动后，检查 `config/main.yml` 文件获取自动生成的 admin token：

   ```bash
   cat config/main.yml | grep token
   ```

   使用 token 访问：`http://localhost:2233`

## 配置说明

### 端口映射

默认映射 Web Admin 端口：`2233:2233`

如需修改宿主机端口，编辑 `docker-compose.yml`：

```yaml
ports:
  - "8080:2233"  # 宿主机端口:容器端口
```

### 内存配置

默认 JVM 内存配置：`-Xms256m -Xmx1g`

根据实际需求调整 `docker-compose.yml` 中的 `JAVA_OPTS`：

```yaml
environment:
  - JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

### 数据持久化

容器使用以下目录挂载来持久化数据：

| 宿主机路径 | 容器路径 | 说明 |
|-----------|---------|------|
| `./data` | `/app/data` | 数据库、插件私有状态、图片缓存 |
| `./config` | `/app/config` | 配置文件 |
| `./plugins` | `/app/plugins` | 插件 JAR 文件 |

**注意**：首次运行前，请确保这些目录已创建：

```bash
mkdir -p data config plugins
```

## 插件安装

1. 将插件 JAR 文件放入 `plugins/` 目录
2. 重启容器使插件生效：

   ```bash
   docker-compose restart dynamic-bot
   ```

## 仅使用 Dockerfile

如果不使用 Docker Compose，可以直接使用 Dockerfile：

1. **构建镜像**

   ```bash
   # 在 dynamic-bot 目录执行
   docker build -t dynamic-bot:latest .
   ```

2. **运行容器**

   ```bash
   docker run -d \
     --name dynamic-bot \
     -p 2233:2233 \
     -v $(pwd)/data:/app/data \
     -v $(pwd)/config:/app/config \
     -v $(pwd)/plugins:/app/plugins \
     -e JAVA_OPTS="-Xms256m -Xmx1g" \
     --restart unless-stopped \
     dynamic-bot:latest
   ```

3. **查看日志**

   ```bash
   docker logs -f dynamic-bot
   ```

4. **停止容器**

   ```bash
   docker stop dynamic-bot
   docker rm dynamic-bot
   ```

## 健康检查

容器内置健康检查，每 30 秒通过访问 `/api/health` 端点检查服务状态。

查看健康状态：

```bash
docker inspect --format='{{.State.Health.Status}}' dynamic-bot
```

## 故障排查

### 容器无法启动

1. 检查日志：

   ```bash
   docker-compose logs dynamic-bot
   ```

2. 检查端口占用：

   ```bash
   # Windows
   netstat -ano | findstr :2233
   
   # Linux
   lsof -i :2233
   ```

3. 检查目录权限：

   ```bash
   ls -la data config plugins
   ```

### 配置修改不生效

配置文件修改后需要重启容器：

```bash
docker-compose restart dynamic-bot
```

### Web Admin 无法访问

1. 确认容器正在运行：

   ```bash
   docker-compose ps
   ```

2. 检查健康状态（应为 `healthy`）：

   ```bash
   docker inspect --format='{{.State.Health.Status}}' dynamic-bot
   ```

3. 检查防火墙是否允许 2233 端口访问

## 更新镜像

1. 拉取最新代码并重新构建：

   ```bash
   git pull
   docker-compose down
   docker-compose build --no-cache
   docker-compose up -d
   ```

2. 查看新版本日志确认启动正常：

   ```bash
   docker-compose logs -f dynamic-bot
   ```

## 生产环境建议

1. **使用外部卷**：将数据目录挂载到独立的卷或外部存储
2. **配置日志轮转**：避免日志文件过大
3. **定期备份**：定期备份 `data/` 目录（包含数据库和状态）
4. **监控资源使用**：使用 `docker stats` 监控容器资源占用
5. **设置资源限制**：

   ```yaml
   services:
     dynamic-bot:
       deploy:
         resources:
           limits:
             cpus: '2'
             memory: 2G
           reservations:
             cpus: '0.5'
             memory: 512M
   ```

## 网络配置

### 与 OneBot 客户端同机部署

如果 OneBot 客户端（NapCat/LLOneBot）也在 Docker 中运行，可以使用同一网络：

```yaml
networks:
  dynamic-bot-network:
    external: true
    name: onebot-network  # OneBot 客户端所在网络
```

### 反向代理

如需通过 Nginx/Caddy 等反向代理访问，配置示例（Nginx）：

```nginx
server {
    listen 80;
    server_name bot.example.com;

    location / {
        proxy_pass http://localhost:2233;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```
