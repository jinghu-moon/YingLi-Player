# Changelog

## Unreleased

### Added

- 版本化统一设置、深浅色/系统主题、库布局、缩略图密度和回收站保留期。
- 标签、集合、历史与设置的 JSON 备份预览、冲突策略和事务恢复。
- 本地脱敏诊断导出与仅限 GitHub Releases 的更新检查。
- 持久处理项目、任务状态机、恢复、取消、前台通知和原子产物提交。
- 多片段切片项目、取帧时间轴、快速/精确切片和批量导出。
- Macrobenchmark/Baseline Profile 生成器、Release R8 和 SHA-256 任务。

### Security

- 禁止明文网络流量；无遥测上传。
- 诊断不包含原始路径、URI、文件名、视频帧或密钥材料。
- Release 签名仅从本机环境变量读取，仓库不保存密钥。

