# Phase 8-10 TDD 报告

## 交付范围

### Phase 8：设置、备份、性能与发布

- 统一版本化 `UserPreferences` DataStore，覆盖主题、布局、排序、处理入口、回收站、迷你播放器、PiP 和输出目录偏好。
- 完成 JSON 备份预览、冲突策略和事务恢复，覆盖设置、标签关系、收藏、播放列表、集合与历史；自定义 SAF URI 不进入备份。
- 完成最多 200 条的脱敏诊断日志，以及仅访问固定 GitHub Releases 仓库的更新检查。
- 完成四 ABI Release、R8、资源收缩、环境变量签名和 SHA-256 生成任务。
- 增加独立 `benchmark` 模块，提供冷启动 Macrobenchmark 与 Baseline Profile 生成器。

### Phase 9：处理任务基础设施与处理中心

- 完成持久化处理项目、输入、任务和事件模型，Room Schema 从 v2 迁移到 v3。
- 完成 `Queued/Preparing/Running/Paused/Canceling/Succeeded/Failed/Canceled` 状态机及幂等恢复、重试和取消规则。
- 完成单并发调度、低电量和低空间门禁，以及状态变化、1%、2 秒或完成时写入 checkpoint 的节流策略。
- 完成前台处理服务、私有临时产物、MediaStore `IS_PENDING` 原子提交和处理中心控制界面。
- 处理日志只记录任务 ID 和错误码，不记录原始文件名、路径或媒体内容。

### Phase 10：切片项目与导出

- 完成 `ClipProject`、`ClipSegment`、编辑命令、允许重叠的排序与 50 层撤销模型。
- 完成多选、复制、删除、500ms 节流保存和 Room Schema v3 到 v4 迁移。
- 完成 I/O 调度的 320x180 JPEG 时间轴取帧、URI SHA-256 缓存目录及最多 12 帧的 UI 限制。
- 快速模式使用 `MediaExtractor + MediaMuxer`，精确模式使用 Media3 Transformer；输出通过独立探测后才提交。
- 每个片段生成独立持久处理任务，处理中心可观察单片段结果并对失败项重试。

## 自动化证据

| 层级 | 覆盖重点 | 结果 |
| --- | --- | --- |
| JVM | 设置 schema、备份边界、诊断滚动、处理状态机、checkpoint、任务恢复、切片约束、编辑器 reducer、撤销与批量导出 | `testDebugUnitTest`：109 项通过，0 失败、0 跳过 |
| Room Schema | 已提交数据库结构快照 | `1.json`、`2.json`、`3.json`、`4.json` 均存在 |
| Room/文件/Media3/Compose 仪器 | v1 到 v4 迁移、文件操作、媒体发现、播放服务、设置、处理中心、切片和自适应导航 | 真机 `OK (45 tests)`，0 失败、0 跳过 |
| Benchmark 构建 | 冷启动与 Baseline Profile 测试 APK | `:benchmark:assembleBenchmark` 通过；dry-run 获得 `timeToInitialDisplay = 490.9ms`，仅作为链路诊断值 |
| 静态检查 | Debug 源码、测试源码和资源 | `lintDebug` 通过，0 错误 |
| Debug 构建 | 四 ABI 调试 APK | `assembleDebug` 通过 |
| Release 构建 | R8、资源收缩、四 ABI 和 SHA-256 | `generateReleaseChecksums` 通过；当前产物未签名 |

最终本地门禁于 2026-08-09 再次执行：

```text
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug assembleDebugAndroidTest :benchmark:assembleBenchmark :app:generateReleaseChecksums
git diff --check
```

- JVM 测试报告：109 项通过，0 失败、0 错误、0 跳过。
- Lint、四 ABI Debug、AndroidTest APK、Benchmark APK、R8 Release 与校验和任务均成功。

## Release 校验和

```text
081aefa3510862ecacc547967ac2ee358bd37395b081a47755d34ef76588773f  app-arm64-v8a-release-unsigned.apk
7d0cdee613618b7adc2af8f3362e67f4dd0ea18bbee891e6fa5bcf45c5cfe29f  app-armeabi-v7a-release-unsigned.apk
a36ee0cc326cf64cd99231c0b7bead4133bedb436dde5aed06e4b2bdf89162a9  app-x86-release-unsigned.apk
1e513bed5d790864a4fb056dd6177e44e9be9d16823f007873e781f92d288ed0  app-x86_64-release-unsigned.apk
```

## 真机执行记录

- 设备：Xiaomi M2012K11AC，Android 13/API 33，设备 ID `dc39c31d`。
- 最终仪器回归：`OK (45 tests)`，耗时 44.439 秒，0 失败、0 跳过。
- 覆盖 v1 到 v4 Room 迁移、文件操作、MediaStore/SAF、Media3、设置恢复预览、处理状态矩阵、切片编辑器、自适应导航和 200% 字体。
- 首轮 Compose 测试被 MIUI“后台弹出界面”权限拦截；允许 `MIUIOP(10021)` 后测试宿主正常运行。
- 首轮切片编辑器测试因文本输入框与勾选标签同名产生选择器歧义；收紧为可编辑语义节点后完整回归通过。

## 待完成的真机门禁

以下项目尚未计入通过结论：

- 设置备份导出/导入、冲突策略、真实处理任务和切片导出的人工端到端操作。
- 窄屏、横屏、TalkBack、触控目标和深浅色人工视觉检查；200% 字体自动化回归已通过。
- 正式 Baseline Profile 与 `CompilationMode.Partial(BaselineProfileMode.Require)` 五次冷启动数据已由用户确认跳过。dry-run 的 `490.9ms` 不代表 fresh-install、Baseline Profile 优化后的发布基线，不计入性能 DoD。
- 短/长、VFR、非关键帧、HEVC、HDR、多音轨、片段重叠、取消、空间不足和进程终止样本矩阵。
- 使用正式签名环境变量生成并验证可安装 Release APK；当前只有 unsigned Release，不满足发布安装门禁。

## 已知构建注意事项

- Baseline Profile Gradle 插件稳定版 `1.4.1` 与当前 AGP `9.3.1` 不兼容，因此未引入测试版插件；生成器保留在独立 Benchmark 模块中。
- Xiaomi Android 13/API 33 在非 root shell 下运行 AndroidX Benchmark 1.4.1 时，Baseline Profile 首轮和正式 `Partial` 编译模式都会卸载并通过 shell 重装目标包；MIUI 返回 `INSTALL_FAILED_USER_RESTRICTED`。手动预装无法绕过规则内部重装。启用 Magisk Shell root 又会使 AndroidX `ShellImpl` 卡在 `su root id`，因此本阶段不把该设备作为正式性能基线设备。
- 10,000 条合成索引的墙钟测试在与 R8/Lint 并发时曾出现一次 `5242ms` 抖动；独立复跑全部 JVM 测试后通过，测试套件耗时记录为 `1.599s`。真机 3 秒目标仍需在指定设备上单独测量。
