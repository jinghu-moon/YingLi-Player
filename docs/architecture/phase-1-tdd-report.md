# Phase 1 TDD 记录

## 范围

本记录对应 `09-tdd-phased-development-checklist.md` 的任务 1.1 至 1.7。Phase 1 只建立构建、架构和测试基础，不引入 Hilt、WorkManager、Room 实现、Media3 播放实现、FFmpeg 或第二播放内核。

## Red-Green-Refactor

| 任务 | Red | Green | Refactor / 证据 |
|---|---|---|---|
| 1.1 | 全局 Gradle 9.3.1 与冻结版本不符；引入要求更高 compileSdk 的未使用依赖时 AAR metadata 失败 | Wrapper 9.5.0、JDK 21、AGP 9.3.1 和版本目录可配置 | 约束收敛到 `BuildBaselineTask`；配置缓存成功写入；未使用依赖不进入版本目录或依赖图 |
| 1.2 | 无包边界，UI 可直接引用任意数据或媒体类型 | 建立 `app/feature/domain/core` 方向和最小实际包 | `ArchitectureRulesTest` 覆盖反向依赖、越层访问和循环；不创建空模块或占位类型 |
| 1.3 | 真实 `Dispatchers.Main` 和时间等待无法在 JVM 稳定推进 | 注入 `AppDispatchers`、`AppClock`，用 `MainDispatcherRule` 和虚拟时间断言状态 | Fake Clock、测试调度器、顺序 ID 与 Fixture Builder 统一在 `testing` 包 |
| 1.4 | 路径、URI 查询参数、保险库标题和密钥样本会进入原始日志 | `RedactingAppLogger` 在 Sink 前统一清洗，敏感结构化字段强制替换 | 失败类型与用户文案分离；未知异常只保留异常类型，不保留消息 |
| 1.5 | 生产对象固定创建时无法被 JVM 测试替换 | `AppContainer` 只使用构造注入的稳定接口 | 生产工厂与 `src/test` Fixture 分离，无任意 Service Locator API |
| 1.6 | 仓库没有自动门禁 | Push/PR 执行 Wrapper 校验与单命令质量门禁 | Actions 固定主版本、最小权限、Gradle 缓存和统一产物上传 |
| 1.7 | 首次构建暴露 Wrapper 外部脚本 DSL 与 Configuration Cache 不兼容 | 改为显式 Wrapper 类型和声明式自定义任务输入 | 删除未使用依赖和临时业务样例；最终门禁以本报告末尾结果为准 |

## 异常约束样本

构建约束的预期失败信息如下：

| 变异 | 预期结果 |
|---|---|
| 使用非 JDK 21 启动 Gradle | 配置阶段提示设置 JDK 21 |
| Wrapper 不是 9.5.0 | 配置阶段提示使用已提交 Wrapper |
| Android 模块加入 `org.jetbrains.kotlin.android` | 配置阶段指出 AGP 已内置 Kotlin，并给出需删除插件的模块文件 |
| Version Catalog 使用 Alpha/Beta/RC/动态版本 | `verifyBuildBaseline` 输出不稳定版本映射 |
| 冻结版本被修改或删除 | `verifyBuildBaseline` 输出期望值与实际值 |

## 最终门禁

交付前执行：

```text
./gradlew testDebugUnitTest lintDebug assembleDebug
git diff --check
```

2026-08-07 最终结果：

- 单命令门禁成功，55 个任务完成或命中缓存，Configuration Cache 成功写入；
- 5 个测试套件共 12 个 JVM 测试，失败 0、错误 0、跳过 0；
- Lint 成功，错误 0、警告 0；
- Debug APK 按四种 ABI 成功分包；`arm64-v8a` 产物大小 29,344,236 字节；
- `git diff --check` 成功，`app/build`、`buildSrc/build` 与 Gradle 缓存均保持忽略；
- JDK 17、Gradle 9.3.1、`androidx-test-ext 1.4.0-beta01` 和重复 Kotlin Android 插件四种变异均已实际验证为失败，随后恢复事实源版本。
