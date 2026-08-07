# Phase 2 原型到 Compose 契约

## 事实源

Phase 2 只冻结三个原型中已经确认的结构与语义，不把 HTML DOM 或像素坐标带入生产实现：

| 优先级 | 原型 | Compose 契约 |
|---:|---|---|
| 1 | `01-navigation-style-prototypes.html` 的方案 F | Compact 使用“首页 / 视频 / 整理”三项底栏；Medium/Expanded 使用导航轨；处理中心默认顶部入口，可由用户固定为第四项；设置始终是顶部或页面内入口 |
| 2 | `02-color-system-components.html` 的 Draft 03 | 结构选中只使用黑白中性灰；钢蓝仅用于信息、焦点、链接和处理中；成功绿不表示观看进度或一级选中 |
| 3 | `03-functional-color-brightness-comparison.html` | 浅色使用 B 档功能色，深色使用 A 档功能色；播放器使用纯黑画布和无彩白色透明度控件 |

冲突时按上表优先级处理。产品需求和 Phase 清单高于 HTML 原型；自动测试断言语义角色和 Token，不解析 HTML DOM。

## Token 映射

| 原型角色 | Compose 事实源 |
|---|---|
| 13 级中性灰 | `YingLiPrimitiveTokens.neutralScale` |
| 浅色 / 深色语义表面与文字 | `YingLiSemanticTokens.LightColors` / `DarkColors` |
| 浅 B / 深 A 功能色 | `LightFunctional` / `DarkFunctional` |
| Hover、Pressed、Dragged | `stateHover` / `statePressed` / `stateDragged` |
| 浅 32/56/72%、深 40/64/80% Scrim | `scrimSubtle` / `scrimDefault` / `scrimStrong` |
| 播放器黑色画布、白 100/72/40 | `PlayerScrimTokens` |
| 三套系统栏 | `SystemBarTokens.lightApp` / `darkApp` / `player` |
| 触控区、导航尺寸、间距、圆角 | `YingLiComponentTokens` |

业务 Feature 禁止直接引用 Primitive、Hex、Tabler 或 Material 图标。`YingLiIcon` 负责语义映射，以 Tabler 为主；只有没有等价语义的动态颜色入口回退 Material 图标。

## 窗口与导航矩阵

| 场景 | 一级导航 | 顶部动作 | 状态规则 |
|---|---|---|---|
| Compact `< 600dp` | 3 项底栏，可选第 4 项处理中心 | 处理中心、设置 | 每个一级页面保留独立栈 |
| Medium `600-839dp` | 导航轨 | 处理中心、设置 | 跨断点不重建 ViewModel 状态 |
| Expanded `>= 840dp` | 导航轨 | 处理中心、设置 | 与 Medium 共享导航语义 |
| 详情 | 保留当前一级导航 | 返回、处理中心、设置 | 返回来源页面及原状态 |
| 播放 | 隐藏一级导航和应用顶栏 | 播放页自身返回 | 画布固定纯黑，系统栏使用 Player 映射 |
| 应用锁 | 隐藏一级导航和应用顶栏 | 无 | 不泄露页面内容 |

## 自动化门禁

| 契约 | JVM | Compose 真机 |
|---|---:|---:|
| Token 数量、对比度、透明度、深色表面海拔 | 是 | - |
| 三态主题回退、功能色档位、系统栏映射 | 是 | - |
| 独立一级栈、重复导航、未知深链、来源返回 | 是 | - |
| 页面/分组状态隔离、版本回退、会话筛选清理 | 是 | - |
| Compact 底栏、Medium/Expanded 导航轨 | - | 是 |
| 可选第四入口、播放页隐藏导航 | - | 是 |
| selected/contentDescription/48dp | - | 是 |
| 200% 字体关键设置可滚动访问 | - | 是 |
| Feature 禁止 Primitive、Hex 和第三方图标直绑 | 是 | - |

动态色默认关闭。启用后只把 Material You 颜色转换为低饱和中性表面；结构选中和四类固定功能色继续使用 YingLi Token，不接受壁纸颜色污染。
