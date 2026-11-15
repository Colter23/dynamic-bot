# 发布顺序

当前开发分支使用插件 API 5。模板重构尚未发布，本轮没有提升软件包版本；发布前必须为 core、主程序和插件确定新版本，并更新依赖及下面的插件锁定清单。

1. 先发布新 core，确认 Maven Central 可获取对应版本。本地 composite build 成功不代表远程依赖已发布。
2. 发布四个官方插件，确认每个 Release 都有对应版本的 `*-all.jar`、`*.sha256` 和从 JAR 导出的 `plugin.yml`。
3. 将 `official-plugins.lock.json` 的版本、标签和文件名更新到已发布的 API 5 插件。清单暂时保留原版本号，不能直接用于本轮发布；合集打包会校验每个 JAR 内的插件 ID、版本和 API，发现不匹配立即失败，防止混入旧 API 插件。
4. 最后发布主程序；其 CI 会生成启动包、插件合集、校验和及 Docker 镜像。

不要移动或覆盖已经公开的旧版本标签。远程 CI 独立检出各仓库，不能依赖开发机的 Maven Local 或相邻 core 源码。

`plugins/catalog.json` 的 `release.tag` 固定为 API 3 最后一个版本，供旧主程序使用；`tagsByApiMajor` 为 API 4 固定兼容标签，API 5 指向新版。未来切换 API 时，同样先为上一代固定兼容标签，再添加新主版本映射。

本地打包检查：

```text
python -m pip install -r scripts/requirements-release.txt
python -m unittest discover -s scripts -p "test_*.py"
python scripts/validate-plugin-package.py packaging/official-plugins.lock.json <插件JAR目录>
```
