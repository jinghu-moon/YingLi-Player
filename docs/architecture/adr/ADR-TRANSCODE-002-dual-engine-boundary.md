# ADR-TRANSCODE-002：编码层用 Transformer、封装层用手写 extractor + muxer 的双引擎边界

- **状态**：已采纳（Accepted）
- **日期**：2026-10-09
- **决策者**：项目作者（采纳 `docs/architecture/Organizing-Page-Function-Design.md` §19 的建议）
- **相关**：`docs/architecture/phase-11-transcode-contract.md`、`docs/architecture/phase-10-clips-contract.md`、`docs/architecture/Organizing-Page-Function-Design.md` §4.6（F18）
- **取代**：无
- **编号说明**：编号沿用参考资料 ADR 系列，与本项目自有 ADR 序号无关

---

## 1. 背景

### 1.1 被推翻的旧假设

项目早期把「视频压缩」和「格式转换」当作两个独立功能，因此推断需要两套引擎。这个推断是错的，但**「两个引擎」这个结论碰巧是对的——理由完全不同**。

参考资料（粘贴对话）也主张「压缩和格式转换是两个功能」，用户在对话中两次纠正 AI「我说的是库」「压缩和格式转换是两个功能」。这条表述在**产品入口层面**成立，在**实现层面**是误导。

### 1.2 第一性原理：可达性有两层

把「把一段视频变成另一种文件」拆到不可再还原的粒度，只剩下两件事：

1. **改变每个样本的字节**（编码）
2. **把样本按时间戳写进容器**（封装）

这两件事的**能力集合完全不同**，且**互不蕴含**：

| 层 | 平台 + Media3 能做什么 | 覆盖 |
| --- | --- | --- |
| **编码层** | 输出 H.263 / H.264 / H.265 / MPEG-4 SP + AAC / AMR | 产品需求 100% 覆盖：**MP4 + H.264 + AAC 是唯一必需的目标** |
| **封装层** | 额外可写入 VP9 / Opus / Vorbis / AV1 / Dolby Vision / APV | 仅「源已经是该 codec、只需换容器」的场景 |

关键事实（F18）：**封装层的能力严格大于编码层**。平台 `MediaMuxer` 支持 VP9（仅 WebM）、Opus（仅 WebM/Ogg，**不能进 MP4**）、AV1（MP4，API 31+）、Dolby Vision（MP4，API 32+）；Media3 的 `Mp4Muxer` 是纯 Java 应用内 muxer，还接受 `video/x-vnd.on2.vp9` 与 `video/av01` 进 MP4。

而 Media3 `Transformer` 的硬边界是：**at most one video track and one audio track**，且**没有** `setMuxerFactory` 之外的容器扩展点来写入它不支持的 codec。

### 1.3 为什么不能只用 Transformer

Transformer 覆盖了编码层的全部需求，但**够不着**「源已经是 VP9/Opus/AV1，只需要换容器」这一小类场景。这类场景下重新编码是**无谓的质量损失**（F16：有损重编码必然损失质量且耗时）。

### 1.4 为什么不能只用手写管线

手写 `MediaExtractor` + `MediaCodec` + `Muxer` 意味着自己承担 Transformer 已经解决的全部设备一致性问题：Surface 生命周期、编码器 profile/level 选择、设备特定的 workaround、进度与取消、编码器回退回调。这是十项强制约束，**不是可以顺手补上的工作量**。

---

## 2. 决策

**编码必须走 Media3 `Transformer`；封装在 Transformer 够不着的场景走手写 `MediaExtractor` + `Muxer`。两个引擎是同一套 `OutputTarget` 下的两条实现路径，不是两个功能。**

具体地：

1. **域层只有一个目标模型**：`OutputTarget`（容器 × 视频 codec × 音频 codec × 分辨率 × 码率）。压缩与格式转换都是给这个模型赋不同值的操作，**没有第二个 planner**。
2. **路由发生在执行层，不在 UI 层**：由目标与源的可达性判定决定走哪条路径，UI 只展示「快速封装」与「重新编码」的预期差异（速度/质量/兼容性），不暴露引擎选择。
3. **两条路径共用同一套**：能力探测（`MediaCapabilityProbe`）、空间预估、输出命名与冲突策略、独立验证器（`OutputVerifier`）、任务中心进度模型。
4. **封装层的轨道白名单必须来自反查，不能硬编码**：`PlatformClipEngine.kt:207-213` 的 `FAST_MP4_MIME_TYPES` 是硬编码的 5 项，导致 WebM(VP9/Opus) 源在快速模式必然 `FAST_CONTAINER_UNSUPPORTED`（G11）。应改为反查 `Muxer.Factory.getSupportedSampleMimeTypes()`（或 `Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES` / `SUPPORTED_AUDIO_SAMPLE_MIME_TYPES`），且**与 Phase 11 共用同一份能力来源**。
5. **已知且被接受的边界**（不是缺陷）：多音轨保留与字幕嵌入在 Transformer 路径上不可能；HDR 源的处理能力依赖设备。

---

## 3. 后果

### 3.1 正面

- 产品入口可以明确区分「快速封装（无损、秒级、可能偏移到关键帧）」与「重新编码（慢、有损、所见即所得）」，符合 `docs/02-product-and-ui-research.md:125,148` 与 Q226。
- 编码路径复用 Media3 的全部设备兼容性工作，**不自造轮子**。
- 封装路径不需要引入 FFmpeg，即可覆盖 VP9/Opus/AV1 的换容器场景。
- 「两个引擎」有单一理由（F18），未来任何「要不要合并成一个引擎」的讨论都有可判定的依据。

### 3.2 负面 / 代价

- 需要维护两条执行路径，且两条路径的**输出必须过同一个验证器**（`OutputVerifier`），否则「成功」状态会出现两个来源，违反 Phase 11 契约的「成功状态只能由独立 verifier 产生」。
- 封装层需要自己处理平台 `MediaMuxer` 的诸多限制：`addTrack` 必填键、codec-specific data 必须在 `addTrack` 给出、样本时间戳单调、`KEY_ROTATION` 不生效（MP4 必须用 `setOrientationHint`）。
- 平台 `MediaExtractor` 在 API 36 上**仍无 `setCancellationSignal`**，取消必须分两层（扫描循环自查标记 + 从取消线程 `release()` 容器），与 `docs/19-player-implementation-progress.md:258` 的既有结论一致。

### 3.3 中立

- 两个引擎**不共享代码**，只共享契约与验证器。这不是重复实现，是两层能力差异的直接投影。

---

## 4. 备选方案与否决理由

| 方案 | 否决理由 |
| --- | --- |
| 只保留 Transformer | 够不着 VP9/Opus/AV1 换容器；这些场景只能重编码，损失质量且耗时（F16） |
| 只保留手写管线 | 自造 Transformer 已解决的十项设备一致性约束，违背「无明确需求不引入复杂度」 |
| 引入 FFmpeg 覆盖两者 | 已裁决 No-Go（`docs/architecture/phase-14-experiment-ledger.md`）；否决理由不是许可（本项目 `LICENSE:1-2` 为 AGPL-3.0，与 GPL-3.0 兼容），而是**零需求覆盖 + 体积 + 单点维护者 + 专利未核实**（`docs/architecture/Organizing-Page-Function-Design.md` §13.1） |
| 把两个功能做成两套引擎 | 两个功能共用同一个 `OutputTarget` 与同一套可达性判定；做成两套会产生两套能力探测、两套空间预估、两套验证器 |

---

## 5. 验证方式

1. **契约测试（JVM）**：给定源能力 + 目标，可达性判定必须唯一确定一条路径；不可达必须返回明确拒绝码而不是静默降级。
2. **白名单反查测试**：构造 VP9/Opus 源，反查得到的白名单必须允许快速封装（当前实现会返回 `FAST_CONTAINER_UNSUPPORTED`，该测试在修复前**必须失败**）。
3. **单一验证器测试**：两条路径的输出都必须经过同一个 `OutputVerifier`；测试必须锁住「任何路径都不能绕过验证器产生成功状态」。
4. **设备测试**：真实 HDR 源 + 多设备矩阵（D9），验证编码路径的 `HDR_MODE_*` 行为与封装路径的色彩元数据保留。
5. **回归**：`.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`。

---

## 6. 关联

- `docs/architecture/Organizing-Page-Function-Design.md` §4.1（推翻「压缩和格式转换是两个功能」）、§4.4（输出侧可达矩阵）、§4.6（可达性的两层）、§13.1（FFmpeg 否决）
- `docs/architecture/phase-11-transcode-contract.md`（编码层契约、G1/G2/G6）
- `docs/architecture/phase-10-clips-contract.md`（封装层契约、快速/精确模式、G11）
- `docs/architecture/phase-14-experiment-ledger.md`（FFmpeg 后端 No-Go）
