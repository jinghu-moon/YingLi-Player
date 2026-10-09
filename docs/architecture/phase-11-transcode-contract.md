# Phase 11 转码契约

> **本文状态**：描述 Phase 11 已实现的契约，并标注已裁决但**尚未实现**的目标契约。
> 已裁决的目标设计见 `docs/architecture/Organizing-Page-Function-Design.md` §6（压缩与格式转换设计）与 §14.2（阶段 1）。
> 标记约定：**【现状】**= 代码当前行为；**【目标】**= 已裁决、待实现。

## 固定预设

| ID | 版本 | 视频/音频 | 最大长边 | 视频码率 | 音频码率 |
| --- | --- | --- | ---: | ---: | ---: |
| `compatible_mp4` | 1 | AVC/AAC in MP4 | 1920 | 8 Mbps | 192 Kbps |
| `balanced_mp4` | 1 | AVC/AAC in MP4 | 1920 | 5 Mbps | 160 Kbps |
| `space_saver_mp4` | 1 | AVC/AAC in MP4 | 1280 | 2.5 Mbps | 128 Kbps |

UI 不接受任意 codec、码率或容器参数。规划器取源尺寸、预设上限和设备编码器能力的交集，输出宽高强制为偶数。空间估算按目标总码率乘 1.15，并额外保留 128 MiB；空间不足不创建任务。

### 预设码率的接线要求（G1 / G8）

**【现状 — 缺陷】** `Media3TranscodeEngine.kt:79-84` 构造 `Transformer` 时**没有调用 `setEncoderFactory`，也没有传入任何码率**，因此上表的「视频码率」列**没有生效**：实际码率由 `DefaultEncoderFactory.getSuggestedBitrate()` 按设备能力推导。后果是 `compatible_mp4`（8 Mbps）与 `balanced_mp4`（5 Mbps）在最大长边、编码格式、音频码率上完全相同，**除输出文件名与空间预检外完全等价**，同一源视频会产出同样大小的文件。`targetVideoBitrate` 目前只在 `TranscodeContracts.kt:204` 参与体积估算。

**【目标】** 预设码率必须经 `VideoEncoderSettings.Builder.setBitrate(...)` 与 `AudioEncoderSettings` 真正下发给编码器，并通过 `DefaultEncoderFactory.Builder.setRequestedVideoEncoderSettings(...)` 安装。

**【目标】** 独立 verifier 必须回读输出的 `KEY_BIT_RATE`，与请求值偏离超过容差时报错（G8）。**在此之前，"码率已生效"不可声称。**

### 预设模型的收敛（D1）

**【目标】** `TranscodePreset` 折叠为 `OutputTarget` 的命名常量，不再是独立类型；「压缩」与「格式转换」共用同一个 planner 与同一个引擎，两者的差别只是 `OutputTarget` 的取值域（详见设计文档 §4.1、§4.6）。「需要两个引擎」的理由是**编码层与封装层的可达性差异**，不是功能差异。

## 能力与降级

设备能力由 `MediaCodecList` 探测并缓存，源轨道由 `MediaExtractor` 探测。厂商 codec 查询异常只产生 `CODEC_CAPABILITY_ERROR`，不会让应用崩溃。

### HDR（G2）

**【现状 — 缺陷】** `AndroidMediaCapabilityProbe.kt:114` 把 `supportsHdr` **硬编码为 `false`**，导致 `TranscodeContracts.kt:195-197` 对**每一个 HDR 源**都生成 `HDR_TO_SDR`；而 `Media3TranscodeEngine.kt:51-53` 只要 `plan.changes` 含 `HDR_TO_SDR` 就直接返回 `Failed("HDR_TONE_MAPPING_UNAVAILABLE")`。**结果：HDR 视频在当前实现下必然失败。**

这与事实不符：Media3 `Composition.HdrMode` 的 tone mapping 能力自 API 29 起可用（OpenGL 路径），本项目 minSdk 31。**因此这不是平台限制，是代码缺陷。**

**【目标】** `supportsHdr` 改为实测（`MediaCodecInfo.CodecCapabilities.profileLevels` 查 HEVC Main10 + `ColorInfo`）；`Media3TranscodeEngine` 改为通过 `Composition.Builder.setHdrMode(...)` 选择 `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC`（设备支持时）或 `..._USING_OPEN_GL`（兜底），**而不是无条件拒绝**。

**【目标】** 在完成真实 HDR 样本实测前，不得声称 HDR 转码已支持。实测覆盖率是待决项 D9。

### 编码器回退（G6）

**【现状 — 缺陷】** 未处理 `Transformer.Listener.onFallbackApplied`。当请求的编码格式不被支持时，Media3 会静默回退到另一种编码器，**用户拿到与请求不同的编码格式而没有任何提示**。

**【目标】** 覆写 `onFallbackApplied`，把它转换为 `requiresConfirmation = true` 的 change；并在 build 之前用 `MediaCodecInfo.CodecCapabilities.getSupportedSampleMimeTypes()` 校验目标格式，不支持的组合**在 planner 阶段就拒绝**，不进入引擎。

## 执行与发布

Media3 Transformer 在主线程 looper 执行，进度每 250ms 采样；取消、编码失败或异常会删除私有临时输出。任务复用 Phase 9 的持久处理状态机和原子产物提交协议。

成功状态只能由独立 verifier 产生。verifier 检查文件、视频/音频轨道、目标 codec、尺寸、时长容差、压缩样本可读性，并实际解码首个同步帧。任一检查失败时不提交媒体库。

**【目标】** verifier 增加码率回读校验（见上）。

## 轨道与字幕的硬边界

**【现状】** 只保留首个音轨；字幕不嵌入。

**【事实 — 不是缺陷】** 这是 **Media3 Transformer 的结构性边界**，不是可以靠改代码绕过的限制：

- `Transformer` 的参考文档原文：*"The output can contain at most one video track and one audio track. Other track types are ignored."*
- `Composition.sequences` 的官方原文：*"MediaItem instances from different sequences that are overlapping in time will be **mixed** in the output."* —— 多条 `EditedMediaItemSequence` 是**混音**，不是保留为多条独立轨道。

**⚠ 已证伪的旧表述**：本文档早期版本与设计初稿都写过「保留多音轨需要 `Composition.Builder.setSequences(...)` 构造多条 `EditedMediaItemSequence`」。**这条是错的**，已删除，不要再据此实现。

**【事实】** 平台 `MediaMuxer` 的「多视频/音频轨与 Metadata 轨仅 MP4 且 API 26+」限制**不适用于 Media3 的 `Mp4Muxer`**——后者是纯 Java 应用内多轨 muxer，轨道数不限，支持 `addTrackReference` 与 MP4-AT。因此**多音轨保留在「手写 `MediaExtractor` + `Mp4Muxer`」这条路上是可达的**（正是 `PlatformClipEngine.fastCut` 已在做的搬运循环），但那是**另一条引擎**，当前无产品决策要求它。

**【目标 — 产品边界】** 「保留轨道的能力不做，但『要求确认』的行为必须保留」。`TranscodeContracts.kt:189-194` 已为 `EXTRA_AUDIO_TRACKS_REMOVED` 与 `SUBTITLES_NOT_EMBEDDED` 置 `requiresConfirmation = true`，满足 Q498「不静默丢轨」，**这部分是正确的，不需要改**。等到出现真实需求时，按 Q543 以独立 `ProcessingEngine` 接入，不预先建抽象。

## 已知限制

- 只保留首个音轨；字幕不嵌入（见上「硬边界」）。
- HDR 转换在代码层面被关闭，**属缺陷（G2）**，不是平台能力不足。
- 能力快照仍需在每台目标设备上跑真实 SDR/HDR、多轨、VFR、长任务和空间耗尽矩阵；**AOSP 模拟器通过不等于厂商真机通过**。
- 真机门禁当前被 MIUI 以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝（Xiaomi M2012K11AC，Android 13 / API 33），`connectedDebugAndroidTest` **不计为通过**。
