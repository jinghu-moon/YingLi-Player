# Phase 8 设置与发布契约

## 设置 schema

`UserPreferences` schema v1 集中保存主题、动态中性表面、处理入口、库布局/密度/排序、回收站天数、迷你播放器、自动 PiP 和输出目录类型。损坏枚举回退默认，数值按领域边界钳制；自定义 SAF URI 不进入备份。

Material You 只替换低饱和中性 `background/surface/surfaceVariant`。结构选中、四类功能色和播放页纯黑/Scrim 继续使用固定 Token：浅色功能色 B，深色功能色 A。

## 备份与恢复

- JSON schema 当前版本为 1，文档上限 4 MiB。
- 可独立选择设置、整理关系和历史。
- 解析、版本验证和预览先完成，之后才允许“保留现有”或“替换”。
- Room 关系在单事务中恢复；若数据库失败，已写入的 DataStore 设置回滚。

## 诊断与更新

滚动诊断仅保留最近 200 条已脱敏结构化记录。更新源固定为 `jinghu-moon/YingLi-Player` 的 GitHub Releases API，响应上限 256 KiB，本地 6 小时限频，不影响播放与浏览。

## 发布门禁

Release 启用 R8 与资源收缩；签名只读环境变量。`generateReleaseChecksums` 对每个 ABI APK 生成 SHA-256。Macrobenchmark 模块覆盖冷启动并提供 Baseline Profile 生成器；稳定版插件尚不支持 AGP 9.3，因此生成结果需在真机运行后显式审查和导入。

