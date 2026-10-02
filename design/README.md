# design/ — 设计原素材

这里存放**引入项目的外部设计原素材**（图标、图片、字体等）的原始文件，只作为来源与再生成的依据，
不直接参与打包。

## 约定

| 类型 | 原始素材（本目录，可追溯） | 进包形式（Android 平台格式） |
| --- | --- | --- |
| 图标 | `assets/icons/*.svg` | 默认**不需要**进包：在 `core/designsystem/icon` 里转成 `ImageVector`，由 `YingLiIcon` 统一暴露语义入口 |
| 位图 | `assets/images/*.png`/`*.webp` | `app/src/main/res/drawable/`，文件名 `img_` 前缀 |
| 字体 | `assets/fonts/*.ttf`/`*.otf` | `app/src/main/res/font/`，文件名小写下划线 |

两条硬性约束：

1. `res/` 下**不能建任意子目录**（AAPT2 只接受合法的资源限定符目录），所以平台资源是扁平的，
   靠文件名前缀（`ic_`/`img_`/`font_`）分类；分类信息留在本目录的目录结构里。
2. 业务代码不得直接引用第三方图标资源 ID，图标语义必须走 `YingLiIcon`（见
   `docs/architecture/phase-2-prototype-contract.md`）。

## 合成图标（Compose ImageVector）

应用内的图标来自本地生成的 Tabler 图标集
（`io.github.jinghu-moon.composeicons:icons-tabler:0.1.0-local.1`，基于 Tabler Icons 3.46.0）。
该集合缺失某个语义图标时，把原始 SVG 放进本目录，并在
`app/src/main/java/seeyuer/yingli/player/core/designsystem/icon/YingLiLocalIcons.kt`
里用与生成图标相同的构建方式（`iconBuilder` + `addPathData`，24dp/24 视图、2dp 圆头描边）合成，
再由 `YingLiIcon` 增加语义条目。

## 现有素材

| 文件 | 用途 | 合成位置 | 说明 |
| --- | --- | --- | --- |
| `assets/icons/play-mode-sequence.svg` | 播放顺序＝顺序播放 | `YingLiLocalIcons.PlayModeSequence` | Tabler Icons 3.46.0 无对应图标，故本地合成 |
