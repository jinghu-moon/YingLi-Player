# Phase 11 转码契约

> **本文状态**：描述 Phase 11 已实现的契约，并标注已裁决但**尚未实现**的目标契约。
> 已裁决的目标设计见 `docs/architecture/Organizing-Page-Function-Design.md` §6（压缩与格式转换设计）、§14.2（阶段 1）与 §14.3（阶段 2）。
> 标记约定：**【已实现】**= 代码当前行为；**【目标】**= 已裁决、待实现；**【已修正】**= 原目标已被实测或推理否掉，并记录更正后的口径。
> **命名变更（2026-10-09 阶段 2 步骤 5）**：`Transcode*` 一族已随 `OutputTarget` 折叠改名，见下表。本文档正文已同步。
>
> | 旧名 | 新名 |
> | --- | --- |
> | `TranscodePreset` | `OutputTarget`（取值常量在 `object OutputTargets`） |
> | `TranscodePlan` | `ProcessingPlan` |
> | `TranscodeChange` / `TranscodeChangeCode` | `ProcessingChange` / `ProcessingChangeCode` |
> | `TranscodeEngine` / `TranscodeEngineResult` | `ProcessingEngine`（方法 `process`）/ `ProcessingEngineResult` |
> | `TranscodeQueue` | `ProcessingQueue` |
> | `Media3TranscodeEngine` | `Media3ProcessingEngine` |

## 固定预设

**【已实现 — 2026-10-09 阶段 2 步骤 5】** `TranscodePreset` 已不再是独立类型，三档取值是 `domain/processing/ProcessingPlanContracts.kt` 里 `OutputTargets` 的三个 `OutputTarget` 命名常量（`version` 字段已删除：没有持久化契约需要版本号）。下表是该折叠的取值等价表——**数值未变，只是实体变了**。

| OutputTarget 常量 | 容器/codec | 最大长边 | 视频码率 | 音频码率 |
| --- | --- | ---: | ---: | ---: |
| `OutputTargets.Compatible`（原 `compatible_mp4`） | AVC/AAC in MP4 | 1920 | 8 Mbps | 192 Kbps |
| `OutputTargets.Balanced`（原 `balanced_mp4`） | AVC/AAC in MP4 | 1920 | 5 Mbps | 160 Kbps |
| `OutputTargets.SpaceSaver`（原 `space_saver_mp4`） | AVC/AAC in MP4 | 1280 | 2.5 Mbps | 128 Kbps |

UI 不接受任意 codec、码率或容器参数。规划器（`DefaultProcessingPlanner.plan`）取源尺寸、目标上限和设备编码器能力的交集，输出宽高强制为偶数（`and -2`）。空间估算按目标总码率乘 1.15，并额外保留 **256 MiB**（`PROCESSING_FREE_SPACE_RESERVE_BYTES`）；空间不足不创建任务。**该预留量此前有两个互相矛盾的真源**（域层 128 MiB 与调度侧 `MediaContainer.MINIMUM_FREE_BYTES` 256 MiB），步骤 5 已收敛为唯一常量，调度侧直接引用它。

### 预设码率的接线要求（G1 / G8）

**【已实现 — 2026-10-09 阶段 1 步骤 1】** 预设码率经 `VideoEncoderSettings.Builder.setBitrate(...)`（码率模式固定 `BITRATE_MODE_VBR`）与 `AudioEncoderSettings.setBitrate(...)`，由 `Media3EncoderSettings.encoderFactory(...)` 经 `DefaultEncoderFactory.Builder.setRequestedVideoEncoderSettings(...)` 安装，`Media3ProcessingEngine` 在 `Transformer.Builder` 上调用 `setEncoderFactory(...)`。

真机改前/改后证据（Xiaomi 25102RKBEC，高熵源）：改前 `compatible_mp4` 与 `balanced_mp4` 输出字节数**逐字节相同**（10 657 482）；改后为 5 968 431 / 3 868 755 / 1 867 993（请求比 1.6 与 2.0，实测比 1.543 与 2.07）。证据见 `docs/architecture/evidence/stage1/`。

**【已修正 — G8 原目标不成立】** 原目标写的是「verifier 回读输出的 `KEY_BIT_RATE`，与请求值偏离超过容差时报错」。实测否掉了它：真机输出的 MP4 视频轨**读不到** `KEY_BIT_RATE`（源与三档输出全为 `null`），且 `AudioEncoderSettings.setBitrate` 的官方说明逐字为「The encoder may ignore the requested bitrate to improve the encoding quality.」。因此：

- verifier 只新增只读字段 `OutputVerification.averageBitrateBitsPerSecond`（`字节数 × 8 × 1000 / 时长毫秒`），**不参与 `valid`**；把码率偏差判成失败会误杀合法输出（静态画面下编码器会远低于目标码率）。
- 「码率是否真的生效」由两处回答：JVM 单测断言请求已进入 `VideoEncoderSettings`，真机测量断言三档预设的输出字节数分离（`Stage1BitrateWiringMeasurementTest`）。

### 预设模型的收敛（D1）

**【已实现 — 2026-10-09 阶段 2 步骤 5】** `OutputTarget` 与 `ProcessingOperation` 已落地于 `domain/processing/ProcessingPlanContracts.kt`（域层，不导入 `android.*`）；`TranscodePreset` 折叠为 `OutputTargets` 的三个命名常量。「压缩」与「格式转换」共用同一个 planner（`DefaultProcessingPlanner.plan`）与同一个 `ProcessingEngine` 接口，两者的差别只是 `OutputTarget` 的取值域与 planner 判出的 `ProcessingOperation`（详见设计文档 §4.1、§4.6、§14.3.1）。「需要两个引擎」的理由是**编码层与封装层的可达性差异**，不是功能差异。

**一处对设计稿 §4.2 的更正（实质）**：原稿的判定顺序照字面实现会把「压缩」判成 REMUX（三档预设的容器与 codec 与源相同，只是多了质量参数），用户点压缩却拿到原样复制的文件。实现改为 `operation = if (target.hasQualityParameters || videoCodecChanged || audioCodecChanged) TRANSCODE else REMUX`，即**质量参数挡在 remux 之前**；同时 `REMUX` 不再查编码器（否则会把「VP9/Opus 的 MKV 换容器成 MP4」错误拒绝）。

## 能力与降级

设备能力由 `MediaCodecList` 探测并缓存，源轨道由 `MediaExtractor` 探测。厂商 codec 查询异常只产生 `CODEC_CAPABILITY_ERROR`，不会让应用崩溃。

### HDR（G2）

**【已实现 — 2026-10-09 阶段 1 步骤 3】** 两处一起改：

- `AndroidMediaCapabilityProbe` 不再把 `supportsHdr` 写成常量，改为复用 Media3 自己的谓词 `EncoderUtil.getSupportedEncodersForHdrEditing(mimeType, colorInfo)`（对 ST2084 与 HLG、BT.2020 + 有限范围 + 10 bit 各问一次）。**不自己手写 `profileLevels` + `ColorInfo` 判断**——那会成为「谁算支持 HDR」的第二个真源。`probeEncoders()` 的 `distinctBy` 键必须含 `supportsHdr`，否则只在 HDR 能力上不同的两个编码器会被错误合并。
- `Media3ProcessingEngine` 不再因 `plan.changes` 含 `HDR_TO_SDR` 就返回 `Failed("HDR_TONE_MAPPING_UNAVAILABLE")`；HDR 模式由 `Media3EncoderSettings.hdrMode(plan)` 决定：出现 `HDR_TO_SDR` 时用 `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`，否则用默认的 `HDR_MODE_KEEP_HDR`。**固定用 OpenGL 路径**：MediaCodec 路径「Only supported on API 31+ on certain devices」，不支持的设备上 `Transformer` 会抛 `ExportException`，设备能力不确定时请求它等于拿用户编码时间试雷。

真机改后：15 个视频编码器中 2 个 HEVC（8192×8192、4096×4096）`supportsHdr = true`，声明 `hdr-editing` 的 mime 为 `video/hevc` 与 `video/x-mvhevc`；容器层声明 HDR10 的源 → planner `Ready`（`HDR_TO_SDR`）→ 引擎 `Completed` → 独立 verifier `valid = true`。证据见 `docs/architecture/evidence/stage1/stage1-hdr-capability.json`、`stage1-hdr-tonemap.json`。

**⚠ 仍未验证**：真实 HDR10/HLG 相机素材的实际 tone mapping 画质，以及多设备矩阵。本机媒体库没有任何 HDR 视频，上述实测用的是现场合成的「容器层声明 HDR10」源。**D9 只部分闭合，不得声称真实 HDR 素材的转码画质已验证。**

**一处 API 约束**：`Transformer.Builder` 没有 `setHdrMode`，设 HDR 模式只能经 `Composition`；而 `EditedMediaItemSequence` 可用的公开入口只有显式声明轨道类型的 `withAudioFrom` / `withVideoFrom` / `withAudioAndVideoFrom`（`fromSingleItem` 为包内私有，无参 `Builder()` 已废弃且本项目 `-Werror` 会失败）。因此轨道类型由计划给出：`retainedAudioTrackIds` 为空用 `withVideoFrom`，非空用 `withAudioAndVideoFrom`。

### 编码器回退（G6）

**【已实现 — 2026-10-09 阶段 1 步骤 2】** 两道防线，缺一不可：

1. **编码前拒绝**（`Media3CodecAvailability.kt`）：`MediaCodecList.findEncoderForFormat(...)` 按计划的目标 mime + 目标尺寸（视频）或 mime + 44.1 kHz / 2 声道（音频）查编码器；查不到就直接 `Failed("VIDEO_ENCODER_UNAVAILABLE")` / `Failed("AUDIO_ENCODER_UNAVAILABLE")`，**与 planner 的拒绝码一致**（同一原因不因「在哪层发现」而出现两个码）。音频只在 `plan.retainedTrackIds` 非空时校验。
2. **运行期兜底**（`Media3FallbackMapping.kt` + `Media3ProcessingEngine` 覆写 `onFallbackApplied`）：把 `original.videoMimeType != fallback.videoMimeType` 映射为 `ProcessingChangeCode.VIDEO_CODEC_FALLBACK`（音频同理），随 `ProcessingEngineResult.Completed(fallbacks)` 返回；执行器见到非空 `fallbacks` 立即 `artifacts.abort(artifact)` 并返回 `Failure("DEGRADATION_CONFIRMATION_REQUIRED")`。**`null` 表示「跟随输入推断」，不构成对编码格式的承诺，故不算回退。**

为什么两道都要：第 1 道只能挡「确定做不到」（`findEncoderForFormat` 看不出 profile/level、tier、并发实例数、configure 期异常），而 `DefaultEncoderFactory.enableFallback` 默认 `true`，**没有第 2 道就必然静默降级**。两处都记录而不中断（让库把剩下的帧编完没有额外正确性代价；中断需要区分「我们自己取消」与「用户取消」，而结果无论如何不会被提交）。

**【对原目标的修正】** 原目标写的是「用 `MediaCodecInfo.CodecCapabilities.getSupportedSampleMimeTypes()` 校验」。实际实现用的是 `MediaCodecList.findEncoderForFormat(...)`：它把分辨率/帧率约束也算进去，与框架自己挑编码器的口径一致。`Muxer.Factory.getSupportedSampleMimeTypes()` 是**封装层**的能力来源，已在阶段 2 步骤 7 接入（见下节「封装层」）。

**【对契约的修正】** `ProcessingChange` 原有的 `requiresConfirmation: Boolean` 字段已删除，改为由 `ProcessingChangeCode.requiresConfirmation()` 派生（字段与枚举并存 = 同一事实两个真源、可互相矛盾）。

### 封装层：容器适配与 build 前校验（G4 / F19）

**【已实现 — 2026-10-09 阶段 2 步骤 7】** 新增 `app/src/main/java/seeyuer/yingli/player/data/processing/muxer/ContainerMuxerFactory.kt`：

- `MuxerContainer` 枚举持有**每个容器实际允许写入的样本 mime**：`video/mp4`、`video/webm`（VP8/VP9 + Opus/Vorbis）、`audio/ogg`（Opus）、`audio/aac`（ADTS，AAC）。MP4 一行**直接取** `Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES` / `SUPPORTED_AUDIO_SAMPLE_MIME_TYPES`，**不手写**——手写第二份清单正是 G3/G11 的成因（`PlatformClipEngine.kt` 的 `FAST_MP4_MIME_TYPES`）。
- `ContainerMuxerFactory : Muxer.Factory` 为**四个容器**提供适配器（`Mp4Muxer` / `WebmMuxer` / `OggMuxer` / `AacMuxer`），其 `getSupportedSampleMimeTypes(trackType)` 回报的与上表是**同一份**。`androidx.media3:media3-muxer` 由传递依赖改为在 `gradle/libs.versions.toml` 显式声明。**（步骤 8 起 MP4 也由本类提供：手写搬运管线 `InAppRemuxEngine` 需要它，而且只有 `Mp4Muxer` 能把 VP9/Opus 写进 MP4。）**
- **MP4 刻意不接管（这条取舍属于 `Transformer`，不属于工厂）**：`Transformer.Builder` 的默认封装器 `DefaultMuxer.Factory` 携带 `videoDurationUs`、元数据收集与 faststart 相关配置，换成裸 `Mp4Muxer` 的能力增量是零，所以 `Media3ProcessingEngine` 只在 `plan.target.containerMimeType != MimeTypes.VIDEO_MP4` 时才调用 `setMuxerFactory`。**【步骤 8 修正】** 此前的写法是 `ContainerMuxerFactory.forContainerMimeType("video/mp4")` 返回 `null`，让调用方据此跳过——那是把调用方的取舍塞进了工厂的语义，已改。
- **build 前校验**（F19）：`Media3ProcessingEngine.process` 在构造 `Transformer` **之前**查同一张表，不通过即返回 `TARGET_CONTAINER_UNSUPPORTED` / `CONTAINER_VIDEO_CODEC_UNSUPPORTED` / `CONTAINER_AUDIO_CODEC_UNSUPPORTED` 且**不产生输出文件**。理由：`Transformer.Builder.build()` 在 muxer 不收所请求 mime 时抛异常，而那时**整段编码已经花掉了**。
- 容器名沿用域层既有词汇：ADTS 写 `"audio/aac"`（与 `OutputTarget.fileExtension` 的 `"audio/aac" → .aac` 一致），**不是** `MimeTypes.AUDIO_AAC`（`"audio/mp4a-latm"`，那是**样本** mime）。

**【封装层引擎也用同一份能力来源 — 步骤 8】** `app/src/main/java/seeyuer/yingli/player/data/processing/InAppRemuxEngine.kt`（`ProcessingOperation.REMUX`，取代已删除的 `PlatformRemuxEngine.kt`）用 Media3 `Muxer` 而非平台 `MediaMuxer` 写样本，白名单判据与上节完全相同（`ContainerMuxerFactory.supports(...)` → `Muxer.Factory.getSupportedSampleMimeTypes(...)`）。硬编码常量 `MP4_REMUX_MIME_TYPES` 已随旧引擎一起删除（G3/G11 的修法）。**该路径有真机用例**：`app/src/androidTest/java/seeyuer/yingli/player/data/processing/Stage2RemuxContainerMeasurementTest.kt` 五例（证据 `docs/architecture/evidence/stage2/`），其中用例 1 证明 VP9+Opus 源确实能搬进 MP4。

**【重要限制 — 缺口 G27】** 能力表说「收」不等于「写得进去」。`Mp4Muxer` 写 VP9 轨道时 `Boxes.vpcCBox` 要求 csd-0（`checkArgument(!format.initializationData.isEmpty(), "csd-0 is not found in the format for vpcC box")`），而 ffmpeg 产出的 VP9 WebM 通常不带 CodecPrivate ⇒ 该路径对常见源**不可达**。详见 `docs/architecture/Organizing-Page-Function-Design.md` G27 与 §14.3.4。

**【两层能力来源的关系】** 编码层（上节第 1 道防线）用 `MediaCodecList.findEncoderForFormat(...)`，封装层（本节）用 `MuxerContainer` / `Muxer.Factory.getSupportedSampleMimeTypes(...)`。两层各自持有真源，但**不重复判断同一件事**：设备没有编码器的组合由编码层拒绝（`VIDEO_ENCODER_UNAVAILABLE` / `AUDIO_ENCODER_UNAVAILABLE`），容器收不下的组合由封装层拒绝（`CONTAINER_*_CODEC_UNSUPPORTED`）。

**【未实现 — 已接入但不可达（`Transformer` 侧）】** 三档预设与切片计划的目标容器全部是 `video/mp4`，**目前没有任何入口能选中 WebM/Ogg/ADTS 目标**，音频专用输出（无视频轨）同样不可达（引擎始终构造带视频轨的 `EditedMediaItemSequence`）。因此「经 `Transformer` 输出 `.webm` / `.ogg` / `.aac`」目前是**能力已接入但无产品路径**；「`setMuxerFactory` 是否真的被调用」也**看不见**（`Transformer` 不提供读回封装工厂的 getter，与 G1 的 `.setEncoderFactory(...)` 是同一类盲区）。**【步骤 8 更新】** `WebmMuxer` / `OggMuxer` / `AacMuxer` 的**写出行为**已有真机用例（在 `InAppRemuxEngine` 路径上：`WebmMuxer` 经用例 1 的 `video/webm` 源、以及 `Mp4Muxer` 经全部用例），不再属于「完全无真机用例」；`AacMuxer`（ADTS）仍无真机用例。

## 执行与发布

Media3 Transformer 在主线程 looper 执行，进度每 250ms 采样；取消、编码失败或异常会删除私有临时输出。任务复用 Phase 9 的持久处理状态机和原子产物提交协议。

成功状态只能由独立 verifier 产生。verifier 检查文件、视频/音频轨道、目标 codec、尺寸、时长容差、压缩样本可读性，并实际解码首个同步帧。任一检查失败时不提交媒体库。

verifier 另有一个**只读**观测字段 `OutputVerification.averageBitrateBitsPerSecond`（见上「G8 原目标不成立」），它**不参与 `valid`**。

## 轨道与字幕的硬边界

**【现状】** 只保留首个音轨；字幕不嵌入。

**【事实 — 不是缺陷】** 这是 **Media3 Transformer 的结构性边界**，不是可以靠改代码绕过的限制：

- `Transformer` 的参考文档原文：*"The output can contain at most one video track and one audio track. Other track types are ignored."*
- `Composition.sequences` 的官方原文：*"MediaItem instances from different sequences that are overlapping in time will be **mixed** in the output."* —— 多条 `EditedMediaItemSequence` 是**混音**，不是保留为多条独立轨道。

**⚠ 已证伪的旧表述**：本文档早期版本与设计初稿都写过「保留多音轨需要 `Composition.Builder.setSequences(...)` 构造多条 `EditedMediaItemSequence`」。**这条是错的**，已删除，不要再据此实现。

**【事实】** 平台 `MediaMuxer` 的「多视频/音频轨与 Metadata 轨仅 MP4 且 API 26+」限制**不适用于 Media3 的 `Mp4Muxer`**——后者是纯 Java 应用内多轨 muxer，轨道数不限，支持 `addTrackReference` 与 MP4-AT。因此**多音轨保留在「手写 `MediaExtractor` + `Mp4Muxer`」这条路上是可达的**（正是 `PlatformClipEngine.fastCut` 已在做的搬运循环），但那是**另一条引擎**，当前无产品决策要求它。

**【目标 — 产品边界】** 「保留轨道的能力不做，但『要求确认』的行为必须保留」。`domain/processing/ProcessingPlanContracts.kt` 的 `ProcessingChangeCode.requiresConfirmation()` 已为 `EXTRA_AUDIO_TRACKS_REMOVED` 与 `SUBTITLES_NOT_EMBEDDED` 返回 `true`（连同 `HDR_TO_SDR`、`FRAME_RATE_CAPPED`、两个 `*_CODEC_FALLBACK`），满足 Q498「不静默丢轨」，**这部分是正确的，不需要改**。等到出现真实需求时，按 Q543 以独立 `ProcessingEngine` 接入，不预先建抽象。

## 已知限制

- 只保留首个音轨；字幕不嵌入（见上「硬边界」）。
- **非 MP4 容器（WebM/Ogg/ADTS）能力已接入但无产品路径可达**，且其写出行为无真机用例（见上「封装层」）。
- **真实 HDR10/HLG 相机素材的 tone mapping 画质未验证**。能力探测与通路已在真机实测通过（G2 已修复，见上），但本机媒体库没有任何 HDR 视频，实测用的是容器层声明 HDR10 的合成源；多设备矩阵同样未覆盖（D9 只部分闭合）。
- 能力快照仍需在每台目标设备上跑真实 SDR/HDR、多轨、VFR、长任务和空间耗尽矩阵；**AOSP 模拟器通过不等于厂商真机通过**。
