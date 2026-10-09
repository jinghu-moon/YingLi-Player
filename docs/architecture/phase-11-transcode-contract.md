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

**【已实现 — 2026-10-09 阶段 1 步骤 1】** 预设码率经 `VideoEncoderSettings.Builder.setBitrate(...)`（码率模式固定 `BITRATE_MODE_VBR`）与 `AudioEncoderSettings.setBitrate(...)`，由 `Media3EncoderSettings.encoderFactory(...)` 经 `DefaultEncoderFactory.Builder.setRequestedVideoEncoderSettings(...)` 安装，`Media3TranscodeEngine` 在 `Transformer.Builder` 上调用 `setEncoderFactory(...)`。

真机改前/改后证据（Xiaomi 25102RKBEC，高熵源）：改前 `compatible_mp4` 与 `balanced_mp4` 输出字节数**逐字节相同**（10 657 482）；改后为 5 968 431 / 3 868 755 / 1 867 993（请求比 1.6 与 2.0，实测比 1.543 与 2.07）。证据见 `docs/architecture/evidence/stage1/`。

**【已修正 — G8 原目标不成立】** 原目标写的是「verifier 回读输出的 `KEY_BIT_RATE`，与请求值偏离超过容差时报错」。实测否掉了它：真机输出的 MP4 视频轨**读不到** `KEY_BIT_RATE`（源与三档输出全为 `null`），且 `AudioEncoderSettings.setBitrate` 的官方说明逐字为「The encoder may ignore the requested bitrate to improve the encoding quality.」。因此：

- verifier 只新增只读字段 `OutputVerification.averageBitrateBitsPerSecond`（`字节数 × 8 × 1000 / 时长毫秒`），**不参与 `valid`**；把码率偏差判成失败会误杀合法输出（静态画面下编码器会远低于目标码率）。
- 「码率是否真的生效」由两处回答：JVM 单测断言请求已进入 `VideoEncoderSettings`，真机测量断言三档预设的输出字节数分离（`Stage1BitrateWiringMeasurementTest`）。

### 预设模型的收敛（D1）

**【目标】** `TranscodePreset` 折叠为 `OutputTarget` 的命名常量，不再是独立类型；「压缩」与「格式转换」共用同一个 planner 与同一个引擎，两者的差别只是 `OutputTarget` 的取值域（详见设计文档 §4.1、§4.6）。「需要两个引擎」的理由是**编码层与封装层的可达性差异**，不是功能差异。

## 能力与降级

设备能力由 `MediaCodecList` 探测并缓存，源轨道由 `MediaExtractor` 探测。厂商 codec 查询异常只产生 `CODEC_CAPABILITY_ERROR`，不会让应用崩溃。

### HDR（G2）

**【已实现 — 2026-10-09 阶段 1 步骤 3】** 两处一起改：

- `AndroidMediaCapabilityProbe` 不再把 `supportsHdr` 写成常量，改为复用 Media3 自己的谓词 `EncoderUtil.getSupportedEncodersForHdrEditing(mimeType, colorInfo)`（对 ST2084 与 HLG、BT.2020 + 有限范围 + 10 bit 各问一次）。**不自己手写 `profileLevels` + `ColorInfo` 判断**——那会成为「谁算支持 HDR」的第二个真源。`probeEncoders()` 的 `distinctBy` 键必须含 `supportsHdr`，否则只在 HDR 能力上不同的两个编码器会被错误合并。
- `Media3TranscodeEngine` 不再因 `plan.changes` 含 `HDR_TO_SDR` 就返回 `Failed("HDR_TONE_MAPPING_UNAVAILABLE")`；HDR 模式由 `Media3EncoderSettings.hdrMode(plan)` 决定：出现 `HDR_TO_SDR` 时用 `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`，否则用默认的 `HDR_MODE_KEEP_HDR`。**固定用 OpenGL 路径**：MediaCodec 路径「Only supported on API 31+ on certain devices」，不支持的设备上 `Transformer` 会抛 `ExportException`，设备能力不确定时请求它等于拿用户编码时间试雷。

真机改后：15 个视频编码器中 2 个 HEVC（8192×8192、4096×4096）`supportsHdr = true`，声明 `hdr-editing` 的 mime 为 `video/hevc` 与 `video/x-mvhevc`；容器层声明 HDR10 的源 → planner `Ready`（`HDR_TO_SDR`）→ 引擎 `Completed` → 独立 verifier `valid = true`。证据见 `docs/architecture/evidence/stage1/stage1-hdr-capability.json`、`stage1-hdr-tonemap.json`。

**⚠ 仍未验证**：真实 HDR10/HLG 相机素材的实际 tone mapping 画质，以及多设备矩阵。本机媒体库没有任何 HDR 视频，上述实测用的是现场合成的「容器层声明 HDR10」源。**D9 只部分闭合，不得声称真实 HDR 素材的转码画质已验证。**

**一处 API 约束**：`Transformer.Builder` 没有 `setHdrMode`，设 HDR 模式只能经 `Composition`；而 `EditedMediaItemSequence` 可用的公开入口只有显式声明轨道类型的 `withAudioFrom` / `withVideoFrom` / `withAudioAndVideoFrom`（`fromSingleItem` 为包内私有，无参 `Builder()` 已废弃且本项目 `-Werror` 会失败）。因此轨道类型由计划给出：`retainedAudioTrackIds` 为空用 `withVideoFrom`，非空用 `withAudioAndVideoFrom`。

### 编码器回退（G6）

**【已实现 — 2026-10-09 阶段 1 步骤 2】** 两道防线，缺一不可：

1. **编码前拒绝**（`Media3CodecAvailability.kt`）：`MediaCodecList.findEncoderForFormat(...)` 按计划的目标 mime + 目标尺寸（视频）或 mime + 44.1 kHz / 2 声道（音频）查编码器；查不到就直接 `Failed("VIDEO_ENCODER_UNAVAILABLE")` / `Failed("AUDIO_ENCODER_UNAVAILABLE")`，**与 planner 的拒绝码一致**（同一原因不因「在哪层发现」而出现两个码）。音频只在 `plan.retainedAudioTrackIds` 非空时校验。
2. **运行期兜底**（`Media3FallbackMapping.kt` + `Media3TranscodeEngine` 覆写 `onFallbackApplied`）：把 `original.videoMimeType != fallback.videoMimeType` 映射为 `TranscodeChangeCode.VIDEO_CODEC_FALLBACK`（音频同理），随 `TranscodeEngineResult.Completed(fallbacks)` 返回；执行器见到非空 `fallbacks` 立即 `artifacts.abort(artifact)` 并返回 `Failure("DEGRADATION_CONFIRMATION_REQUIRED")`。**`null` 表示「跟随输入推断」，不构成对编码格式的承诺，故不算回退。**

为什么两道都要：第 1 道只能挡「确定做不到」（`findEncoderForFormat` 看不出 profile/level、tier、并发实例数、configure 期异常），而 `DefaultEncoderFactory.enableFallback` 默认 `true`，**没有第 2 道就必然静默降级**。两处都记录而不中断（让库把剩下的帧编完没有额外正确性代价；中断需要区分「我们自己取消」与「用户取消」，而结果无论如何不会被提交）。

**【对原目标的修正】** 原目标写的是「用 `MediaCodecInfo.CodecCapabilities.getSupportedSampleMimeTypes()` 校验」。实际实现用的是 `MediaCodecList.findEncoderForFormat(...)`：它把分辨率/帧率约束也算进去，与框架自己挑编码器的口径一致。`Muxer.Factory.getSupportedSampleMimeTypes()` 是**封装层**的能力来源，等阶段 2 步骤 7 接入 `Muxer.Factory` 时再与本节的能力来源统一。

**【对契约的修正】** `TranscodeChange` 原有的 `requiresConfirmation: Boolean` 字段已删除，改为由 `TranscodeChangeCode.requiresConfirmation()` 派生（字段与枚举并存 = 同一事实两个真源、可互相矛盾）。

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
- **真实 HDR10/HLG 相机素材的 tone mapping 画质未验证**。能力探测与通路已在真机实测通过（G2 已修复，见上），但本机媒体库没有任何 HDR 视频，实测用的是容器层声明 HDR10 的合成源；多设备矩阵同样未覆盖（D9 只部分闭合）。
- 能力快照仍需在每台目标设备上跑真实 SDR/HDR、多轨、VFR、长任务和空间耗尽矩阵；**AOSP 模拟器通过不等于厂商真机通过**。
