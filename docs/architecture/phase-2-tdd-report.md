# Phase 2 TDD 记录

## 范围

本记录对应 `09-tdd-phased-development-checklist.md` 的任务 2.1 至 2.8。Phase 2 建立可安装的设计系统和自适应产品壳，不实现媒体扫描、真实详情数据或 Media3 播放链路。

## Red-Green-Refactor

| 任务 | Red | Green | Refactor / 证据 |
|---|---|---|---|
| 2.1 | 原型颜色散落且没有对比度、状态层和播放器配对门禁 | 三层 Token 覆盖 13 级中性灰、浅 B/深 A、状态层、Scrim 和纯黑播放器 | `YingLiTokenTest` 自动验证数量、透明度、4.5:1/3:1、深色海拔与播放器无彩映射 |
| 2.2 | 主题无法切换或跨进程保存，系统栏没有场景区分 | DataStore 保存 Light/Dark/System、动态中性色和处理入口偏好 | 应用、深色、播放器三套系统栏；动态色只影响去饱和表面 |
| 2.3 | 重复点击、未知深链和跨一级页返回会污染同一栈 | `SavedStateNavigationStateStore` 保存独立根栈、稳定 ID 和来源 | 播放/锁页隐藏导航；关闭第四入口安全迁回标准根页面 |
| 2.4 | 单一底栏不能适配平板和分屏 | Material Window Size Class 驱动 Compact 底栏与 Medium/Expanded 导航轨 | 两种形态共享目的地、图标和 selected 语义 |
| 2.5 | 滚动、视图和筛选在页面间混用 | `SavedStatePageViewStateStore` 使用页面、分组、版本复合 Key | 持久视图状态与会话筛选分离；删除分组和无效版本回默认 |
| 2.6 | 控件可绕过 Token、缺少读屏名称或触控区不足 | 集中 `YingLiIcon` 与 Button、输入、选择、菜单、Dialog、Banner 和状态组件 | Tabler 优先、Material 回退集中；200% 字体和 48dp 由 Compose 测试守护 |
| 2.7 | 原型冻结决策只能人工比对 | `PrototypeConformanceTest` 断言方案 F、Draft 03、功能色档位和业务层禁用项 | 测试只依赖语义角色和代码事实源，不依赖 DOM 或截图坐标 |
| 2.8 | 初始安装只有“影里”文字且没有可验证导航 | 空数据产品壳可导航、切换主题并恢复状态 | 真实媒体、详情和播放保持 Phase 3/4 边界，不创建假业务数据 |

## 依赖取舍

- Lifecycle `2.11.0` 的 Compose AAR 要求 `compileSdk 37`，因此统一固定为兼容 `compileSdk 36` 的稳定版 `2.10.0`。
- Tabler Icons `1.1.1` 是稳定版但携带旧 `kotlin-stdlib-common` 声明；应用排除该传递模块，由 Kotlin `2.4.0` 统一标准库版本。
- 未引入 Navigation Compose。Phase 2 路由只需要确定性的 SavedState 状态机，引入 NavController 不会减少当前复杂度；后续出现多 Activity、复杂图图或平台深链集成需求时再评估。

## 最终门禁

交付前执行：

```text
./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug
git diff --check
```

2026-08-07 当前结果：

- `testDebugUnitTest lintDebug assembleDebug` 成功，Configuration Cache 成功写入；
- 12 个 JVM 测试套件共 39 个测试，失败 0、错误 0、跳过 0；
- Lint 成功，错误 0、警告 0；
- Debug APK 按四种 ABI 成功分包，`arm64-v8a` 产物 68,197,961 字节；
- `compileDebugAndroidTestKotlin` 成功，6 个 Compose 仪器测试已生成；
- Gradle 自动安装被小米设备的 USB 安装限制拒绝；手动安装主 APK 和测试 APK、允许“后台弹出界面”后，使用同一 `AndroidJUnitRunner` 直接执行仪器测试，6 项通过、失败 0；
- `git diff --check` 成功。

Phase 2 本地实现、构建门禁与指定 API 33 真机测试均已完成。设备仍禁止 ADB 自动安装，因此 `connectedDebugAndroidTest` 的安装编排不能直接使用；该限制不影响已手动安装后通过同一 Runner 得到的 6 项真实仪器测试结果。
