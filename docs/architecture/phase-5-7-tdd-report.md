# Phase 5-7 TDD 报告

> 历史证据：本报告记录 Phase 5-7 当时的实现和测试结果；当前播放 UI/UX 与播放架构分别以 [`../16-player-ui-ux-interaction-implementation-spec.md`](../16-player-ui-ux-interaction-implementation-spec.md) 和 [`../17-playback-architecture-refactor-spec.md`](../17-playback-architecture-refactor-spec.md) 为准。

## 交付范围

### Phase 5：媒体库与安全文件操作

- 完成查询、排序、筛选、稳定游标、关键词规范化和 250ms 防抖契约。
- 完成网格、列表、瀑布流及缩略图密度偏好，Compact Bottom Sheet 与宽屏筛选面板。
- 完成重命名、移动、回收站、恢复、永久删除及 30 天默认保留策略。
- 完成多选、删除确认、恢复冲突和不覆盖源文件的文件操作边界。

### Phase 6：整理、历史与首页

- Room Schema 从 v1 迁移到 v2，增加标签、收藏、播放列表、集合、历史、最近整理和回收站关系。
- 完成标签、收藏、手动/智能集合、播放历史和继续观看领域契约。
- 完成整理页四入口、标签编辑与 8 色圆点，以及首页搜索、继续观看、最近添加和常用文件夹。
- 播放达到 10 秒后每个会话只累计一次历史；无痕播放不写历史。

### Phase 7：高级播放与系统集成

- 完成 0.5x-4x 固定倍速、按视频偏好覆盖、显式队列和默认关闭连续播放。
- 完成单击、双击前后 10 秒、3 秒自动隐藏和锁定模式的 Overlay 状态机。
- 完成 Media3 音轨、字幕、倍速、画面适应、PiP、音频焦点、耳机断开和迷你播放器配置。
- 完成基于 PixelCopy/TextureView 的截图，输出到 `Pictures/YingLi`。

## TDD 证据

| 层级 | 覆盖重点 | 结果 |
| --- | --- | --- |
| JVM | 查询与筛选边界、防抖、回收站策略、整理约束、继续观看、偏好优先级、队列边界、Overlay 状态机、ViewModel | `testDebugUnitTest`：92 项通过 |
| Room 仪器 | v1 到 v2 Schema 迁移、外键、事务回滚、播放进度 | 真机通过 |
| 文件仪器 | File URI 回收、恢复、永久删除及源文件保护 | 真机通过 |
| Media3 仪器 | 单例 Controller、Service 连接、高级偏好命令及主线程约束 | 真机通过 |
| Compose 仪器 | 三种媒体库布局、整理入口、标签表单、播放锁定状态和既有自适应导航回归 | 真机通过 |
| 静态检查 | Debug 源码、测试源码和资源 | `lintDebug` 通过，0 错误 |

## 真机执行记录

- 设备：Xiaomi M2012K11AC，Android 13/API 33，设备 ID `dc39c31d`。
- 最终仪器回归：`OK (40 tests)`，耗时 39.353 秒，0 失败、0 跳过。
- 首轮发现并修复 Media3 测试线程错误，以及 Room Testing 与 DataStore 间接 Serialization 版本不一致；最终统一调试运行时为 `1.11.0`。
- 构建门禁：`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功。

## 尚需样本矩阵

以下项目依赖用户媒体、系统授权或可控故障环境，不计入本轮自动化通过结论：

- 1k/10k 真实或合成媒体库的查询延迟与滚动性能基线。
- SAF 跨卷移动、只读卷、卷拔出及进程中断恢复。
- 多音轨/HDR/外挂字幕样本、旋转重组和主动/自动 PiP。
- 来电音频焦点、蓝牙/有线耳机断开、通知权限拒绝。
- 真实视频帧截图、磁盘空间不足及只读输出目录。
