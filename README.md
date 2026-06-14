# dynamic-bot

dynamic-bot 是主程序，负责运行 Web 后台、管理订阅、加载插件，并把插件提供的动态、直播和链接解析结果推送到 QQ 等目标平台。

单独运行主程序本身不会接入任何平台。你需要安装至少一个内容来源插件，以及至少一个消息发送插件。

## 主要功能

- Web 后台管理订阅、插件、配置、日志、任务和消息记录。
- 支持 Bilibili、微博等来源插件。
- 支持 OneBot/QQ 等消息出口插件。
- 支持动态推送、直播开播/下播推送和链接解析。
- 支持推送图片渲染、媒体缓存和视频缓存。
- 支持失败重试、消息记录和系统通知。
- 支持插件提供自己的管理页面。

## 快速启动

构建主程序：

```powershell
.\gradlew.bat fatJar
```

启动：

```powershell
java -jar build\libs\dynamic-bot-0.0.1-all.jar
```

默认后台地址：

```text
http://127.0.0.1:2233
```

首次启动时，如果没有配置后台 token，程序会自动生成并写入：

```text
config/main.yml
```

启动日志中也会输出本次生成的 token，请妥善保存。

## 安装插件

将插件 JAR 放到主程序运行目录的 `plugins/` 下，然后重启主程序或在后台刷新插件。

常用插件：

- `dynamic-bot-bilibili`：Bilibili 动态和直播来源。
- `dynamic-bot-weibo`：微博动态来源。
- `dynamic-bot-onebot`：QQ/OneBot 消息发送和消息接收。

## 常用目录

- `config/`：主程序和插件配置。
- `data/dynamic.db`：数据库。
- `data/images/source`：来源原图缓存。
- `data/images/draw`：推送渲染图缓存。
- `data/videos`：链接解析视频缓存。
- `data/plugins/`：插件自己的数据。
- `plugins/`：插件 JAR。

## 基础配置

大多数配置都可以先使用默认值。首次使用通常只需要关注：

- Web 后台地址、端口和 token。
- 命令前缀。
- 推送模板。
- 媒体发送方式，默认建议使用自动。
- 消息发送路由，多 Bot 时再配置。

内容平台登录、OneBot 连接等配置在对应插件页面中完成。

## Docker

默认从 GHCR 拉取镜像：

```powershell
docker compose up -d
```

镜像地址：

```text
ghcr.io/colter23/dynamic-bot
```

详细说明见 [DOCKER.md](DOCKER.md)。

## 构建与测试

```powershell
.\gradlew.bat test
.\gradlew.bat fatJar
```

生成的完整 JAR：

```text
build/libs/dynamic-bot-0.0.1-all.jar
```

## 使用建议

- 先安装消息出口插件，再配置内容来源插件。
- Bilibili、微博等平台不要把轮询间隔设置得太短，避免增加风控风险。
- 默认媒体发送方式建议保持“自动”。
- 如果和 OneBot 客户端部署在同一台机器，自动模式通常会优先尝试本地文件发送，图片量大时更友好。
- 如果推送失败，优先查看后台消息记录和日志页面。
