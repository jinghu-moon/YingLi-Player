# YingLi-Player TDD 开发任务清单

## 原型事实源与实现优先级

- 导航以 [`01-navigation-style-prototypes.html`](../prototypes/01-navigation-style-prototypes.html) 的 **F 方案“自适应三栏”**为事实源：Compact 窗口固定显示“首页 / 视频 / 整理”三项底部导航，横屏、折叠屏及 Medium/Expanded 窗口转换为导航轨；播放页隐藏一级导航；处理中心与设置默认位于顶部全局操作区，处理中心可由用户配置为第四个一级入口。
- 颜色与组件以 [`02-color-system-components.html`](../prototypes/02-color-system-components.html) 的 **Draft 03** 为最终事实源：中性灰承担界面骨架，黑白反转承担结构性选中，钢蓝只用于焦点、链接、信息与处理中；琥珀、红、绿分别表达警告/等待、错误/危险、成功/完成。`01` 中的绿色导航选中、绿色链接和绿色观看进度均为历史展示，不得进入实现。
- 功能色亮度以 [`03-functional-color-brightness-comparison.html`](../prototypes/03-functional-color-brightness-comparison.html) 的正式选择为准：浅色主题统一使用 B 档（相对基准 OKLab L +4），深色主题统一使用 A 档；不得在运行时重新计算、混用 A-E 档或因动态颜色改变四类功能色。
- 原型只定义产品行为和视觉契约，不直接复制 HTML/CSS 实现。Compose 组件必须使用三层 Token（Primitive -> Semantic -> Component）、Material 3 窗口尺寸类和无障碍语义，并由本清单中的自动化测试验证。

## Phase 1: 构建基线、架构边界与测试骨架
**【阶段目标】**

建立可重复构建、可独立测试、可持续集成的 Android 工程骨架；冻结 Kotlin 2.4.0、AGP 9.3.1、Gradle 9.5.0、JDK 21、compile/target 36、minSdk 31 及稳定依赖版本。首版保持单 `app` 模块并按业务域分包，不提前引入 Hilt、WorkManager、FFmpeg 或第二播放内核。

**【接口与契约定义】**

- 根命名空间：`seeyuer.yingli.player`；依赖方向固定为 `Compose -> ViewModel -> UseCase（按需）-> Repository -> DataSource`。
- 基础可替换契约：`AppDispatchers`、`AppClock`、`IdGenerator`、`AppLogger`、`SensitiveValueRedactor`。
- 统一失败模型：`AppFailure` 为封闭类型，至少区分权限、存储、数据库、媒体格式、解码、任务取消和未知错误；不得以用户文案作为领域错误类型。
- 依赖组装：`AppContainer` 仅暴露稳定接口；测试使用手写 Fake/Stub，禁止业务测试直接构造 Android Framework 对象。
- 测试分层：纯 Kotlin/JVM、Room/Android 仪器、Compose UI、Media3 真机、Macrobenchmark；测试夹具统一放在 `testFixtures` 或 `testing` 包。

**【任务节点树】**

* **任务 1.1: 固化 Gradle 与版本目录**
  * **依赖**: 无
  * **TDD 循环**: Red：先编写构建约束测试，证明错误 JDK、重复 Kotlin 插件或预览依赖会失败；Green：以最小脚本完成可构建工程；Refactor：把版本集中到 Version Catalog。
  * **测试预期**:
    * 正常路径: JDK 21 下 `assembleDebug`、`testDebugUnitTest`、`lintDebug` 成功，AGP 内置 Kotlin 生效。
    * 异常路径: JDK 版本不符、`org.jetbrains.kotlin.android` 被误加、稳定依赖被替换为 Alpha/Beta/RC 时构建校验失败并给出明确原因。
  * **DoD**: Gradle Wrapper 可执行；Version Catalog 与 `00-android-technology-selection.md` 一致；无动态版本；构建缓存目录未入库；Red-Green-Refactor 记录进入测试报告。
* **任务 1.2: 建立单模块领域包与依赖规则**
  * **依赖**: 任务 1.1
  * **TDD 循环**: Red：写架构测试阻止 `core` 依赖 `feature`、Repository 依赖 Compose；Green：创建最小包结构；Refactor：删除无用占位抽象。
  * **测试预期**:
    * 正常路径: `core/model`、`core/database`、`core/datastore`、`core/media`、`core/designsystem`、`domain`、`feature/*` 依赖方向合法。
    * 异常路径: UI 直接访问 DAO、文件 API 或 ExoPlayer 时架构测试失败；循环依赖被检测。
  * **DoD**: 架构规则自动化；没有为未来功能创建空 Gradle 模块；包职责写入工程结构文档；测试、Lint 全通过。
* **任务 1.3: 建立 JVM 与协程测试夹具** `[并行]`
  * **依赖**: 任务 1.1
  * **TDD 循环**: Red：用真实时间与真实 Dispatcher 制造不可控测试；Green：注入测试时钟与调度器；Refactor：收敛为共享 Rule/Extension。
  * **测试预期**:
    * 正常路径: `runTest` 可推进虚拟时间，主线程状态转换可确定性断言。
    * 异常路径: 未完成协程、泄漏 Job、硬编码 `Dispatchers.IO/Main` 和依赖真实系统时间的测试被检测。
  * **DoD**: `AppDispatchers`、`AppClock`、测试 Rule、Fixture Builder 可复用；测试无 `delay` 等待；重复运行结果一致。
* **任务 1.4: 定义统一错误、日志与脱敏契约** `[并行]`
  * **依赖**: 任务 1.2
  * **TDD 循环**: Red：先覆盖路径、URI、标题泄露及错误映射遗漏；Green：实现最小封闭失败类型与脱敏器；Refactor：分离技术错误和 UI 文案。
  * **测试预期**:
    * 正常路径: 已知异常稳定映射为 `AppFailure`，日志保留错误码和可执行上下文。
    * 异常路径: 完整路径、保险库标题、URI 查询参数、密钥材料不得进入日志；未知异常映射为可诊断但不泄密的失败。
  * **DoD**: 穷举映射测试通过；无吞异常代码；日志接口不依赖 Android `Log`；敏感样本回归测试通过。
* **任务 1.5: 建立手动依赖容器与测试替身注册表** `[并行]`
  * **依赖**: 任务 1.2、1.3、1.4
  * **TDD 循环**: Red：先证明生产实现无法在 JVM 测试中替换；Green：用构造函数和 `AppContainer` 注入；Refactor：缩小容器暴露面。
  * **测试预期**:
    * 正常路径: Feature 可注入 Fake Repository、Stub Gateway 和 Test Dispatcher 独立运行。
    * 异常路径: 缺失依赖在应用启动时快速失败；测试替身不得被打包进 Release。
  * **DoD**: 无 Service Locator 式任意查找；生产/测试容器分离；依赖创建顺序有测试；未引入 DI 框架。
* **任务 1.6: 建立 CI 质量门禁** `[并行]`
  * **依赖**: 任务 1.1、1.3
  * **TDD 循环**: Red：提交一个故意失败测试验证 CI 会阻断；Green：配置最小 GitHub Actions；Refactor：缓存 Gradle 并消除重复步骤。
  * **测试预期**:
    * 正常路径: Pull Request/Push 执行单元测试、Lint、Debug 构建和 `git diff --check`。
    * 异常路径: 测试失败、Lint Error、Wrapper 校验失败、未提交生成文件时流水线失败；密钥不得打印。
  * **DoD**: CI 使用 JDK 21、固定 Action 主版本与最小权限；构建产物可下载；失败可定位；本地命令与 CI 一致。
* **任务 1.7: Phase 1 交付与清理**
  * **依赖**: 任务 1.1-1.6
  * **TDD 循环**: Red：从全新克隆复现一次失败构建并记录缺口；Green：修复到全部门禁通过；Refactor：删除临时样例、重复 Fixture 和未使用依赖。
  * **测试预期**:
    * 正常路径: 全新克隆后单命令完成测试、Lint 与 Debug APK 构建。
    * 异常路径: 离线缓存缺失、路径含空格、Windows/CI 换行差异不会产生不可解释失败。
  * **DoD**: `./gradlew testDebugUnitTest lintDebug assembleDebug` 通过；工作树无生成垃圾；架构与构建文档更新；仅此节点执行阶段提交和推送。

**【并行开发分配建议】**

任务 1.3、1.4、1.6 可在 1.1/1.2 契约冻结后并行。各分支只依赖 `AppDispatchers`、`AppClock`、`AppFailure` 等接口；用手写 Fake/Stub 替代真实 Room、MediaStore 和 Android 日志。1.5 最后汇合并验证替身不会泄漏到生产包。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 更新 `00-android-technology-selection.md` 的实装版本、补充 `docs/architecture/build-and-test-baseline.md` 与 CI 命令。
* **Commit 建议**: `chore(build): establish Android and TDD baseline`
* **推送指令**: `./gradlew testDebugUnitTest lintDebug assembleDebug && git add . && git commit -m "chore(build): establish Android and TDD baseline" && git push origin main`

---
## Phase 2: 设计系统、自适应导航与页面状态壳
**【阶段目标】**

实现与三个 HTML 原型一致的可安装产品壳：Compact 窗口使用“首页 / 视频 / 整理”三项底栏，Medium/Expanded 窗口切换导航轨，播放页隐藏一级导航；处理中心和设置默认保留在顶部；建立浅色、深色、跟随系统及纯黑播放画布的单一 Token 事实源。

**【接口与契约定义】**

- `RootDestination`：固定 `Home`、`Library`、`Organize`，以及仅在用户启用后出现的 `Processing`；`Settings` 始终是顶部/页面内入口，不进入一级导航。
- `GlobalAppAction`：`OpenProcessing`、`OpenSettings`；各一级页标题区共享动作语义，但可按页面状态隐藏不可用动作。
- `NavigationStateStore`：保存各一级页面的导航栈和页面键，不保存业务实体对象。
- `PageViewStateStore`：按页面和主分组保存滚动、视图、排序；临时筛选只在会话内。
- `ThemeRepository`：暴露 `Flow<ThemePreference>`；`ThemePreference` 为 Light/Dark/System。
- `YingLiPrimitiveTokens`、`YingLiSemanticTokens`、`YingLiComponentTokens`：禁止业务组件直接引用 Primitive 或 Hex。
- `PlayerScrimTokens`、`FunctionalColorTokens`、`SystemBarTokens`：浅色 B、深色 A；结构选中无彩；钢蓝用于焦点/链接/信息/处理中；播放页使用独立纯黑与白色透明度映射。
- `YingLiIcon`：以 Tabler Icons 的语义名称为主；缺失图标才使用 Material Symbols，业务代码不得直接绑定第三方图标资源 ID。

**【任务节点树】**

* **任务 2.1: 将颜色、排版、间距和状态层收敛为三层 Token**
  * **依赖**: 任务 1.7
  * **TDD 循环**: Red：先以 Draft 03 的安全配对矩阵、主题映射和禁止配对建立失败断言；Green：实现 Primitive/Semantic/Component 最小 Token 集；Refactor：消除组件内硬编码色值和临时透明度。
  * **测试预期**:
    * 正常路径: 13 级中性灰、浅色 B/深色 A 四类功能色、深色 Level 0-3 表面、浅/深 Hover 6%/8%、Pressed 10%/12%、Dragged 14%/16% 状态层、浅色 Scrim 32%/56%/72%、深色 Scrim 40%/64%/80%、阴影、文本选中和滚动条均有稳定语义名；播放器白色 100/70/40 满足配对要求。
    * 异常路径: 正文低于 4.5:1、UI/焦点边界低于 3:1、业务层直接使用 Primitive/Hex、用成功绿表示观看进度、播放器时间在最亮画面叠加 72% 边缘 Scrim 后仍不足 4.5:1 时测试失败。
  * **DoD**: 浅/深主题完整配对矩阵自动测试通过；错误/成功在灰度和绿色弱模拟中仍由图标与文字区分；Compose/XML 导出名称统一为 YingLi；原型 Token 均可追踪。
* **任务 2.2: 实现三态主题与系统栏映射** `[并行]`
  * **依赖**: 任务 2.1
  * **TDD 循环**: Red：先测试主题切换和进程重建丢失；Green：实现 ThemeRepository 与映射；Refactor：分离应用表面和播放器画布。
  * **测试预期**:
    * 正常路径: Light/Dark/System 正确持久化；浅色使用 B 档、深色使用 A 档；播放画布始终纯黑，并分别应用浅色深图标、深色浅图标、播放页浅图标/黑色导航区三套 edge-to-edge 映射。
    * 异常路径: 无效偏好回退 System；系统主题快速切换不闪白；Material You 默认关闭，启用后最多影响低饱和中性表面，不得污染结构选中和固定功能色。
  * **DoD**: JVM 状态测试与 Compose 主题测试通过；三套系统栏映射和深色“海拔越高表面越亮”规则完成；无简单颜色反转。
* **任务 2.3: 定义根导航、全局动作与来源返回契约** `[并行]`
  * **依赖**: 任务 1.7
  * **TDD 循环**: Red：先覆盖重复点击、深链和来源返回错误；Green：实现最小导航状态机；Refactor：去除页面对 NavController 的散乱调用。
  * **测试预期**:
    * 正常路径: 三个固定一级页面独立恢复栈；处理中心默认由顶部动作打开，用户启用后作为第四入口；详情和播放返回来源页面及原状态。
    * 异常路径: 未知路由安全回首页；重复导航不创建重复目的地；关闭“固定处理中心”时从第四入口安全迁回顶部入口；播放页不显示一级导航。
  * **DoD**: 导航状态机 JVM 测试、Compose 导航测试通过；路由参数只传稳定 ID；无业务对象序列化进 Bundle。
* **任务 2.4: 实现 Compact/Medium/Expanded 自适应导航壳**
  * **依赖**: 任务 2.2、2.3
  * **TDD 循环**: Red：先在边界宽度测试底栏/导航轨切换失败；Green：实现原型 F 最小布局；Refactor：共享导航项和语义。
  * **测试预期**:
    * 正常路径: Compact 显示三项底栏，Medium/Expanded 使用导航轨；处理中心与设置位于顶部；可选第四入口在两种导航形态中同步；尺寸变化保持当前页面和各页状态。
    * 异常路径: 多窗口反复跨断点不重建业务状态；横屏/折叠态不出现重叠、截断或五项底栏；播放页和应用锁页不得残留一级导航。
  * **DoD**: 三类窗口及折叠/横屏 Compose 布局测试通过；所有触控目标不少于 48dp；黑白反转、图标、文字和 `aria-current` 对应语义共同表达选中态。
* **任务 2.5: 实现一级页面状态保存骨架** `[并行]`
  * **依赖**: 任务 2.3
  * **TDD 循环**: Red：先证明跨页面切换后滚动/视图混用；Green：实现按页面和分组的 Key；Refactor：把持久与会话状态分开。
  * **测试预期**:
    * 正常路径: 页面滚动、视图和排序独立恢复；临时筛选离开会话后清除。
    * 异常路径: 分组删除、偏好版本不兼容、无效滚动位置时安全回默认值。
  * **DoD**: Fake Store 单元测试与 SavedState 恢复测试通过；状态键有版本；无静态全局可变状态。
* **任务 2.6: 建立基础组件、图标语义与字体放大门禁** `[并行]`
  * **依赖**: 任务 2.1
  * **TDD 循环**: Red：先让缺失 contentDescription、过小触控区和大字截断测试失败；Green：补齐最小语义；Refactor：沉淀共用组件。
  * **测试预期**:
    * 正常路径: Tabler 语义图标、按钮、图标按钮、输入框、Chip、复选、单选、开关、分段控件、滑块、菜单、Dialog、Banner、空/加载/禁用态均可被读屏识别；200% 字体下关键操作仍可用；状态均有图标和文字。
    * 异常路径: 缺少 Tabler 图标时未走 Material Symbols 回退、禁用态仍可点击、重复语义、仅靠颜色表达状态、长文件名撑高固定卡片时测试失败。
  * **DoD**: Semantics 和布局测试通过；基础组件只消费 Component Token；Tabler/Material 回退映射集中管理；无嵌套卡片和不稳定尺寸。
* **任务 2.7: 建立原型一致性契约测试** `[并行]`
  * **依赖**: 任务 2.1、2.2、2.4、2.6
  * **TDD 循环**: Red：先为三个原型中的已冻结决策建立失败的结构/Token/语义断言；Green：实现最小 `PrototypeConformanceTest`；Refactor：测试通过语义角色而非像素坐标定位组件。
  * **测试预期**:
    * 正常路径: F 方案导航、Draft 03 选中规则、浅 B/深 A、三套系统栏、播放器无彩控件和状态“颜色 + 图标 + 文字”均有自动断言。
    * 异常路径: 恢复 `01` 的绿色选中/链接/观看进度、动态色染色功能色、增加未配置一级入口或业务组件出现直接 Hex 时门禁失败。
  * **DoD**: 原型到 Compose 的决策追踪表完整；测试不依赖 HTML DOM 或像素截图；每条冻结规则至少有一条自动化断言。
* **任务 2.8: Phase 2 交付与清理**
  * **依赖**: 任务 2.1-2.7
  * **TDD 循环**: Red：用三类窗口与三种主题跑完整导航矩阵；Green：修复所有差异；Refactor：删除原型临时代码和重复 Token。
  * **测试预期**:
    * 正常路径: 空数据应用可稳定导航、切换主题和恢复页面状态。
    * 异常路径: 旋转、分屏、进程重建和字体放大不产生重叠、闪白或状态丢失。
  * **DoD**: 单元、Compose、原型契约、Lint、Debug 构建全通过；三个原型与 Compose 的差异/取舍有记录；仅此节点提交推送。

**【并行开发分配建议】**

2.1 冻结 Token 名后，2.2 与 2.6 可并行；2.3 冻结路由后，2.5 可使用 Fake `NavigationStateStore` 并行。2.4 仅依赖主题与导航接口，不等待业务页面，页面内容统一用确定尺寸 Stub；2.7 读取冻结的契约清单，不依赖真实业务数据或 HTML 运行时。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 回填三个原型的事实源优先级、Compose 映射、Token 名、窗口断点、图标回退和无障碍测试矩阵。
* **Commit 建议**: `feat(shell): add adaptive navigation and design system`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(shell): add adaptive navigation and design system" && git push origin main`

---
## Phase 3: 媒体领域模型、授权与增量索引
**【阶段目标】**

建立“逻辑媒体实体与物理位置分离”的媒体基础设施，完成推荐全部文件访问、SAF/MediaStore 完整降级、首次后台扫描、增量索引、重定位和缩略图队列；缓存首屏目标小于 500ms，指定真机 1 万条轻量增量索引目标小于 3 秒。

**【接口与契约定义】**

- 模型：`MediaSource`、`MediaItem`、`MediaLocation`、`MediaIdentityEvidence`、`ScanRequest`、`ScanResult`、`ScanFailure`、`ThumbnailRequest`。
- `MediaPermissionGateway`：只返回权限能力状态和用户动作结果，不在 Repository 内启动 Activity。
- `MediaDiscoveryDataSource`：分别实现 MediaStore、SAF Tree、全部文件访问；输出统一候选流。
- `MediaCatalogRepository`、`MediaSourceRepository`、`MediaIdentityResolver`、`MediaScanner`、`ThumbnailRepository`。
- 测试 Stub 使用内存 URI 和虚拟卷；禁止 JVM 测试访问真实共享存储。

**【任务节点树】**

* **任务 3.1: 定义媒体实体、位置与身份规则**
  * **依赖**: 任务 2.8
  * **TDD 循环**: Red：先覆盖同内容多路径、路径变化和证据不足；Green：实现最小值对象与身份决策；Refactor：把路径从逻辑 ID 中移除。
  * **测试预期**:
    * 正常路径: 一个 MediaItem 可关联多个 MediaLocation，标签和进度归逻辑实体。
    * 异常路径: 同名不同内容不得合并；大小/时间冲突时保持独立；删除单个位置不删除其他位置关系。
  * **DoD**: 值对象不可变；相等性和序列化测试通过；不存在裸 String ID；规则对应 FR-SOURCE-001/Q584。
* **任务 3.2: 建立 Room v1 Schema 与 DAO** `[并行]`
  * **依赖**: 任务 3.1
  * **TDD 循环**: Red：先写唯一约束、级联和事务失败测试；Green：创建最小表与 DAO；Refactor：优化索引而不复制系统事实为唯一真相。
  * **测试预期**:
    * 正常路径: 源、逻辑媒体、位置和关系可事务写入并 Flow 观察。
    * 异常路径: 重复位置、孤儿关系、事务中断、数据库关闭时保持一致并返回明确失败。
  * **DoD**: 导出 Room Schema；DAO 仪器测试通过；查询计划无明显全表扫描；数据库 API 不泄漏 Entity 到 UI。
* **任务 3.3: 实现权限能力状态机** `[并行]`
  * **依赖**: 任务 3.1
  * **TDD 循环**: Red：覆盖拒绝、取消、部分授权、持久授权丢失；Green：实现最小状态机；Refactor：分离说明页、系统跳转和结果处理。
  * **测试预期**:
    * 正常路径: 用户主动选择全部文件访问或 SAF，授权后立即触发后台索引。
    * 异常路径: 不自动跳系统页；拒绝全部文件访问后完整降级；SAF 取消不阻断进入空首页；失效权限可重新授权。
  * **DoD**: 状态机 JVM 测试和 Activity Result 仪器测试通过；不循环骚扰授权；权限文案与实际能力一致。
* **任务 3.4: 实现 MediaStore 发现适配器** `[并行]`
  * **依赖**: 任务 3.1
  * **TDD 循环**: Red：用空游标、缺列、重复 ID 和 SecurityException 建立失败测试；Green：实现最小查询映射；Refactor：批量读取并限制列集合。
  * **测试预期**:
    * 正常路径: 视频事实映射为候选位置，分页/批次输出且不阻塞主线程。
    * 异常路径: 空库、损坏行、权限变化和已删除行不会终止整批扫描。
  * **DoD**: Adapter Contract Test 通过；只查询必要列；I/O 调度器可注入；无真实路径依赖。
* **任务 3.5: 实现 SAF Tree 与持久 URI 适配器** `[并行]`
  * **依赖**: 任务 3.1、3.3
  * **TDD 循环**: Red：覆盖深树、循环提供者、失效 URI 和慢 Provider；Green：实现可取消遍历；Refactor：抽取 Document Stub Contract。
  * **测试预期**:
    * 正常路径: 单目录、SD/USB 授权树可发现视频并保存持久权限。
    * 异常路径: Provider 抛错只影响当前节点；取消及时停止；重复 URI 去重；移出授权树标记缺失。
  * **DoD**: 虚拟 DocumentProvider 仪器测试通过；遍历有深度/取消保护；不读取未授权树。
* **任务 3.6: 实现增量索引协调器**
  * **依赖**: 任务 3.2、3.4、3.5
  * **TDD 循环**: Red：覆盖全量重建、重复写入和中途取消污染；Green：实现轻量 diff 与批量事务；Refactor：拆分发现、解析、合并三步。
  * **测试预期**:
    * 正常路径: 首次和增量扫描产生新增/更新/缺失摘要，新内容增量进入 Flow。
    * 异常路径: 单文件解析失败不终止；取消不清空旧索引；权限中途丢失保留关系；不支持格式单独统计。
  * **DoD**: 1k/10k 合成数据 JVM 基准完成；批量事务测试通过；扫描无主线程 I/O；无虚构百分比。
* **任务 3.7: 实现媒体身份重定位** `[并行]`
  * **依赖**: 任务 3.1、3.2
  * **TDD 循环**: Red：先覆盖移动、重命名、卷重连和哈希碰撞；Green：实现低成本证据链；Refactor：完整哈希仅在候选冲突时运行。
  * **测试预期**:
    * 正常路径: 全部文件访问下跨目录重定位；SAF 范围内重关联；SD/USB 重连恢复原源。
    * 异常路径: 证据不足不自动合并；卷标冲突要求用户确认；连续 3 次或 7 天前不清理缺失记录。
  * **DoD**: 规则表驱动测试通过；哈希运行在 Default/IO；关系与用户数据不随路径丢失。
* **任务 3.8: 实现分级缩略图队列** `[并行]`
  * **依赖**: 任务 3.1、3.6
  * **TDD 循环**: Red：覆盖快速滚动任务堆积、抽帧失败和缓存超限；Green：实现可见优先与受限并发；Refactor：统一 Coil Key 和取消语义。
  * **测试预期**:
    * 正常路径: 可见项、继续观看、最近添加按优先级生成，失败回退首帧。
    * 异常路径: 离屏任务取消；损坏视频显示占位和重试；缓存达到上限按 LRU 清理。
  * **DoD**: 队列单元测试和 Coil 集成测试通过；无无限重试；内存/磁盘上限可配置；首屏不等待全库缩略图。
* **任务 3.9: 实现首次引导、空首页与媒体源管理 UI**
  * **依赖**: 任务 3.3、3.6、3.8
  * **TDD 循环**: Red：先覆盖强制授权、空白等待和错误阻塞；Green：实现“现在添加/稍后添加”最小流程；Refactor：UI 仅消费状态并发事件。
  * **测试预期**:
    * 正常路径: 稍后添加进入空首页；授权后立即后台索引；已有缓存立即显示。
    * 异常路径: 权限拒绝、卷离线、超时扫描显示非阻塞恢复操作；不得显示伪百分比或逐文件路径。
  * **DoD**: Compose 状态矩阵测试通过；TalkBack 文案完整；页面不直接调用 ContentResolver；返回路径正确。
* **任务 3.10: Phase 3 交付与清理**
  * **依赖**: 任务 3.1-3.9
  * **TDD 循环**: Red：在空库、10k 库、权限拒绝、卷离线四套场景跑端到端；Green：修复门禁；Refactor：删除扫描临时代码和重复映射。
  * **测试预期**:
    * 正常路径: 当前真机授权后可在秒级看到真实媒体，重启后缓存先显示并增量更新。
    * 异常路径: 拒绝、取消、损坏文件、卷拔出和进程终止不会丢失已有索引或用户关系。
  * **DoD**: JVM、Room、权限仪器、Compose、真机性能记录全部通过；Schema 和权限文档更新；仅此节点提交推送。

**【并行开发分配建议】**

3.1 冻结模型后，3.2、3.3、3.4、3.7 可并行；3.5 使用 Fake `MediaPermissionGateway`；3.8 使用 Stub `ThumbnailExtractor` 和内存缓存。3.6 只依赖 `MediaDiscoveryDataSource` 契约，可先用确定顺序的 Fake Candidate Flow 开发，最后执行同一套 Contract Test 验证真实适配器。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 更新媒体实体 ER 图、权限降级表、扫描算法、性能数据集和已验证设备结果。
* **Commit 建议**: `feat(media): add sources permissions and incremental index`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(media): add sources permissions and incremental index" && git push origin main`

---
## Phase 4: Media3 基础播放垂直切片
**【阶段目标】**

完成从真实媒体卡到首帧、暂停、进度保存、错误恢复的最小播放闭环。Player 与 MediaSession 独立于 Activity/Compose 生命周期，页面、迷你播放器占位、通知和系统控制共享同一会话事实源。

**【接口与契约定义】**

- `PlaybackRequest`：媒体 ID、位置 ID、起播位置、来源上下文、无痕标志；不得携带 ExoPlayer 类型。
- `PlaybackState`：Idle/Preparing/Ready/Playing/Paused/Ended/Failed，包含位置、时长、缓冲和可用动作。
- `PlaybackController`：命令接口；`PlaybackStateRepository`：只读状态流；`PlaybackProgressRepository`：持久化策略。
- `PlaybackErrorMapper`：区分权限、文件缺失、容器不支持、解码器不支持、损坏与未知错误。
- 单元测试使用 `FakePlaybackController`；Media3 适配器使用 `media3-test-utils`/真机 Contract Test，不在 ViewModel 测试中启动 Service。

**【任务节点树】**

* **任务 4.1: 定义播放状态机与命令幂等性**
  * **依赖**: 任务 3.10
  * **TDD 循环**: Red：先覆盖非法状态跳转和重复命令；Green：实现纯 Kotlin 状态归约器；Refactor：把 Media3 事件映射放到边界层。
  * **测试预期**:
    * 正常路径: Prepare、Play、Pause、Seek、Stop、End 状态转换确定且可重放。
    * 异常路径: Idle Seek、重复 Prepare、结束后 Play、失败后 Retry 不造成崩溃或双会话。
  * **DoD**: 状态转移表全覆盖；命令幂等；无 Android 类型；分支测试全部通过。
* **任务 4.2: 实现 MediaSessionService 与 ExoPlayer 作用域**
  * **依赖**: 任务 4.1
  * **TDD 循环**: Red：先证明 Activity 重建会丢 Player；Green：把会话移入 Service；Refactor：隔离 Media3 Adapter。
  * **测试预期**:
    * 正常路径: Service 创建一次 Player/Session，Controller 重连后恢复状态。
    * 异常路径: Controller 断开、Service 重建、非法 URI 和资源释放时无泄漏或双重 release。
  * **DoD**: Media3 Service 仪器测试通过；生命周期成对；Composable 不创建 Player；前台服务行为符合平台要求。
* **任务 4.3: 实现 MediaController 连接适配器** `[并行]`
  * **依赖**: 任务 4.1
  * **TDD 循环**: Red：覆盖未连接命令和重连竞态；Green：排队或拒绝命令；Refactor：将连接状态并入只读 Flow。
  * **测试预期**:
    * 正常路径: UI 订阅单一状态流并发送命令，旋转后无重复订阅。
    * 异常路径: 连接超时、Service 不可用、命令在断连期间触发时返回可恢复失败。
  * **DoD**: Fake Session Contract 与仪器测试通过；无轮询 Player；取消订阅无泄漏。
* **任务 4.4: 实现基础播放页与纯黑画布**
  * **依赖**: 任务 4.2、4.3
  * **TDD 循环**: Red：先测试首帧前空白、重组重载和控件状态错位；Green：实现最小画面、播放/暂停、进度和返回；Refactor：状态提升。
  * **测试预期**:
    * 正常路径: 点击媒体卡打开播放页，画布纯黑，首帧后控制层正确同步。
    * 异常路径: 位置未知、时长未知、首帧超时和 Surface 重建不导致假进度或状态丢失。
  * **DoD**: Compose UI 测试与真机首帧验证通过；播放页隐藏一级导航；无主线程媒体 I/O。
* **任务 4.5: 实现进度内存更新与持久化节流** `[并行]`
  * **依赖**: 任务 4.1、3.2
  * **TDD 循环**: Red：覆盖每帧写库、生命周期漏写和无痕误写；Green：实现约 5 秒及关键事件写入；Refactor：用 Clock/Dispatcher 控制节流。
  * **测试预期**:
    * 正常路径: 位置实时展示，暂停、停止、后台和结束时最终进度落库。
    * 异常路径: 无痕播放不写；数据库失败不阻塞播放；位置越界钳制；重复事件不重复计数。
  * **DoD**: 虚拟时间测试通过；写入次数有上限；播放线程不等待数据库；失败可诊断。
* **任务 4.6: 实现基础续播与结束规则** `[并行]`
  * **依赖**: 任务 4.1、4.5
  * **TDD 循环**: Red：覆盖 3 秒回退负值、接近结尾和默认自动下一项；Green：实现规则；Refactor：抽取纯函数策略。
  * **测试预期**:
    * 正常路径: 常规视频从上次位置前 3 秒恢复；结束页提供重播、下一项、返回。
    * 异常路径: 小于 3 秒钳制 0；已看完默认从头；连续播放默认关闭；无下一项时动作禁用。
  * **DoD**: 边界表驱动测试通过；规则可配置但默认与 Q556/Q577 一致；UI 无隐式自动播放。
* **任务 4.7: 实现播放错误分类与最小恢复动作** `[并行]`
  * **依赖**: 任务 4.1、4.2
  * **TDD 循环**: Red：将所有 Media3 异常映射为“播放失败”并证明不可诊断；Green：分类映射；Refactor：用户动作与技术日志分离。
  * **测试预期**:
    * 正常路径: 权限失效、文件缺失、不支持编码、损坏分别显示重授权、重定位、兼容说明或重试。
    * 异常路径: 未知异常不泄露路径；失败不自动跳过；重试不创建重复会话。
  * **DoD**: 错误映射穷举测试、损坏样本真机测试通过；播放状态保持可恢复；日志已脱敏。
* **任务 4.8: Phase 4 交付与清理**
  * **依赖**: 任务 4.1-4.7
  * **TDD 循环**: Red：用 MP4、HEVC、损坏、失权 URI 跑端到端；Green：修复；Refactor：清除页面持有的 Media3 细节。
  * **测试预期**:
    * 正常路径: 从媒体库进入播放、旋转、暂停、续播、结束、返回完整闭环稳定。
    * 异常路径: 进程/Activity 重建、权限撤销、解码失败不崩溃、不静默跳片、不丢最后进度。
  * **DoD**: JVM、Media3 仪器、Compose 与指定真机媒体样本通过；记录点击到首帧基线；仅此节点提交推送。

**【并行开发分配建议】**

4.1 冻结后，4.2、4.3、4.5、4.6、4.7 可并行。UI 分支使用 `FakePlaybackController` 和脚本化 `PlaybackState`；Service 分支用无 UI 的 Controller Contract Test；进度分支用内存 Repository 与虚拟时钟。最终只在 4.8 连接真实 Media3。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 增加播放状态图、会话生命周期、错误矩阵、已验证媒体样本和首帧基线。
* **Commit 建议**: `feat(player): deliver Media3 playback vertical slice`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(player): deliver Media3 playback vertical slice" && git push origin main`

---
## Phase 5: 视频库、搜索、筛选与安全文件操作（M3）
**【阶段目标】**

把已索引媒体变成可扫描、可搜索、可排序、可筛选的媒体库，并完成重命名、移动、删除、回收站与权限失效提示。首版默认网格，同时支持列表与瀑布流；“缩略图”不作为第四种结构，而是通过尺寸滑块控制网格/瀑布流的信息密度。

**【接口与契约定义】**

- `LibraryQuery`：关键词、分组、视图模式、分页游标、`SortSpec`；空关键词不得触发全表模糊扫描。
- `LibraryDisplayPreference`：`Grid`、`List`、`Waterfall` 与独立的 `thumbnailScale`；布局切换不得改变查询结果。
- `FilterExpression`：字段过滤、多个标签 AND、可选 OR/排除；表达式可序列化，组合标签不允许嵌套。
- `LibraryRepository`、`SearchRepository`：只返回领域模型和稳定游标，不暴露 Room 类型。
- `FileOperationGateway`：rename/move/trash/restore/purge；每个操作返回可恢复错误。
- `TrashRepository`：软删除元数据、删除时间、恢复源、自动清理策略。

**【任务节点树】**

* **任务 5.1: 定义查询、排序和筛选表达式**
  * **依赖**: 任务 3.10
  * **TDD 循环**: Red：先覆盖空查询、未知字段、边界时长和组合逻辑；Green：实现不可变值对象与规范化；Refactor：统一解析器和排序比较器。
  * **测试预期**:
    * 正常路径: 最近添加、时长、名称、播放次数均支持升序/降序；多标签默认 AND，OR 和排除可表达。
    * 异常路径: 非法范围、空标签、重复条件、超长关键词被拒绝或规范化，不产生全库错误结果。
  * **DoD**: 属性测试覆盖交换律/结合律适用规则；序列化 round-trip 通过；无 Android/Room 依赖。
* **任务 5.2: 实现 LibraryRepository 查询适配器** `[并行]`
  * **依赖**: 任务 5.1、3.2
  * **TDD 循环**: Red：覆盖分页重复、数据变更和空库；Green：实现 Room 查询与稳定排序；Refactor：将投影映射集中到 mapper。
  * **测试预期**:
    * 正常路径: 查询结果按游标连续分页，过滤和排序组合稳定，全部视频始终可达。
    * 异常路径: 数据库忙、游标过期、媒体被删除时返回可重试状态且不重复展示。
  * **DoD**: DAO 集成测试、查询契约测试通过；关键字段有索引；无 N+1 查询。
* **任务 5.3: 实现搜索索引与防抖查询** `[并行]`
  * **依赖**: 任务 5.1、5.2
  * **TDD 循环**: Red：覆盖快速输入、大小写、全角半角和取消；Green：实现本地索引/Room LIKE 策略与 250ms 防抖；Refactor：查询取消使用结构化并发。
  * **测试预期**:
    * 正常路径: 名称和路径别名可搜索，结果按当前排序返回，无搜索建议。
    * 异常路径: 旧查询晚到、索引不可用、特殊字符和空结果均不覆盖新状态。
  * **DoD**: 虚拟时间测试证明只执行最后一次查询；基准数据集响应达标；查询不阻塞主线程。
* **任务 5.4: 实现库页状态、三种布局与缩略图密度** `[并行]`
  * **依赖**: 任务 5.2、5.3、3.9
  * **TDD 循环**: Red：覆盖加载、空结果、错误、选择、布局切换、缩略图尺寸和旋转；Green：实现统一 `LibraryUiState` 与网格/列表/瀑布流；Refactor：抽取共用卡片语义和缩略图密度策略。
  * **测试预期**:
    * 正常路径: 切换 Grid/List/Waterfall 或调整缩略图尺寸不丢查询和滚动锚点；卡片展示缩略图、时长、日期/分辨率；网格名称最多一行、列表最多两行，详情/编辑显示完整名称；观看进度使用白色 90% 并辅以可读进度语义。
    * 异常路径: 缩略图缺失、超宽/超高封面、屏幕窄、字体放大、多选和缩略图尺寸极值时不溢出、不误触；不得用成功绿表达观看进度。
  * **DoD**: Compose 布局/交互/语义测试通过；TalkBack 能读出名称、时长、分辨率、观看进度和选择态；不使用移动端像素截图门禁；不在 Composable 中查库。
* **任务 5.5: 实现 SAF/MediaStore 文件操作网关** `[并行]`
  * **依赖**: 任务 3.3、5.1
  * **TDD 循环**: Red：覆盖同名、跨卷、无权限和部分成功；Green：实现 rename/move 的事务边界；Refactor：统一 URI 授权与回滚。
  * **测试预期**:
    * 正常路径: 用户授权范围内可重命名、移动并刷新索引，全部文件权限下移动后仍可访问。
    * 异常路径: 冲突、只读、卷拔出、目标不存在时不删除源文件，返回明确动作。
  * **DoD**: Fake gateway 契约测试和 API 31+ 仪器测试通过；操作日志脱敏；无越权 URI 访问。
* **任务 5.6: 实现回收站、恢复与安全删除** `[并行]`
  * **依赖**: 任务 5.5、3.2
  * **TDD 循环**: Red：覆盖重复删除、恢复冲突、过期清理和取消；Green：实现软删除与用户确认；Refactor：将保留天数策略纯函数化。
  * **测试预期**:
    * 正常路径: 删除先进回收站，可恢复原位置；支持配置保留天数，永久删除二次确认。
    * 异常路径: 恢复源失效、文件已被外部删除、清理中断时状态可重试且不误删其他媒体。
  * **DoD**: 时间旅行测试、文件网关集成测试通过；清理幂等；回收站关系与索引一致。
* **任务 5.7: 实现筛选面板与批量操作 UI** `[并行]`
  * **依赖**: 任务 5.1、5.4、5.5、5.6
  * **TDD 循环**: Red：覆盖底部面板开关、条件组合、结果计数、取消和批量失败；Green：Compact 使用 Modal Bottom Sheet，宽屏使用等价侧面板，并实现多选工具条；Refactor：筛选状态与导航事件分离。
  * **测试预期**:
    * 正常路径: 面板展示排序、分辨率、分组、时长、后缀和标签匹配，提供“重置”和“查看 N 个视频”；应用后结果可复现；批量工具条展示选中数并提供加标签、移动和删除。
    * 异常路径: 条件无结果时主动作显示 0 并禁用或给出说明；批量部分失败逐项汇总；返回恢复筛选；面板不遮挡系统返回手势且打开时底层导航不可误触。
  * **DoD**: Bottom Sheet/宽屏面板状态还原与无障碍焦点测试通过；结构选中使用黑白反转；危险命令使用错误红且都有图标、文字和确认。
* **任务 5.8: Phase 5 交付与清理**
  * **依赖**: 任务 5.1-5.7
  * **TDD 循环**: Red：空库、10k 库、部分授权、冲突和卷离线端到端；Green：修复门禁；Refactor：删除假数据和临时筛选状态。
  * **测试预期**:
    * 正常路径: 从“视频”一级入口打开库页；搜索框匹配本地视频/文件夹/标签且不提供建议；快捷 Chip“全部/文件夹/最近/未看”、高级筛选、三种布局、批量整理和回收站恢复闭环稳定。
    * 异常路径: 权限撤销、数据库迁移、操作中进程终止不造成静默丢失。
  * **DoD**: JVM/Room/Compose/真机/Lint 全绿；查询与文件操作基线记录；更新 API、权限和回收站文档；仅此节点提交推送。

**【并行开发分配建议】**

5.1 冻结后，5.2、5.3、5.5、5.6 可并行；5.4/5.7 使用 Fake `LibraryRepository`、Stub 缩略图和脚本化批量结果。文件操作分支只能依赖 `FileOperationGateway`，禁止直接调用系统 API。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 更新查询 DSL、三种布局/缩略图密度、筛选面板状态图、SAF 权限矩阵、回收站数据保留策略和性能基线。
* **Commit 建议**: `feat(library): add searchable filterable media library and safe file operations`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(library): add searchable filterable media library and safe file operations" && git push origin main`

---
## Phase 6: 整理、集合、播放历史与首页（M4）
**【阶段目标】**

交付“整理”一级页的收藏、标签、播放列表、智能集合和最近整理，建立标签组、多种集合和播放历史；让首页以搜索、继续观看、最近添加和常用文件夹构成高频入口。

**【接口与契约定义】**

- `Tag`：用户自定义名称、颜色、创建/更新时间；颜色仅显示为中性标签容器中的圆点；`CompositeTag` 仅是可保存的多标签匹配，不允许嵌套。
- `Favorite`、`Playlist`、`Collection`、`SmartCollection`：实体引用媒体 ID，不复制媒体文件；删除媒体时引用可悬空并可清理。
- `HistoryEntry`、`RecentlyOrganizedEntry`、`ContinueWatchingPolicy`、`HomeSection`：播放次数、最近播放、整理时间、继续观看、常用文件夹和排序规则。

**【任务节点树】**

* **任务 6.1: 定义标签、集合和历史领域模型**
  * **依赖**: 任务 5.8
  * **TDD 循环**: Red：覆盖名称空白、颜色无效、重复关系和悬空引用；Green：实现值对象与约束；Refactor：统一 ID 与时间来源。
  * **测试预期**:
    * 正常路径: 一个视频可收藏、加入多个播放列表并拥有多个标签；集合可手动或按筛选表达式生成；历史和最近整理可分别合并同一媒体。
    * 异常路径: 标签组嵌套、循环引用、未知媒体 ID、非法颜色和时间倒流被拒绝。
  * **DoD**: 领域属性测试和序列化测试全绿；约束表形成文档；无 UI 类型。
* **任务 6.2: 实现标签/集合 Room DAO 与迁移** `[并行]`
  * **依赖**: 任务 6.1、3.2
  * **TDD 循环**: Red：覆盖并发写入、删除标签、悬空集合和迁移；Green：实现关系表、唯一索引和 DAO；Refactor：用事务封装批量变更。
  * **测试预期**:
    * 正常路径: 批量收藏、加/移标签、加入播放列表、重命名标签、保存筛选为智能集合均原子完成。
    * 异常路径: 唯一约束冲突、迁移失败、半事务回滚不留下孤儿关系。
  * **DoD**: Room migration tests、并发 DAO 测试通过；外键策略明确；查询有索引。
* **任务 6.3: 实现播放历史与继续观看策略** `[并行]`
  * **依赖**: 任务 4.5、6.1
  * **TDD 循环**: Red：覆盖短视频、完成阈值、无痕、重复播放和删除媒体；Green：实现策略与节流写入；Refactor：与播放进度复用时间/ID 边界。
  * **测试预期**:
    * 正常路径: 最近播放、播放次数和继续观看按规则更新；完成后从继续观看移除。
    * 异常路径: 播放不足阈值不计数；无痕不写历史；数据库异常不影响播放。
  * **DoD**: 虚拟时钟和重启恢复测试通过；策略参数有默认值和配置入口。
* **任务 6.4: 实现整理页与标签编辑流程** `[并行]`
  * **依赖**: 6.1、6.2、5.7
  * **TDD 循环**: Red：覆盖新建、编辑、删除、批量标记和取消；Green：实现状态机和表单校验；Refactor：共用筛选条件编辑器。
  * **测试预期**:
    * 正常路径: 整理页以收藏、标签、播放列表、智能集合四个入口和“最近整理”模块呈现；可创建标签、从 1 个中性色加 7 个精选色圆点中选择、批量标记并保存组合标签。
    * 异常路径: 重名、空名、离开未保存和部分批量失败有明确处理；用户选择红/绿圆点时不得改变中性标签容器或被误读为错误/成功。
  * **DoD**: UI 状态/语义测试通过；标签文字/中性容器满足 4.5:1；表单错误可读；破坏性操作确认且可撤销。
* **任务 6.5: 实现播放列表、集合与智能集合浏览** `[并行]`
  * **依赖**: 6.2、5.2
  * **TDD 循环**: Red：覆盖空集合、悬空媒体、筛选变更和分页；Green：实现手动/智能集合查询；Refactor：统一 LibraryQuery 执行管线。
  * **测试预期**:
    * 正常路径: 播放列表/集合展示封面、数量和最近项目；智能集合实时反映标签/属性变化；收藏作为系统集合可快速进入。
    * 异常路径: 集合规则损坏、媒体缺失、查询超时不阻塞整理页。
  * **DoD**: Repository contract、DAO 集成和 Compose 测试通过；集合不复制文件。
* **任务 6.6: 实现首页模块编排与空状态** `[并行]`
  * **依赖**: 6.3、6.5、3.9
  * **TDD 循环**: Red：覆盖模块无数据、顺序配置、点击恢复和刷新；Green：实现 HomeSection 编排；Refactor：模块只接收只读 Flow。
  * **测试预期**:
    * 正常路径: 首页标题为“影里”，提供本地搜索；继续观看展示观看百分比和剩余时间；最近添加与常用文件夹按配置展示；点击进入详情、播放或文件夹上下文正确。
    * 异常路径: 某模块查询失败只局部降级；无历史/无文件夹时不显示伪内容；刷新可取消；搜索仍不得提供联网或建议内容。
  * **DoD**: 首页状态矩阵、导航契约、无障碍测试通过；首屏只加载可见模块。
* **任务 6.7: Phase 6 交付与清理**
  * **依赖**: 6.1-6.6
  * **TDD 循环**: Red：首次使用、无标签、批量失败、历史恢复端到端；Green：修复；Refactor：清除临时集合和演示数据。
  * **测试预期**:
    * 正常路径: 首页搜索/继续观看/最近添加/常用文件夹到视频页或播放页，以及整理四入口到收藏/标签/列表/智能集合闭环一致。
    * 异常路径: 媒体删除/重定位后关系保留或明确悬空，不崩溃。
  * **DoD**: 全套测试、Lint、基准样本通过；更新数据字典和导航文档；仅此节点提交推送。

**【并行开发分配建议】**

6.1 完成后 6.2、6.3、6.4、6.5、6.6 可并行。UI 使用 Fake `TagRepository`、Fake `HistoryRepository`；智能集合使用固定 Candidate Set，避免等待真实库查询。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 更新收藏/标签/播放列表/集合 ER 图、历史与最近整理策略、首页模块契约和数据迁移说明。
* **Commit 建议**: `feat(organize): add tags collections history and home sections`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(organize): add tags collections history and home sections" && git push origin main`

---
## Phase 7: 高级播放、系统集成与截图（M5）
**【阶段目标】**

完善视频播放页的控制、手势、倍速、音轨/字幕、旋转、画中画、音频焦点、媒体通知和截图；播放器优先展示视频本身，控件按需出现。

**【接口与契约定义】**

- `PlaybackQueueRepository`：显式队列和下一项规则；连续播放默认关闭。
- `TrackPreferenceRepository`：全局与按视频的音轨/字幕/倍速偏好。
- `AudioFocusGateway`、`PictureInPictureGateway`、`MediaNotificationGateway`、`ScreenshotGateway`。
- `PlayerOverlayState`：控件可见性、手势锁、进度拖动、缓冲和错误 scrim 状态。
- `PlayerControlLayout`：顶部返回/标题/更多，中部后退 10 秒/播放暂停/前进 10 秒，底部 Seek/时间/字幕/倍速/旋转/锁定/全屏；控制层只消费 `PlayerOverlayState`。

**【任务节点树】**

* **任务 7.1: 定义播放控制与偏好契约**
  * **依赖**: 任务 4.8、6.7
  * **TDD 循环**: Red：覆盖局部/全局偏好优先级、非法倍速和队列边界；Green：实现纯 Kotlin 决策器；Refactor：合并重复规则。
  * **测试预期**:
    * 正常路径: 0.5x–4x 固定步进、按视频覆盖全局、队列下一项可预测。
    * 异常路径: 无音轨/字幕、队列末尾、未知偏好回退默认且不崩溃。
  * **DoD**: 决策表和属性测试全绿；契约不依赖 Media3。
* **任务 7.2: 实现手势、控制层和自动隐藏** `[并行]`
  * **依赖**: 7.1、4.4
  * **TDD 循环**: Red：覆盖单击、双击、滑动 seek、误触、锁定和超时；Green：实现 Overlay reducer；Refactor：把指针事件映射与 UI 分离。
  * **测试预期**:
    * 正常路径: 纯黑画布上按原型分为顶部/中部/底部三层；后退/前进固定 10 秒；控件按需显隐；Seek 同时展示 24% 轨道、40% 缓冲、100% 已播放层级，拖动时轨道由 4dp 增至 6dp、触点增至 20dp；锁定后只保留解锁动作。
    * 异常路径: 多指、快速反向拖动、系统手势冲突、时长未知和缓冲值倒退不产生跳变；视频极亮/极暗画面下控制层仍依赖 72% 边缘 Scrim 保持可读。
  * **DoD**: Compose interaction、语义、TalkBack 操作路径和不同屏幕比例通过；主要/次要/不确定控件严格使用白色 100/70/40，不引入功能色。
* **任务 7.3: 实现音轨、字幕、倍速和画面适应** `[并行]`
  * **依赖**: 7.1、4.2
  * **TDD 循环**: Red：覆盖轨道缺失、切换失败、fit/fill/原始比例和偏好恢复；Green：实现 Media3 track selector 适配；Refactor：错误映射复用 4.7。
  * **测试预期**:
    * 正常路径: 默认适应屏幕，可选原始/裁剪；音轨字幕菜单可操作；倍速选中使用黑白反转；全局与单视频偏好生效。
    * 异常路径: 不支持字幕、HDR/多音轨异常、切换中退出不会留下错误选中态。
  * **DoD**: 含多轨样本的仪器测试通过；选择态、对比度和状态图标符合视觉 token。
* **任务 7.4: 实现旋转、全屏、系统栏和画中画** `[并行]`
  * **依赖**: 7.2、7.3
  * **TDD 循环**: Red：覆盖旋转重组、edge-to-edge、锁定方向和 PiP 进入/退出；Green：实现 Window/Activity 与 Media3 集成；Refactor：统一生命周期 owner。
  * **测试预期**:
    * 正常路径: 横竖屏切换不丢位置；全屏隐藏一级导航；PiP 恢复同一会话。
    * 异常路径: PiP 不可用、权限拒绝、旋转竞态和后台限制均可回退播放页。
  * **DoD**: API 31+ 仪器测试、配置变更和系统栏状态断言通过；不使用移动端像素截图门禁。
* **任务 7.5: 实现音频焦点、通知和耳机按键** `[并行]`
  * **依赖**: 7.1、4.2
  * **TDD 循环**: Red：覆盖焦点丢失、短暂 duck、蓝牙断开和按键重复；Green：实现 AudioFocus/MediaSession 回调；Refactor：适配器只转译平台事件。
  * **测试预期**:
    * 正常路径: 来电暂停/恢复规则明确，通知操作与页面一致，耳机播放键可控制。
    * 异常路径: 无通知权限、焦点拒绝、Service 被回收不会泄漏或自动播放。
  * **DoD**: MediaSession 和通知仪器测试通过；隐私模式不显示敏感标题。
* **任务 7.6: 实现截图与媒体元数据动作** `[并行]`
  * **依赖**: 7.2、5.5
  * **TDD 循环**: Red：覆盖 Surface 截图失败、权限、命名冲突和取消；Green：实现系统截图 API/PixelCopy 适配；Refactor：统一输出目录和结果通知。
  * **测试预期**:
    * 正常路径: 播放帧保存到图库/配置目录，文件名含视频名和时间戳，可从播放页查看结果。
    * 异常路径: 空帧、磁盘不足、只读目录和重复命名可恢复，不覆盖原文件。
  * **DoD**: 真机截图样本、空间错误和无障碍反馈通过；不在主线程编码。
* **任务 7.7: 实现可配置迷你播放器** `[并行]`
  * **依赖**: 7.2、6.6
  * **TDD 循环**: Red：覆盖开关、返回栈、旋转和关闭；Green：实现配置项和最小浮层；Refactor：复用 PlayerOverlayState。
  * **测试预期**:
    * 正常路径: 用户可决定是否启用；启用时以含缩略图、播放状态和“正在播放”语义的紧凑浮层跨一级页面保留当前会话，Compact 位于底栏上方，导航轨布局位于内容边缘。
    * 异常路径: 播放结束、Service 断连、屏幕窄、Bottom Sheet/Dialog 打开时浮层自动隐藏或收敛，不遮挡主操作和无障碍焦点。
  * **DoD**: 配置持久化、Compose 导航/遮挡/焦点测试通过；默认值符合 Q578；视觉只使用中性表面和结构选中 Token。
* **任务 7.8: Phase 7 交付与清理**
  * **依赖**: 7.1-7.7
  * **TDD 循环**: Red：多轨、旋转、PiP、焦点、截图和迷你播放器端到端；Green：修复；Refactor：删除平台调用泄漏。
  * **测试预期**:
    * 正常路径: 播放页在手机/平板/横屏保持视频优先和控件一致。
    * 异常路径: 权限、系统限制、媒体损坏和后台回收均有恢复路径。
  * **DoD**: 真机矩阵、媒体样本、Lint 和性能基线通过；更新播放手势/系统集成文档；仅此节点提交推送。

**【并行开发分配建议】**

7.1 冻结后，7.2、7.3、7.4、7.5、7.6、7.7 可并行。分别使用 Fake preference、Fake `AudioFocusGateway`、Stub `PictureInPictureGateway`、Fake `ScreenshotGateway`；只在 7.8 接入真实系统。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 更新播放状态图、手势表、系统集成矩阵、截图输出约定和真机验证记录。
* **Commit 建议**: `feat(player): add advanced controls and system integrations`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(player): add advanced controls and system integrations" && git push origin main`

---
## Phase 8: 设置、备份、性能、无障碍与 GitHub 首版发布（M6）
**【阶段目标】**

完成浅色/深色/跟随系统主题、黑白灰骨架与明亮克制功能色、DataStore 设置、备份恢复、诊断、无障碍、性能门禁和仅 GitHub 分发的首版发布闭环。

**【接口与契约定义】**

- `UserPreferences`：主题、Material You 中性表面开关、默认布局/缩略图尺寸/排序、是否固定处理中心为第四入口、回收站天数、迷你播放器、播放行为、默认导出目录等。
- `BackupGateway`：导出/导入标签、集合、历史、偏好和关系；首版不承诺跨格式兼容。
- `DiagnosticsReporter`：脱敏日志、崩溃上下文、导出前确认；不得包含原始路径或视频内容。
- `UpdateSource`：只检查 GitHub Releases，不接入网络媒体源。

**【任务节点树】**

* **任务 8.1: 定义设置 schema 与主题映射**
  * **依赖**: 任务 7.8、2.8
  * **TDD 循环**: Red：覆盖未知枚举、版本升级、动态颜色污染和系统主题变化；Green：实现 schema 与 token 映射；Refactor：将默认值集中管理。
  * **测试预期**:
    * 正常路径: 浅色/深色/跟随系统切换；浅色四类功能色统一采用 B，深色统一采用 A；播放页纯黑 Scrim 独立映射；Material You 默认关闭，用户开启后只改变低饱和中性表面。
    * 异常路径: 损坏偏好、系统主题回调重复、功能色档位混用、结构选中被壁纸染色、低对比度配对自动回退安全 Token。
  * **DoD**: schema round-trip、浅/深完整配对矩阵和 Compose 主题测试通过；动态中性灰、结构选中、功能色三类边界均有回归断言。
* **任务 8.2: 实现 DataStore 设置仓储** `[并行]`
  * **依赖**: 8.1
  * **TDD 循环**: Red：覆盖并发写、进程终止、默认值和迁移；Green：实现 Preferences DataStore 单一写入流；Refactor：隔离序列化和 UI 映射。
  * **测试预期**:
    * 正常路径: 主题、布局、缩略图尺寸和是否固定处理中心等设置立即反映 UI，重启后保留；无效值回退默认。
    * 异常路径: 文件损坏、写入失败、取消 collector 不导致死锁或丢失其他键；关闭处理中心第四入口后当前页面安全返回原来源或首页。
  * **DoD**: 测试 dispatcher 下全绿；无 SharedPreferences 混用；写入 API 不暴露 MutableStateFlow。
* **任务 8.3: 实现备份/恢复与预览确认** `[并行]`
  * **依赖**: 8.2、6.7
  * **TDD 循环**: Red：覆盖空备份、版本字段、重复 ID、部分导入和取消；Green：实现明确 schema、预览、事务导入；Refactor：分离解析、校验、应用。
  * **测试预期**:
    * 正常路径: 用户选择需要备份/恢复的内容，导入前显示数量和冲突策略。
    * 异常路径: 文件截断、未知版本、校验失败和中途终止保持原数据不变。
  * **DoD**: golden file、事务回滚和大备份流式测试通过；文档标明开发阶段不保证旧格式兼容。
* **任务 8.4: 实现诊断、隐私和 GitHub 更新检查** `[并行]`
  * **依赖**: 8.2
  * **TDD 循环**: Red：覆盖路径脱敏、无网络、限频和恶意响应；Green：实现本地诊断导出与 Releases 检查；Refactor：网络客户端限域 GitHub API。
  * **测试预期**:
    * 正常路径: 用户主动导出脱敏报告；可查看当前版本和 GitHub Release。
    * 异常路径: 网络失败、超时、非 JSON/超大响应不影响核心功能，不建议网络媒体源。
  * **DoD**: 脱敏快照测试、MockWebServer 测试、隐私审查通过；不上传遥测。
* **任务 8.5: 建立性能、基线配置与无障碍门禁** `[并行]`
  * **依赖**: 5.8、6.7、7.8
  * **TDD 循环**: Red：先记录冷启动、首帧、10k 库滚动和扫描指标；Green：加入 Macrobenchmark/Baseline Profile；Refactor：删除测量噪声和重复 fixture。
  * **测试预期**:
    * 正常路径: 缓存优先首屏、秒级扫描、可见优先缩略图和播放首帧达到项目基线。
    * 异常路径: 低内存、IO 慢、数据库锁和字体放大不崩溃、不无限重试。
  * **DoD**: 基准结果入库；Compose semantics/TalkBack、4.5:1/3:1 配对矩阵、灰度/绿色弱状态区分检查通过；Lint 无警告。
* **任务 8.6: 实现 GitHub Release 打包与安装验证** `[并行]`
  * **依赖**: 8.5
  * **TDD 循环**: Red：覆盖签名、版本号、升级/卸载重装和安装包损坏；Green：实现 release variant、校验和和发布说明模板；Refactor：固定可复现构建输入。
  * **测试预期**:
    * 正常路径: Android 12+ 安装、升级后数据保留，GitHub Release 仅分发 APK/校验和/变更说明。
    * 异常路径: 签名不匹配、空间不足、升级中断和权限变化提供明确回退。
  * **DoD**: Release APK 可安装；SHA-256 可验证；无未声明网络源和敏感权限。
* **任务 8.7: Phase 8 交付与清理**
  * **依赖**: 8.1-8.6
  * **TDD 循环**: Red：从新装、升级、备份恢复、深浅色和低内存全流程回归；Green：修复门禁；Refactor：清除 debug 开关、假更新源和测试数据。
  * **测试预期**:
    * 正常路径: 首版全部 M0–M6 场景可用，设置和数据迁移可解释。
    * 异常路径: 迁移失败可恢复；诊断、备份和更新失败不阻塞播放/浏览。
  * **DoD**: 全测试、Lint、Macrobenchmark、真机安装升级通过；更新 README/CHANGELOG/隐私说明/发布文档；仅此节点提交推送。

**【并行开发分配建议】**

8.1 冻结后，8.2、8.3、8.4、8.5、8.6 可并行。设置 UI 使用 Fake `PreferencesRepository`；备份使用内存数据库和 golden files；更新检查使用 MockWebServer；性能分支不修改业务逻辑。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 更新设置 schema、备份格式、主题/对比度矩阵、性能门禁、发布和隐私文档。
* **Commit 建议**: `release(m6): publish YingLi first GitHub distribution`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleRelease && git add . && git commit -m "release(m6): publish YingLi first GitHub distribution" && git push origin main`

---
## Phase 9: 处理任务基础设施与处理中心（M7）
**【阶段目标】**

在不绑定具体编解码引擎的前提下，建立持久化处理项目、任务状态机、执行器、进度、取消、失败诊断和处理中心 UI，为切片、压缩、转换和去重提供统一基础设施。

**【接口与契约定义】**

- `ProcessingProject`：项目 ID、类型、输入媒体、输出策略、创建时间；同一项目可包含多个任务。
- `ProcessingTask`：Queued/Preparing/Running/Paused/Canceling/Succeeded/Failed/Canceled；状态转换必须可恢复且幂等。
- `ProcessingExecutor`：`execute/cancel/recover`；业务层不认识 FFmpeg 或具体 codec。
- `ProcessingProgress`：阶段、已处理量、总量可未知、速度和预计时间可空；不得伪造百分比。
- `ProcessingArtifactStore`：临时文件、最终输出、诊断文件的所有权和清理策略。
- `ProcessingPresentation`：Running=钢蓝、Queued/Waiting=琥珀、Succeeded=绿色、Failed=红色；每种状态必须同时提供图标、文字、可用动作和进度语义。

**【任务节点树】**

* **任务 9.1: 定义处理项目、任务和状态机**
  * **依赖**: 任务 8.7
  * **TDD 循环**: Red：覆盖非法跳转、重复开始、取消竞态和进程死亡；Green：实现纯 Kotlin reducer；Refactor：用命令/事件分离外部副作用。
  * **测试预期**:
    * 正常路径: 排队、准备、运行、成功和恢复流程确定；总量未知时只展示不确定态。
    * 异常路径: 成功后取消、失败后进度、重复恢复、非法阶段事件不破坏终态。
  * **DoD**: 状态转移表 100% 覆盖；事件重放结果一致；无 Android/codec 类型。
* **任务 9.2: 实现处理任务持久化与恢复** `[并行]`
  * **依赖**: 9.1、3.2
  * **TDD 循环**: Red：覆盖事务中断、重复事件和数据库迁移；Green：实现 Room entity/event checkpoint；Refactor：集中状态映射。
  * **测试预期**:
    * 正常路径: 重启后排队/运行任务恢复为可继续或可重试状态，历史结果可查询。
    * 异常路径: checkpoint 损坏、输入缺失、已完成输出不存在时标记一致性错误。
  * **DoD**: Room migration、崩溃恢复和幂等测试通过；不把每个进度 tick 写库。
* **任务 9.3: 实现执行器调度与资源仲裁** `[并行]`
  * **依赖**: 9.1
  * **TDD 循环**: Red：覆盖并发上限、优先级、取消和低电量/空间不足；Green：实现应用内受限调度器；Refactor：策略与执行线程分离。
  * **测试预期**:
    * 正常路径: 按用户顺序执行，前台处理可见，CPU/IO 并发受控。
    * 异常路径: 进程被杀、执行器抛异常、空间预检失败和取消超时转为可诊断终态。
  * **DoD**: 虚拟调度器测试通过；无 GlobalScope；首版未无必要引入 WorkManager。
* **任务 9.4: 实现临时产物、原子导出与清理** `[并行]`
  * **依赖**: 9.1、5.5
  * **TDD 循环**: Red：覆盖同名、半文件、跨卷和取消；Green：实现临时目录、fsync/close、最终重命名；Refactor：统一 Artifact 生命周期。
  * **测试预期**:
    * 正常路径: 成功后只暴露完整文件并触发媒体索引，默认输出到 `YingLi-Output`。
    * 异常路径: 磁盘满、写权限丢失、取消和崩溃清理半成品；失败诊断短期保留。
  * **DoD**: 文件故障注入测试通过；原文件永不覆盖；清理幂等且范围受控。
* **任务 9.5: 实现处理中心列表、详情和控制** `[并行]`
  * **依赖**: 9.2、9.3
  * **TDD 循环**: Red：覆盖排队、运行/暂停、未知进度、完成、失败、取消确认、恢复和入口配置；Green：实现顶部入口、可选第四入口及独立页面；Refactor：列表项只消费领域状态和 Presentation 映射。
  * **测试预期**:
    * 正常路径: 运行项展示钢蓝进度/百分比/预计剩余和暂停，等待项展示琥珀状态和取消，完成项展示绿色状态和打开文件，失败项展示红色原因和重试；可查看详情、清理历史。
    * 异常路径: 总量未知时显示不确定态而非伪百分比；Executor 断连、进度倒退、任务消失和输出被外部删除有一致提示；关闭第四入口不影响顶部入口。
  * **DoD**: Compose 状态矩阵、无障碍和导航测试通过；颜色、图标、文字、动作四种线索一致；所有功能色来自 Functional Token。
* **任务 9.6: 实现处理诊断与隐私日志** `[并行]`
  * **依赖**: 9.1、8.4
  * **TDD 循环**: Red：覆盖敏感路径、参数和大日志；Green：实现结构化事件和脱敏导出；Refactor：共享 DiagnosticsReporter。
  * **测试预期**:
    * 正常路径: 失败任务可导出版本、能力、阶段和错误码。
    * 异常路径: 日志超限滚动清理；不得包含视频帧、原始文件名或密钥材料。
  * **DoD**: 脱敏快照和保留期测试通过；用户主动操作前不外发数据。
* **任务 9.7: Phase 9 交付与清理**
  * **依赖**: 9.1-9.6
  * **TDD 循环**: Red：用 Fake 长任务进行成功、失败、取消、杀进程、重启端到端；Green：修复；Refactor：删除具体处理类型假设。
  * **测试预期**:
    * 正常路径: 多任务可排队、观察、取消和恢复，产物原子提交。
    * 异常路径: 任意生命周期中断不损坏源文件、不留下失管半成品。
  * **DoD**: JVM/Room/Compose/故障注入/Lint 全绿；状态机和清理文档更新；仅此节点提交推送。

**【并行开发分配建议】**

9.1 完成后，9.2、9.3、9.4、9.5、9.6 可并行。所有分支使用脚本化 `FakeProcessingExecutor`；文件分支使用临时目录 `ArtifactStore`，UI 分支禁止依赖真实编码器。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 新增任务状态图、执行器契约、产物所有权、取消/恢复和诊断保留策略。
* **Commit 建议**: `feat(processing): add persistent task infrastructure and processing center`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(processing): add persistent task infrastructure and processing center" && git push origin main`

---
## Phase 10: 切片项目与导出（M8）
**【阶段目标】**

交付长视频精彩片段选择、多片段项目、列表编辑、快速/精确切片和统一导出。允许片段重叠，支持勾选删除和批量导出，预设固定且结果可验证。

**【接口与契约定义】**

- `ClipSegment`：稳定 ID、start、end、名称、选择态；约束 `0 <= start < end <= duration`，片段间允许重叠。
- `ClipProject`：单一源媒体、多片段有序列表、导出模式和固定预设。
- `ClipEngine`：`probe/fastCut/accurateCut`；快速切片以关键帧边界为准，精确切片重新编码。
- `ClipExportPlan`：所选片段、命名规则、输出位置和冲突策略。

**【任务节点树】**

* **任务 10.1: 定义片段模型、编辑命令和不变量**
  * **依赖**: 任务 9.7
  * **TDD 循环**: Red：覆盖零长度、负值、超时长、重叠、排序和撤销；Green：实现值对象与 reducer；Refactor：统一时间精度和钳制。
  * **测试预期**:
    * 正常路径: 添加、修改、复制、排序、勾选和删除多个片段，重叠合法。
    * 异常路径: 未知时长、start=end、浮点/毫秒溢出、删除最后一项可预测。
  * **DoD**: 边界表、属性测试和命令重放通过；模型不依赖播放器时间轴控件。
* **任务 10.2: 实现 ClipProject 持久化** `[并行]`
  * **依赖**: 10.1、9.2
  * **TDD 循环**: Red：覆盖草稿自动保存、并发编辑、源媒体重定位和迁移；Green：实现事务仓储；Refactor：复用媒体实体引用。
  * **测试预期**:
    * 正常路径: 离开后恢复片段、顺序、选中态和预设，源文件移动后仍关联。
    * 异常路径: 源缺失、草稿损坏、版本不匹配时只读恢复或明确失败。
  * **DoD**: Room/恢复/迁移测试通过；不复制源视频；写入节流。
* **任务 10.3: 实现时间轴取帧与片段编辑器** `[并行]`
  * **依赖**: 10.1、7.8
  * **TDD 循环**: Red：覆盖长视频、快速拖动、缩放、取帧失败和旋转；Green：实现可见窗口取帧与双手柄；Refactor：调度复用缩略图优先队列。
  * **测试预期**:
    * 正常路径: 播放头、入点/出点、预览和片段列表同步，视频画面优先。
    * 异常路径: 时长未知、VFR、无关键帧索引、离屏帧请求取消且不堆积。
  * **DoD**: reducer/Compose/长视频性能测试通过；手柄可通过键盘/TalkBack 调整。
* **任务 10.4: 实现快速切片引擎适配器** `[并行]`
  * **依赖**: 10.1、9.3、9.4
  * **TDD 循环**: Red：覆盖非关键帧入点、时间基错误、音画偏移和不支持容器；Green：对接平台可用 muxer/extractor；Refactor：引擎能力通过 `probe` 暴露。
  * **测试预期**:
    * 正常路径: 可兼容输入按最近安全关键帧快速无损切片，输出可被 Media3 播放。
    * 异常路径: 能力不足时明确建议精确模式；不得生成表面成功的损坏文件。
  * **DoD**: 样本矩阵、时间容差和可播放性测试通过；无 FFmpeg 预依赖。
* **任务 10.5: 实现精确切片引擎适配器** `[并行]`
  * **依赖**: 10.1、9.3、9.4
  * **TDD 循环**: Red：覆盖非关键帧、VFR、方向 metadata、音频缺失和编码失败；Green：使用平台 MediaCodec/Transformer 能力最小实现；Refactor：与快速模式共享导出契约。
  * **测试预期**:
    * 正常路径: 目标时间误差满足基线，方向与音画同步正确。
    * 异常路径: 编码器不可用、HDR 不支持、热限制和取消返回可诊断错误并清理输出。
  * **DoD**: 指定设备样本和时间误差测试通过；不静默降级质量。
* **任务 10.6: 实现片段列表与批量导出 UI** `[并行]`
  * **依赖**: 10.2、10.3、9.5
  * **TDD 循环**: Red：覆盖多选、全选、删除确认、同名和部分失败；Green：实现列表动作与 ExportPlan；Refactor：批量结果统一展示。
  * **测试预期**:
    * 正常路径: 勾选片段、统一导出、查看结果和回到项目继续编辑。
    * 异常路径: 无选中项、输出空间不足、部分片段失败时保留成功项和可重试项。
  * **DoD**: UI 状态、导航、批量结果和无障碍测试通过；默认导出选项可配置。
* **任务 10.7: Phase 10 交付与清理**
  * **依赖**: 10.1-10.6
  * **TDD 循环**: Red：短/长、VFR、非关键帧、多片段重叠、取消端到端；Green：修复；Refactor：清除实验引擎分支和重复命名逻辑。
  * **测试预期**:
    * 正常路径: 建项目、选片段、保存、快速/精确导出、回放验证完整。
    * 异常路径: 进程终止、空间不足、编解码失败不损坏源或其他片段。
  * **DoD**: 样本矩阵、输出探测、处理中心、Lint 全绿；更新格式/误差/限制文档；仅此节点提交推送。

**【并行开发分配建议】**

10.1 冻结后，10.2、10.3、10.4、10.5、10.6 可并行。编辑器用 Stub Frame Provider；两个引擎共享 `ClipEngineContractTest` 但不得互相依赖；UI 使用 Fake Exporter 注入脚本化进度和失败。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 新增片段模型、时间精度、快速/精确模式差异、格式能力和输出验证矩阵。
* **Commit 建议**: `feat(clips): add multi-segment editing and export`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(clips): add multi-segment editing and export" && git push origin main`

---
## Phase 11: 压缩、转换与输出兼容性（M9）
**【阶段目标】**

基于设备实际 codec 能力交付视频压缩和格式转换，提供固定预设、输出空间预检、HDR/字幕/多音轨处理策略和结果验证；不宣称设备无法证明的格式支持。

**【接口与契约定义】**

- `MediaCapabilityProbe`：输入轨道、容器、codec、HDR、分辨率、帧率和设备编码能力。
- `TranscodePreset`：固定 ID、目标容器/codec、分辨率/码率策略、音频策略；用户不直接输入危险参数。
- `TranscodePlan`：输入、预设、轨道保留、预计空间、输出和降级说明。
- `TranscodeEngine`、`OutputVerifier`：执行与独立探测分离；成功必须通过结构和 Media3 回放验证。

**【任务节点树】**

* **任务 11.1: 定义能力模型、预设和规划规则**
  * **依赖**: 任务 10.7
  * **TDD 循环**: Red：覆盖不支持 codec、HDR、奇数分辨率、无音频和超大码率；Green：实现能力交集与固定预设；Refactor：决策规则表驱动。
  * **测试预期**:
    * 正常路径: 根据源属性和设备能力生成兼容、均衡、节省空间等计划并解释变化。
    * 异常路径: 无合法计划时拒绝开始；不得静默丢字幕/多音轨/HDR。
  * **DoD**: 规则/属性测试通过；每个预设有版本和可解释输出；无具体引擎类型。
* **任务 11.2: 实现设备能力探测器** `[并行]`
  * **依赖**: 11.1
  * **TDD 循环**: Red：覆盖 codec 列表为空、误报、配置异常和缓存失效；Green：实现 MediaCodecList/MediaExtractor 探测；Refactor：设备结果缓存与源探测分离。
  * **测试预期**:
    * 正常路径: 当前测试手机得到稳定 codec/尺寸/色彩能力快照。
    * 异常路径: 厂商 codec 崩溃或谎报时能力降级并记录，不导致应用崩溃。
  * **DoD**: Fake codec、真机快照和异常注入测试通过；探测不读取完整文件。
* **任务 11.3: 实现压缩/转换执行器** `[并行]`
  * **依赖**: 11.1、11.2、9.3、9.4
  * **TDD 循环**: Red：覆盖 codec 配置失败、帧丢失、音画不同步、取消和热限制；Green：接入 AndroidX Media3 Transformer/平台 codec；Refactor：封装引擎事件。
  * **测试预期**:
    * 正常路径: 支持的输入按计划输出，进度可观测，原方向/时长和同步在容差内。
    * 异常路径: 不支持轨道、编码器死亡、空间耗尽和后台中断安全失败并保留诊断。
  * **DoD**: Contract Test、真机格式矩阵和长任务恢复通过；无未审批 FFmpeg 依赖。
* **任务 11.4: 实现字幕、多音轨和 HDR 保留策略** `[并行]`
  * **依赖**: 11.1、11.3
  * **TDD 循环**: Red：覆盖内封/外挂字幕、多个音轨、HDR 到 SDR 和 metadata 丢失；Green：按能力保留或要求用户确认降级；Refactor：轨道策略独立于 UI。
  * **测试预期**:
    * 正常路径: 支持时保留用户选择轨道和 HDR；不支持时在执行前列出将被改变内容。
    * 异常路径: 禁止无提示烧录字幕、丢多音轨或错误标记色彩空间。
  * **DoD**: 多轨/HDR 样本探测与回放验证通过；降级需要显式确认。
* **任务 11.5: 实现空间预估、输出命名与冲突策略** `[并行]`
  * **依赖**: 11.1、9.4
  * **TDD 循环**: Red：覆盖未知码率、剩余空间临界值、同名和超长名称；Green：实现保守估算与预留阈值；Refactor：共享输出命名服务。
  * **测试预期**:
    * 正常路径: 开始前显示估算体积、空间和输出路径，默认目录/选项可配置。
    * 异常路径: 空间不足不启动；执行中外部占满空间安全停止；名称非法被规范化。
  * **DoD**: 边界和故障注入测试通过；估算误差范围记录；不覆盖已有文件。
* **任务 11.6: 实现处理配置和结果对比 UI** `[并行]`
  * **依赖**: 11.1、11.5、9.5
  * **TDD 循环**: Red：覆盖无可用预设、降级确认、取消和结果缺失；Green：实现预设选择、变化摘要与前后元数据对比；Refactor：UI 只提交 TranscodePlan。
  * **测试预期**:
    * 正常路径: 用户按时长、分辨率、画幅、体积比较输入输出并打开结果。
    * 异常路径: 能力变化、输出验证失败、任务部分完成不展示伪成功。
  * **DoD**: Compose 状态矩阵、无障碍和错误恢复测试通过；高级参数不暴露在首版。
* **任务 11.7: 实现独立输出验证器** `[并行]`
  * **依赖**: 11.3、11.4
  * **TDD 循环**: Red：准备容器存在但索引损坏、0 帧、音画漂移样本；Green：实现 metadata/轨道/抽样解码验证；Refactor：验证结果与引擎日志分离。
  * **测试预期**:
    * 正常路径: 验证时长、轨道、尺寸、可解码性和计划符合度。
    * 异常路径: 验证失败不发布到媒体库，保留诊断并允许重试。
  * **DoD**: 损坏样本测试通过；成功状态只能由 verifier 产生。
* **任务 11.8: Phase 11 交付与清理**
  * **依赖**: 11.1-11.7
  * **TDD 循环**: Red：SDR/HDR、多轨、VFR、空间不足、取消和 codec 失败端到端；Green：修复；Refactor：删除设备硬编码和实验参数。
  * **测试预期**:
    * 正常路径: 选择预设、确认变化、执行、验证、对比和加入媒体库完整。
    * 异常路径: 能力不足或输出异常时无数据损坏、无伪成功。
  * **DoD**: 真机格式/能力/性能矩阵、Lint 全绿；文档明确支持与限制；仅此节点提交推送。

**【并行开发分配建议】**

11.1 冻结后，11.2、11.3、11.4、11.5、11.6、11.7 可并行。规划/UI 使用固定 Capability Snapshot；引擎使用 Fake Artifact Store；Verifier 使用预制损坏样本，不能信任执行器返回值。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 新增预设版本表、能力探测快照、格式/HDR/轨道矩阵、空间策略和输出验证规范。
* **Commit 建议**: `feat(transcode): add capability-aware compression and conversion`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(transcode): add capability-aware compression and conversion" && git push origin main`

---
## Phase 12: 完全重复与相似视频去重（M10）
**【阶段目标】**

将“完全重复”和“相似视频”实现为两个显式模式。扫描只生成候选组和证据，不自动删除；用户逐组复核后，删除项统一进入回收站。

**【接口与契约定义】**

- `DuplicateMode`：Exact/Similar；两种模式的证据、阈值和文案严格分离。
- `MediaFingerprint`：size/quickHash/fullHash/媒体属性/感知特征及算法版本；哈希在后台且可取消。
- `DuplicateGroup`、`SimilarityScore`、`DuplicateEvidence`：保存候选、相似度分项和生成时间。
- `DuplicateScanner`：增量扫描；`DuplicateDecisionRepository`：保留/删除/忽略决定；`DuplicateDeletionPlan` 必须经过人工确认。

**【任务节点树】**

* **任务 12.1: 定义去重模式、证据和决策契约**
  * **依赖**: 任务 11.8
  * **TDD 循环**: Red：覆盖哈希碰撞、阈值边界、算法升级和人工覆盖；Green：实现不可变证据模型；Refactor：分离候选生成与删除决策。
  * **测试预期**:
    * 正常路径: Exact 要求完整内容证据；Similar 展示分数和属性差异，用户明确选择保留项。
    * 异常路径: 证据缺失/过期、算法版本不同、单项组不得进入删除计划。
  * **DoD**: 决策表和序列化测试全绿；契约禁止 `autoDelete`；模式不可隐式切换。
* **任务 12.2: 实现完全重复分层哈希扫描** `[并行]`
  * **依赖**: 12.1、3.7
  * **TDD 循环**: Red：覆盖同尺寸非重复、quick hash 碰撞、大文件取消和文件变化；Green：size -> quick -> full hash 分层；Refactor：流式读取和缓冲复用。
  * **测试预期**:
    * 正常路径: 字节完全一致文件归为一组，已缓存且版本匹配的 fingerprint 不重算。
    * 异常路径: 扫描中被修改、权限撤销、IO 错误不产生 Exact 结论；不把整文件载入内存。
  * **DoD**: 碰撞样本、GB 级流式测试和取消测试通过；CPU/IO 并发受限。
* **任务 12.3: 建立相似视频特征 Spike 与阈值基线** `[并行]`
  * **依赖**: 12.1
  * **TDD 循环**: Red：先用标注数据证明只比较时长/名称误报；Green：实现最小多帧感知特征实验；Refactor：冻结版本化 feature extractor 接口。
  * **测试预期**:
    * 正常路径: 同源不同压缩/裁剪样本分数高于无关视频，分项证据可解释。
    * 异常路径: 黑屏、短视频、旋转、不同帧率和取帧失败不得给出虚假高置信度。
  * **DoD**: 标注集、precision/recall 和阈值记录入库；未达基线则 Similar 保持实验开关关闭。
* **任务 12.4: 实现相似候选生成与持久化** `[并行]`
  * **依赖**: 12.3、9.2
  * **TDD 循环**: Red：覆盖 O(n²) 爆炸、增量新增、特征版本失效和取消；Green：先按时长/尺寸分桶再算相似度；Refactor：索引和评分器独立。
  * **测试预期**:
    * 正常路径: 只比较合理候选，新增媒体只更新相关桶，候选组保存分数和证据。
    * 异常路径: 特征缺失、版本升级和扫描中断可恢复，不阻塞媒体库。
  * **DoD**: 10k 数据复杂度/内存基线、增量和恢复测试通过；算法版本可追溯。
* **任务 12.5: 实现去重复核与保留建议 UI** `[并行]`
  * **依赖**: 12.1、12.2、12.4
  * **TDD 循环**: Red：覆盖模式混淆、全选、保留项缺失和返回恢复；Green：实现组内并排属性/预览/证据；Refactor：建议器不直接生成删除命令。
  * **测试预期**:
    * 正常路径: 用户按分辨率、时长、画幅、体积、路径和质量复核，显式选择保留/删除。
    * 异常路径: 默认不勾选删除；文件变化后要求重新验证；Similar 清楚标记“相似而非重复”。
  * **DoD**: Compose/无障碍/防误删测试通过；颜色不是唯一模式线索；无自动批量删除入口。
* **任务 12.6: 实现删除计划验证与回收站联动** `[并行]`
  * **依赖**: 12.5、5.6
  * **TDD 循环**: Red：覆盖计划过期、全部删除、权限撤销和部分失败；Green：执行前重验文件证据并调用 TrashRepository；Refactor：复用批量操作结果模型。
  * **测试预期**:
    * 正常路径: 每组至少保留一项，确认后删除项进入回收站并可恢复。
    * 异常路径: 文件内容变化、恢复冲突、某项失败时不继续误删，结果逐项可追踪。
  * **DoD**: 故障注入、计划重验和回收站端到端通过；永不直接永久删除。
* **任务 12.7: Phase 12 交付与清理**
  * **依赖**: 12.1-12.6
  * **TDD 循环**: Red：碰撞、相似误报、10k 库、取消、权限和恢复端到端；Green：修复；Refactor：删除实验阈值硬编码和未用 fingerprint。
  * **测试预期**:
    * 正常路径: 两种模式独立扫描、复核、回收站删除和恢复闭环明确。
    * 异常路径: 低置信度、过期证据、处理中断绝不造成自动删除。
  * **DoD**: 算法/性能/真机/UI/安全门禁全绿；更新算法卡和误报限制；仅此节点提交推送。

**【并行开发分配建议】**

12.1 完成后，12.2 与 12.3 可并行；12.4 使用固定特征库；12.5 使用脚本化候选组；12.6 使用 Fake `TrashRepository`。相似算法分支不得修改安全删除规则。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 新增 Exact/Similar 算法卡、标注集、阈值、性能、人工复核和安全删除规范。
* **Commit 建议**: `feat(dedup): add reviewed exact and similar duplicate workflows`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(dedup): add reviewed exact and similar duplicate workflows" && git push origin main`

---
## Phase 13: 应用锁与真实加密保险库（M11）
**【阶段目标】**

先交付独立应用锁，再交付经过威胁建模的真实加密保险库。保险库媒体使用 Android Keystore 保护的密钥和认证加密，播放采用受控流式解密；通知、截图、PiP、最近任务和诊断不得泄露内容。

**【接口与契约定义】**

- `AppLockPolicy`：关闭/密码/图案/生物识别、超时、后台立即锁；密保问题仅作为用户可选恢复流程，答案不得明文存储。
- `VaultItem`、`VaultManifest`：不暴露原路径；内容、metadata 和缩略图均纳入保护边界。
- `KeyManagementGateway`：create/wrap/unwrap/rotate/invalidate；密钥材料不得离开 Keystore/受控内存。
- `VaultCipher`：版本化分块 AEAD（实现前 ADR 审核 AES-GCM/等效方案、nonce 和分块格式）。
- `SecurePlaybackSource`：向 Media3 提供受控随机读/流式解密，不生成长期明文临时文件。

**【任务节点树】**

* **任务 13.1: 完成威胁模型、ADR 与安全测试计划**
  * **依赖**: 任务 12.7
  * **TDD 循环**: Red：列出丢机、root/备份、内存、通知、截图、临时文件和密钥失效攻击；Green：定义边界和验收；Refactor：删除无法验证的安全承诺。
  * **测试预期**:
    * 正常路径: 明确应用锁与保险库的不同目标、受保护资产和信任边界。
    * 异常路径: 不以混淆、隐藏目录或仅数据库标记冒充加密；无法恢复场景需明确说明。
  * **DoD**: ADR 经审阅；密钥/nonce/备份/恢复/删除策略冻结；测试向量和攻击清单可执行。
* **任务 13.2: 实现应用锁凭据与状态机** `[并行]`
  * **依赖**: 13.1、8.2
  * **TDD 循环**: Red：覆盖密码/图案失败、后台超时、时钟回拨和进程重建；Green：实现 lock reducer 与安全凭据验证；Refactor：生物识别适配器隔离。
  * **测试预期**:
    * 正常路径: 用户可选密码、图案、人脸/指纹能力；按配置锁定和解锁；密码界面使用锁图标、输入位数语义、数字键盘、生物识别与退格操作，全部使用无彩主色和结构性选中。
    * 异常路径: 暴力尝试限速；生物识别不可用回退；密保答案不明文、不出现在日志；错误态只能以红色配合图标和文字提示，不得改变普通数字键颜色。
  * **DoD**: 虚拟时钟、BiometricPrompt Fake、进程恢复、键盘焦点和无障碍测试通过；敏感输入不被自动填充/截屏；锁页不显示一级导航、迷你播放器或媒体标题。
* **任务 13.3: 实现 Keystore 密钥生命周期** `[并行]`
  * **依赖**: 13.1
  * **TDD 循环**: Red：覆盖密钥失效、认证变化、设备锁移除和轮换中断；Green：实现版本化 key envelope；Refactor：错误类型区分可恢复/不可恢复。
  * **测试预期**:
    * 正常路径: 创建、解锁、使用和轮换不导出主密钥。
    * 异常路径: KeyPermanentlyInvalidated、设备不支持、认证取消时不损坏密文且不静默重建密钥。
  * **DoD**: Android Keystore 仪器测试和失效演练通过；无硬编码密钥/IV；日志脱敏。
* **任务 13.4: 实现版本化分块加密容器** `[并行]`
  * **依赖**: 13.1、13.3
  * **TDD 循环**: Red：使用 NIST/库测试向量并覆盖 nonce 复用、篡改、截断和随机读；Green：实现最小分块 AEAD 格式；Refactor：流式缓冲受限且可清零。
  * **测试预期**:
    * 正常路径: 大视频流式加/解密，随机块认证，manifest 版本可识别。
    * 异常路径: 任一字节篡改、块重排、错误密钥、截断必须认证失败且不输出未验证明文。
  * **DoD**: 测试向量、属性/fuzz、大文件/低内存测试通过；格式和 nonce 规则文档化。
* **任务 13.5: 实现安全导入、导出与删除** `[并行]`
  * **依赖**: 13.3、13.4、9.4
  * **TDD 循环**: Red：覆盖导入中断、源删除失败、同名、空间不足和取消；Green：先完成密文并验证，再按用户确认处理源；Refactor：复用原子产物协议。
  * **测试预期**:
    * 正常路径: 导入保险库、验证、可选移除原文件；导出到用户指定位置并重建索引。
    * 异常路径: 验证前不得删除源；导出中断清理明文半成品；“删除”不承诺闪存物理擦除。
  * **DoD**: 故障注入和空间边界通过；每次破坏性动作二次确认；无失管临时文件。
* **任务 13.6: 实现流式安全播放与会话隔离** `[并行]`
  * **依赖**: 13.4、4.2
  * **TDD 循环**: Red：覆盖随机 seek、锁屏、Service 重建、错误密钥和缓存泄露；Green：实现 SecurePlaybackSource；Refactor：解密窗口和生命周期统一管理。
  * **测试预期**:
    * 正常路径: 不落地完整明文即可播放、seek、暂停，锁定后会话立即失效。
    * 异常路径: 认证失败停止播放；锁定后通知、PiP、迷你播放器和音频会话清除敏感内容。
  * **DoD**: Media3 Contract、内存/缓存审计和真机长视频通过；无长期明文文件。
* **任务 13.7: 实现保险库 UI 与防泄露窗口策略** `[并行]`
  * **依赖**: 13.2、13.5、13.6
  * **TDD 循环**: Red：覆盖最近任务预览、截图、通知、分享和返回栈；Green：实现独立入口与 `FLAG_SECURE` 等策略；Refactor：安全上下文集中管理。
  * **测试预期**:
    * 正常路径: 解锁后浏览/播放/导入导出，离开按策略锁定；普通菜单、浮层和确认 Dialog 使用中性表面/边框/海拔建立层级，仅危险命令使用红色。
    * 异常路径: 禁止截图、PiP、敏感通知和普通媒体库缩略图泄露；深链不能绕过锁。
  * **DoD**: UI 自动化、返回栈、窗口/通知审计和 TalkBack 隐私测试通过。
* **任务 13.8: Phase 13 交付与清理**
  * **依赖**: 13.1-13.7
  * **TDD 循环**: Red：导入、篡改、密钥失效、锁屏、杀进程、导出和源删除端到端；Green：修复；Refactor：移除调试密钥、明文 fixture 和旁路入口。
  * **测试预期**:
    * 正常路径: 应用锁和保险库各自可独立启用，安全播放与恢复符合威胁模型。
    * 异常路径: 密钥/密文/流程失败采取 fail-closed，不泄露、不误删、不声称可恢复。
  * **DoD**: 安全审查、静态扫描、仪器/故障注入/真机测试全绿；更新安全白皮书和恢复限制；仅此节点提交推送。

**【并行开发分配建议】**

13.1 必须先完成；随后 13.2、13.3、13.4 可并行但共享冻结的密码学 ADR。13.5 使用临时文件系统 Fake，13.6 使用测试向量 DataSource，13.7 使用 Fake Lock/Vault Repository。密码学实现不得由 UI 分支复制或修改。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 新增威胁模型、密码学 ADR、容器格式、密钥生命周期、数据恢复限制和安全测试报告。
* **Commit 建议**: `feat(vault): add app lock and authenticated encrypted vault`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug && git add . && git commit -m "feat(vault): add app lock and authenticated encrypted vault" && git push origin main`

---
## Phase 14: 需求验证后的扩展与架构演进（M12）
**【阶段目标】**

用真实失败样本、用户需求和基准数据决定是否引入 GIF、片段级去重、第二播放内核、FFmpeg 或网络源。每项先 Spike 与 ADR，再 Go/No-Go；未达门槛不进入产品代码。

**【接口与契约定义】**

- `ExperimentProposal`：问题、证据、假设、成功指标、风险、许可证和退出策略。
- `CapabilityProvider`：现有平台实现与候选扩展共享 Contract Test，不允许 UI 根据实现类型分支。
- `GoNoGoDecision`：指标、兼容性、包体/性能/维护/安全成本和批准结论。
- 扩展列表互相独立；“进入 M12”不代表全部实现。

**【任务节点树】**

* **任务 14.1: 建立证据门禁与 Spike 模板**
  * **依赖**: 任务 13.8
  * **TDD 循环**: Red：用无数据的功能请求验证无法决策；Green：实现提案、基准、ADR 和回滚模板；Refactor：统一指标采集入口。
  * **测试预期**:
    * 正常路径: 每项扩展可记录真实失败率、用户频次、目标和退出标准。
    * 异常路径: 许可证不兼容、无代表样本、指标不可测时自动 No-Go。
  * **DoD**: 模板、审批人、数据保留和隐私规则落库；不引入生产依赖。
* **任务 14.2: 评估 GIF/动图导出** `[并行]`
  * **依赖**: 14.1、10.7
  * **TDD 循环**: Red：建立时长/尺寸/色彩/体积失败样本；Green：实现隔离 Spike；Refactor：仅保留基准与契约。
  * **测试预期**:
    * 正常路径: 代表片段的时间、质量、内存和输出体积达到预设门槛。
    * 异常路径: OOM、超大文件、透明度/色彩失真或依赖许可证失败则 No-Go。
  * **DoD**: ADR 给出 Go/No-Go；Go 后另建实现任务，不把 Spike 直接发布。
* **任务 14.3: 评估片段级相似去重** `[并行]`
  * **依赖**: 14.1、12.7
  * **TDD 循环**: Red：用包含局部复用和误报的标注集建立基线；Green：实现离线 Spike；Refactor：复用版本化特征接口。
  * **测试预期**:
    * 正常路径: 能定位共享时间段并提供可解释置信度。
    * 异常路径: 混剪、变速、裁剪和字幕覆盖导致误报时不得形成删除动作。
  * **DoD**: precision/recall、复杂度和人工复核成本达门槛才 Go。
* **任务 14.4: 评估第二播放内核** `[并行]`
  * **依赖**: 14.1、7.8
  * **TDD 循环**: Red：收集 Media3 无法播放的合法样本；Green：候选内核跑同一 Playback Contract；Refactor：记录适配边界和切换成本。
  * **测试预期**:
    * 正常路径: 候选显著提升真实样本成功率且会话/进度语义一致。
    * 异常路径: 包体、功耗、安全、许可证、维护或状态差异超预算则 No-Go。
  * **DoD**: 样本成功率和成本 ADR 完成；没有失败样本则不引入。
* **任务 14.5: 评估 FFmpeg 处理后端** `[并行]`
  * **依赖**: 14.1、11.8
  * **TDD 循环**: Red：整理平台 Transformer 无法完成的具体任务；Green：隔离 native Spike；Refactor：用现有 ProcessingExecutor Contract 比较。
  * **测试预期**:
    * 正常路径: 对目标格式/功能有可量化增益，ABI、启动、包体和功耗在预算内。
    * 异常路径: GPL/LGPL 配置、源代码义务、CVE 更新、native crash 无治理方案则 No-Go。
  * **DoD**: 法务/许可证、供应链、性能和安全 ADR 完成；不得直接替换现有引擎。
* **任务 14.6: 评估网络源边界** `[并行]`
  * **依赖**: 14.1
  * **TDD 循环**: Red：验证当前产品“纯本地”定位与真实需求；Green：仅定义安全/隐私/缓存 Spike；Refactor：与本地媒体领域隔离。
  * **测试预期**:
    * 正常路径: 若需求成立，明确协议、认证、离线、缓存和错误模型。
    * 异常路径: 无真实需求、DRM/凭据风险、范围膨胀或隐私不清时 No-Go；当前默认不考虑网络源。
  * **DoD**: 产品和安全 ADR 明确结论；No-Go 时生产代码零变化。
* **任务 14.7: 将获批实验产品化** `[并行]`
  * **依赖**: 14.2-14.6 中对应的 Go 决策
  * **TDD 循环**: Red：先把获批指标写成失败的 Contract/Acceptance Test；Green：最小产品实现；Refactor：删除实验开关和双路径重复。
  * **测试预期**:
    * 正常路径: 新 Provider 通过既有契约且达到 ADR 指标。
    * 异常路径: 回归、指标恶化、迁移失败可关闭或回滚，不损坏已有数据。
  * **DoD**: 每个获批能力独立 PR/测试/迁移/文档；未获批项无生产依赖和 UI 入口。
* **任务 14.8: Phase 14 交付与清理**
  * **依赖**: 14.1-14.7（No-Go 项以 ADR 结论视为完成）
  * **TDD 循环**: Red：对所有 Go 能力跑全量回归，对 No-Go 检查生产依赖为零；Green：修复；Refactor：移除 Spike 二进制、样本和临时开关。
  * **测试预期**:
    * 正常路径: 获批扩展不破坏本地播放、媒体库、处理和保险库边界。
    * 异常路径: 扩展关闭/失败时核心功能保持可用，数据仍可由原版本读取或迁移。
  * **DoD**: 全量测试、依赖/许可证/安全/性能审计通过；ADR 索引和路线图更新；仅此节点提交推送。

**【并行开发分配建议】**

14.1 冻结模板后，14.2-14.6 可完全并行，并使用独立 benchmark/sample fixture；14.7 只接收有签字的 Go 决策。Spike 代码位于隔离 source set/分支，不进入 release 依赖图。

**【本阶段 Git 交付与文档更新清单】**

* **文档更新**: 更新实验台账、各项 ADR、基准和样本来源、许可证/SBOM、Go/No-Go 结论及路线图。
* **Commit 建议**: `chore(architecture): record evidence-gated extension decisions`
* **推送指令**: `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleRelease && git add . && git commit -m "chore(architecture): record evidence-gated extension decisions" && git push origin main`
