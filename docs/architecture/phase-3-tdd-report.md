# Phase 3 TDD 记录

## 范围

本记录对应 `09-tdd-phased-development-checklist.md` 的任务 3.1 至 3.10。Phase 3 建立媒体领域模型、Room v1、用户触发授权、三种发现适配器、增量索引、身份重定位、缩略图队列和首次引导，不实现 Media3 播放。

## Red-Green-Refactor

| 任务 | Red | Green | Refactor / 证据 |
|---|---|---|---|
| 3.1 | 路径若作为媒体 ID，多路径、移动和用户数据会耦合 | 独立 `MediaItemId`、`MediaLocationId`、`MediaSourceId`、`VolumeId` 和 `MediaUri` | `MediaModelsTest` 固定值对象不变量与逻辑/物理分离 |
| 3.2 | 重复 URI、孤儿关系和中途失败可能留下半批数据 | Room v1 五张表、唯一 URI、外键和单事务 `CatalogMutation` | 显式 Insert/Update 避免 Room Upsert 对备用唯一键静默忽略；Schema 已导出 |
| 3.3 | 拒绝、取消和持久授权丢失会循环骚扰或卡住首页 | 纯权限状态机和 Activity Result 边界 | 全部系统页只由点击触发；拒绝与取消完整降级 |
| 3.4 | 空 Cursor、缺列、坏行或失权可能中断整批 | MediaStore 必要列查询和逐行失败隔离 | `MediaStoreQuery` 注入使投影、重复 ID 和异常可做 Contract Test |
| 3.5 | 深树、循环 URI 和慢/坏 Provider 可导致递归溢出或重复 | SAF 文档节点网关、迭代遍历、深度 64 和 URI 去重 | 虚拟文档节点覆盖循环、失权、深树和单节点失败 |
| 3.6 | 逐条写库、取消后写缺失和权限中断会污染缓存 | 发现、身份、合并三步后一次事务提交 | 取消不提交；终端失败使用 `markMissing=false`；无虚构百分比 |
| 3.7 | 文件名合并会误判，同内容第二路径会覆盖原位置 | URI/文档 ID/哈希/加权证据链；新 URI 创建独立位置并关联逻辑媒体 | 多候选只在冲突分支计算 SHA-256；仍冲突则保持独立 |
| 3.8 | 快速滚动可能堆积，取消会被误当失败重试 | 四级优先队列、并发上限、取消传播、一次重试和 LRU | 队列测试覆盖优先级、取消、重试和缓存淘汰 |
| 3.9 | 首次启动强制授权或空白等待会阻断产品使用 | 推荐全部文件、SAF、稍后添加；缓存内容与扫描 Banner 并存 | Compose 状态矩阵覆盖拒权、空首页、扫描和缓存内容 |
| 3.10 | 只有主源码编译无法证明事务和平台边界 | JVM、Room、Adapter、Compose、Lint 和 ABI 分包门禁 | 指定 Xiaomi API 33 真机运行同一 AndroidJUnitRunner |

## 性能基线

开发机：Windows、JDK 21、Gradle 9.5.0，测试使用内存 Repository、确定顺序 Flow 和轻量元数据，不访问真实共享存储。

| 用例 | 条目数 | JVM 测试耗时 | 结果 |
|---|---:|---:|---|
| `synthetic-1k` | 1,000 | 约 80ms | 通过 |
| `synthetic-10k` | 10,000 | 约 1,415ms | 通过，小于 3 秒 |

该结果是算法回归门禁，不等同于真机 Provider I/O。完整内容哈希不在这两个数据集的主扫描路径运行。

## 依赖与构建取舍

- Room `2.8.4` 使用 KSP `2.3.2` 生成 DAO 和 Schema。
- AGP `9.3.1` 使用内置 Kotlin；不应用旧 `org.jetbrains.kotlin.android`。
- KSP `2.3.2` 仍通过 Kotlin sourceSets 注册生成目录，因此暂时启用 `android.disallowKotlinSourceSets=false`，该兼容开关已记录在技术选型文档。
- Coil `3.5.0` 只位于缩略图提取边界；列表状态和队列测试不依赖真实解码器。
- 不新增 Core KTX；当前 Phase 3 代码没有该依赖的必要使用点。
- `UseKtx` 风格规则因此显式关闭；平台 `Uri.parse` 继续由 API/Lint 正确性规则检查。

## 自动化门禁

Phase 3 新增 JVM 测试覆盖模型、身份、权限、扫描、冲突哈希、1k/10k 性能、缩略图和 ViewModel。Android 仪器测试覆盖 Room、MediaStore、SAF 与 Compose 状态矩阵。

2026-08-07 本地门禁结果：

- JVM 共 65 项测试，失败 0、错误 0、跳过 0；
- Lint 成功，错误 0、警告 0；
- Debug APK 按 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四种 ABI 分包成功；
- `arm64-v8a` APK 为 70,491,224 字节；
- `git diff --check` 成功。

第一轮指定 Xiaomi M2012K11AC（Android 13 / API 33）执行 23 项仪器测试，22 项通过；唯一失败证明 Room `@Upsert` 会静默忽略备用唯一 URI 冲突。实现改为显式 `INSERT(ABORT)` 与 `UPDATE` 后，手动覆盖安装 v2 主 APK 和测试 APK，并重新允许 MIUI“后台弹出界面”。最终使用同一 `AndroidJUnitRunner` 一次性执行 23 项，耗时 16.817 秒，失败 0、错误 0、跳过 0。

真机最终分项为 Room 4 项、MediaStore 4 项、SAF 4 项、Phase 3 Compose 状态矩阵 5 项、Phase 2 自适应壳回归 6 项。MIUI 重装后会重置后台启动许可，并在第一次测试 Activity 启动时显示确认页；该设备限制已验证，不属于应用权限状态机。

最终门禁命令：

```text
./gradlew testDebugUnitTest lintDebug assembleDebug
adb shell am instrument -w -r seeyuer.yingli.player.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

不在本阶段执行 Git commit、push 或分支操作。
