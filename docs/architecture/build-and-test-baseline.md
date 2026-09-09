# 构建与测试基线

## 工具链

Phase 1 固定以下基线，构建不得使用动态版本或 Alpha、Beta、RC、Preview、EAP、Snapshot：

| 项目 | 版本 |
|---|---:|
| JDK / JVM target | 21 |
| Gradle Wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin / Compose Compiler | 2.4.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 31 |

AGP 9 使用内置 Kotlin，Android 模块禁止应用 `org.jetbrains.kotlin.android`。`verifyBuildBaseline` 在编译、单元测试和 Lint 前检查 JDK、Gradle、Version Catalog、非稳定版本和重复 Kotlin 插件。

Phase 1 不直接依赖 AndroidX Core KTX，也不在 Version Catalog 中预留未使用版本。后续出现明确的扩展 API 使用点时，再选择与当时 `compileSdk` 兼容的稳定版本；间接依赖仍由 Gradle 按实际依赖图解析。

Lint 保持 `warningsAsErrors`，仅关闭 `OldTargetApi`、`GradleDependency`、`AndroidGradlePluginVersion`、`NewerVersionAvailable` 四类“建议升级”检查。这些检查与冻结版本策略相冲突，版本升级统一由 Version Catalog、`verifyBuildBaseline` 和显式升级任务管理；代码质量、安全与无障碍检查不受影响。

`app/lint.xml` 另对 `mipmap-anydpi-v26` 自适应图标目录做单路径 `ObsoleteSdkInt` 豁免。项目最低 API 已是 31，因此 Lint 认为 `v26` 限定冗余；保留该目录是为了维持 Android Adaptive Icon 的标准资源结构，豁免不扩散到其他资源或源码。

## 本地命令

Windows：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux、macOS 与 CI：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

命令产出 JVM 测试报告、Lint 报告和 Debug APK。Wrapper 下载启用 SHA-256 校验；本地必须让 Gradle 运行在 JDK 21。路径可以包含空格，调用 Wrapper 时不要自行拆分工程路径。

APK 按 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四种 ABI 分包，不生成 Universal APK。真机侧载前使用 `adb shell getprop ro.product.cpu.abi` 选择匹配产物；应用商店发布仍优先使用 AAB，由商店生成设备拆分包。

## 单模块边界

首版仅保留 `app` Android 模块，包承担以下职责：

根命名空间和应用 ID 统一为 `seeyuer.yingli.player`。

| 包 | 职责 |
|---|---|
| `app` | Android 入口、生产依赖组装、Framework 适配器 |
| `feature/*` | Compose 页面、ViewModel、UI state 与事件 |
| `domain` | 按需存在的 UseCase 和纯业务规则 |
| `core/model` | 不依赖 UI 的领域模型与统一失败类型 |
| `core/database` | Room 数据源与 DAO，Phase 3 按需创建 |
| `core/datastore` | 偏好数据源，Phase 2 按需创建 |
| `core/media` | Media3 与平台媒体适配，Phase 3/4 按需创建 |
| `core/designsystem` | Token 和基础 Compose 组件，Phase 2 按需创建 |
| `core/foundation` | 时钟、调度器、ID、日志和容器等跨域契约 |

依赖方向固定为 `Compose -> ViewModel -> UseCase（按需）-> Repository -> DataSource`，包层级只允许 `app -> feature -> domain -> core`。不存在职责的包不创建占位类型。

`ArchitectureRulesTest` 自动检查以下边界：

- `core`、`domain`、`feature` 不得反向依赖上层；
- Feature UI 不得直接导入 Room、文件 API 或 ExoPlayer；
- Repository 不得导入 Compose；
- 生产代码必须注入调度器与时钟；
- `testing`、JUnit 和协程测试类型不得进入 `src/main`；
- 项目内包依赖不得形成循环。

## 基础契约与组装

`AppDispatchers`、`AppClock`、`IdGenerator`、`AppLogger`、`SensitiveValueRedactor` 和 `AppFailureMapper` 均为纯 Kotlin 接口。`AppFailure` 只表达稳定技术类别，不携带用户文案或异常消息。

`AppContainer` 只暴露上述稳定接口。生产对象由 `ProductionAppContainerFactory` 按构造顺序创建；Fake、测试调度器、捕获式日志 Sink 和 Fixture Builder 只存在于 `src/test`。禁止通过字符串 Key 或泛型 `get()` 任意查找依赖。

## 测试分层

| 层级 | Source set / 运行环境 | Phase 1 状态 |
|---|---|---|
| 纯 Kotlin/JVM | `src/test` | 已建立，当前主门禁 |
| Room/Android 仪器 | `src/androidTest` | Phase 3 按需建立 |
| Compose UI | `src/androidTest` | Phase 2 已建立；覆盖窗口分类、导航语义、触控区和 200% 字体 |
| Media3 真机 | 指定 API 33 真机 | Phase 4 起建立 |
| Macrobenchmark | 独立 benchmark 模块 | MVP 稳定后建立 |

协程测试统一使用 `MainDispatcherRule`、`StandardTestDispatcher` 和 `runTest` 虚拟时间。测试不得用 `Thread.sleep` 或真实时间等待；`runTest` 会在未完成子协程或泄漏 Job 时失败。

Phase 2 将 AndroidX Lifecycle 固定为 `2.10.0`。`2.11.0` 的 Compose AAR 要求 `compileSdk 37`，与项目稳定 `compileSdk 36` 基线冲突；该降级不改变 Kotlin、targetSdk 或 minSdk。Tabler 图标依赖使用本地从 Tabler Icons `3.46.0` 生成的 `io.github.jinghu-moon.composeicons:icons-tabler:0.1.0-local.1`，由 `mavenLocal()` 解析；在本机执行 YingLi 构建前，必须先在 `compose-icons` 项目执行 `:icons-core:publishToMavenLocal :icons-tabler:publishToMavenLocal`。该本地依赖不适用于干净的托管 CI，后续需要远程可复现构建时再发布至受管 Maven 仓库。

## CI

GitHub Actions 对 Push 与 Pull Request 执行 Wrapper 校验、JDK 21 设置、单元测试、Lint、Debug 构建、`git diff --check` 和生成文件检查。权限仅为 `contents: read`，APK 与测试/Lint 报告保留 14 天。
