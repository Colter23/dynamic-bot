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
   docker compose up -d
   ```

3. **查看日志**

   ```bash
   docker compose logs -f dynamic-bot
   ```

4. **停止服务**

   ```bash
   docker compose down
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

容器启动时会自动创建这些目录，并在需要时修复目录权限。Java 主进程不会以 root 运行，启动脚本只在初始化挂载目录时短暂使用 root。

```bash
# 可选：提前创建目录，便于自己放插件或备份
mkdir -p data config plugins
```

默认使用 UID/GID `1000:1000` 运行应用。如果你的宿主机用户不是 1000，可以启动时指定：

```bash
# Linux/macOS
APP_UID=$(id -u) APP_GID=$(id -g) docker compose up -d
```

`FIX_VOLUME_PERMISSIONS` 控制启动脚本是否修复挂载目录权限：

| 值 | 说明 |
|----|------|
| `auto` | 默认值。目录不可写时才递归 `chown` |
| `always` | 每次启动都递归 `chown`，适合刚迁移目录后使用一次 |
| `false` | 不自动修复权限，适合 NFS root-squash 等不允许容器 root 改权限的环境 |

### 运行时缓存

容器会把 Skiko 原生库缓存、临时文件等运行时目录放到 `/app/.runtime`，不会写进 `data/`。这个目录不需要备份，容器重建后会自动重新创建。

如果确实需要改位置，可以设置：

```bash
APP_RUNTIME_DIR=/some/path docker compose up -d
```

## 插件安装

1. 将插件 JAR 文件放入 `plugins/` 目录
2. 重启容器使插件生效：

   ```bash
   docker compose restart dynamic-bot
   ```

## 仅使用 Dockerfile

默认 `docker-compose.yml` 会使用 GHCR 镜像：

```text
ghcr.io/colter23/dynamic-bot:latest
```

如果需要从源码本地构建，可以直接使用 Dockerfile：

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
     -e APP_UID="$(id -u)" \
     -e APP_GID="$(id -g)" \
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

### 数据库初始化失败 / 权限错误

**症状**：容器日志显示类似错误：
- `Cannot create database file`
- `Permission denied`
- `failed to open database`

**原因**：挂载的宿主机目录权限不匹配。启动脚本会先尝试修复权限，再以 `APP_UID/APP_GID` 指定的用户运行 Java 主进程。如果宿主机文件系统不允许容器 root 修改权限，就需要手动处理。

**解决方案**：

1. **方案 1：使用当前用户的 UID/GID（推荐）**

   ```bash
   # 停止容器
   docker compose down

   # Linux/macOS
   APP_UID=$(id -u) APP_GID=$(id -g) docker compose up -d
   ```

2. **方案 2：强制重新修复目录权限**

   目录从其他机器迁移过来，或子目录权限混乱时，可以临时使用：

   ```bash
   FIX_VOLUME_PERMISSIONS=always docker compose up -d
   ```

   确认启动正常后，可以恢复默认 `auto`。

3. **方案 3：手动修复宿主机目录权限**

   ```bash
   docker compose down
   sudo chown -R 1000:1000 data config plugins
   docker compose up -d
   ```

   如果你用的是自定义 `APP_UID/APP_GID`，把 `1000:1000` 换成对应值。

4. **方案 4：禁用自动修复**

   NFS root-squash、部分 NAS 或受限主机可能不允许容器 root 执行 `chown`。这时先手动修复目录，再禁用自动修复：

   ```bash
   FIX_VOLUME_PERMISSIONS=false docker compose up -d
   ```

### 绘图初始化失败 / Skiko 缓存权限错误

**症状**：日志中出现类似：

- `java.lang.ExceptionInInitializerError`
- `org.jetbrains.skiko.LibraryLoader.findAndLoadLibrary`
- `AccessDeniedException: /home/dynamicbot`

**原因**：绘图依赖 Skiko，首次初始化时需要解压原生库。容器已把 `HOME`、`XDG_CACHE_HOME` 和临时目录指向 `/app/.runtime`，并在启动时修复权限。如果仍然报错，通常是旧镜像尚未更新，或手动覆盖了运行时目录。

**解决方案**：

```bash
docker compose pull
docker compose up -d --force-recreate
```

如果你自定义了 `APP_RUNTIME_DIR`，确认该目录在容器内可写。

### 插件下载失败

**症状**：后台日志显示类似错误：
- `插件下载失败：pluginId=xxx，plugins/.downloads/xxx.jar.tmp (No such file or directory)`
- `无法创建插件下载目录`
- `插件下载目录不可写`

**原因**：`plugins/` 目录或其子目录 `.downloads/` 权限不足。默认启动脚本会自动创建 `.downloads` 并修复权限；如果仍然失败，通常是宿主机文件系统不允许容器修改权限。

**解决方案**：

```bash
# 停止容器
docker compose down

# 修复权限。这里的 1000:1000 要和 APP_UID/APP_GID 保持一致
sudo chown -R 1000:1000 plugins
sudo chmod -R u+w plugins

# 手动创建下载目录（可选）
mkdir -p plugins/.downloads
sudo chown 1000:1000 plugins/.downloads

# 重新启动
docker compose up -d
```

如果问题仍然存在，检查容器日志：

```bash
docker compose logs -f dynamic-bot | grep -i "plugin\|permission"
```

### 容器无法启动

1. 检查日志：

   ```bash
   docker compose logs dynamic-bot
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

4. 如果日志里出现权限修复失败，尝试临时强制修复：

   ```bash
   FIX_VOLUME_PERMISSIONS=always docker compose up -d
   ```

### 配置修改不生效

配置文件修改后需要重启容器：

```bash
docker compose restart dynamic-bot
```

### Web Admin 无法访问

1. 确认容器正在运行：

   ```bash
   docker compose ps
   ```

2. 检查健康状态（应为 `healthy`）：

   ```bash
   docker inspect --format='{{.State.Health.Status}}' dynamic-bot
   ```

3. 检查防火墙是否允许 2233 端口访问

## 更新镜像

1. 拉取最新代码并重新构建：

   ```bash
   docker compose pull
   docker compose up -d
   ```

2. 查看新版本日志确认启动正常：

   ```bash
   docker compose logs -f dynamic-bot
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
