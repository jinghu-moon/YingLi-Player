# Phase 10 切片项目契约

## 模型与编辑

片段使用 Long 毫秒精度并满足 `0 <= start < end <= sourceDuration`。项目保留明确顺序，片段 ID 唯一，片段间允许重叠。Add/Update/Duplicate/Move/Toggle/SelectAll/Delete/Undo 命令可重放，最多保留 50 个撤销快照；编辑后 500ms 节流保存到 Room v4。

时间轴只请求当前可见窗口，单次最多 30 帧；当前 UI 全窗口最多 12 帧。帧在 I/O 调度器提取为 320x180 JPEG，缓存目录以源 URI 的 SHA-256 命名，取消项目加载会停止后续请求。

## 快速与精确模式

| 模式 | 实现 | 时间边界 | 回退 |
| --- | --- | --- | --- |
| 快速 | MediaExtractor + MediaMuxer | 从前一个安全关键帧开始 | 不支持的轨道/加密样本提示使用精确模式 |
| 精确 | Media3 Transformer | 按请求区间重新编码/混流 | 编码失败返回稳定错误码，不静默降质 |

快速模式仅接受**目标封装器自己声明支持的**轨道（2026-10-09 阶段 2 步骤 8 起由 `ContainerMuxerFactory.supports(...)` 反查，不再是一张写死的表；见下）。所有输出都必须再次 probe；探测失败不得标记成功。

### 轨道白名单的来源（G11 / 阶段 2 步骤 7）

**【现状 — 缺陷】** `PlatformClipEngine.kt:207-213` 把可快速切片的轨道 mime 硬编码为 `FAST_MP4_MIME_TYPES = setOf("video/avc", "video/hevc", "video/mp4v-es", "audio/mp4a-latm", "audio/mpeg")`。该集合**不含 `video/av01`、`video/x-vnd.on2.vp9`、`audio/opus`、`audio/flac`、`audio/vorbis`、`audio/ac3`**，因此 WebM（VP9/Opus）或 AV1 源在快速模式下必然返回 `FAST_CONTAINER_UNSUPPORTED`，即使**手写 `MediaExtractor` + Media3 `Mp4Muxer` 这条路径完全能处理它们**（`Mp4Muxer` 是多轨、无 API 26 限制的应用内 muxer，见 `docs/architecture/phase-11-transcode-contract.md`「轨道与字幕的硬边界」）。

**【目标】** 删除硬编码集合，改为在探测阶段**反查实际 muxer 的能力**：平台路径用 `MediaCodecInfo.CodecCapabilities.getSupportedSampleMimeTypes()`，Media3 路径用 `Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES` / `SUPPORTED_AUDIO_SAMPLE_MIME_TYPES`（正是 `Muxer.Factory.getSupportedSampleMimeTypes()` 的现成来源）。**这是破坏性改动**：白名单从常量变成探测结果，`ClipCapabilities` 的构造点与所有依赖它的判断都要改。

**【目标】** 该反查与 Phase 11 的格式转换入口共用同一份能力来源，不再各写一份。

**【已实现 — 2026-10-09 阶段 2 步骤 8】** 硬编码集合与它所在的引擎一起被删除：`PlatformClipEngine.kt` 已由 `app/src/main/java/seeyuer/yingli/player/data/processing/InAppRemuxEngine.kt` 取代（封装层引擎 `ProcessingOperation.REMUX`，`MediaExtractor` 读样本 + Media3 `Muxer` 写样本），白名单的唯一判据是 `app/src/main/java/seeyuer/yingli/player/data/processing/muxer/ContainerMuxerFactory.kt` 的 `supports(trackType, sampleMimeType)`，它转手问的就是 `Muxer.Factory.getSupportedSampleMimeTypes(...)`——**库自己写样本前问的同一个方法**。Phase 11 的 `Media3ProcessingEngine` 用同一个类做 build 前校验，两份能力来源已合一。

**【已实测 — 同一台设备】** `app/src/androidTest/java/seeyuer/yingli/player/data/processing/Stage2RemuxContainerMeasurementTest.kt` 用例 1 证明 **VP9 + Opus 的 WebM 源确实能被搬进 MP4**（`Completed`，输出轨道 `[video/x-vnd.on2.vp9, audio/opus]`）；`docs/architecture/evidence/stage2/stage2-remux-case1-vp9-opus-into-mp4.json`。

**【重要限制 — 缺口 G27】** 能力表说「收」**不等于**「写得进去」：`Mp4Muxer` 写 VP9 轨道时 `Boxes.vpcCBox` 要求 csd-0，而 **ffmpeg 产出的 VP9 WebM 通常不带 CodecPrivate**（`refer/video-transcode-repos/FFmpeg/libavformat/matroskaenc.c:1219-1224` 的 `default:` 分支仅在 `extradata_size > 0` 时写 extradata），此时会抛 `IllegalArgumentException: csd-0 is not found in the format for vpcC box`。因此「WebM(VP9/Opus) 源可走快速路径」这条结论**只在源自带 VP9 CodecPrivate 时成立**；否则快速模式仍会失败（错误码为 `REMUX_FAILED`，不再是 `FAST_CONTAINER_UNSUPPORTED`）。补法是自行合成 csd-0（profile/level/bitDepth/chroma 缺省 0/10/8/0，颜色取 `Format.colorInfo`），本期未做。详见 `docs/architecture/Organizing-Page-Function-Design.md` §14.3.4 与 G27。

## 批量导出

只导出勾选片段，名称按稳定顺序生成且清理路径字符。同一项目的每个片段生成独立持久任务；部分失败不删除已成功输出，可从处理中心单独重试。

## 区间构造的唯一路径（阶段 5，2026-10-10）

`ClipSegment.forRange(id, startMillis, endMillis, name)` 与 `ClipProject.forRange(sourceMediaId, sourceLocationId, sourceDurationMillis, startMillis, endMillis, name, exportMode, preset, projectId, segmentId, nowEpochMillis)` 是「一个区间 → 一个片段/项目」的**唯一构造路径**（`ProcessingViewModel.createProject` / `addSegment` 与播放页的「导出 AB 区间」都调它）。工厂**不钳制、不加工**：区间非法由 `ClipSegment.init` / `ClipProject.init` 抛 `IllegalArgumentException`，不许悄悄互换端点或截到片尾。**AB 区间是会话临时状态，不得进入 `ClipProject`**：提交那一刻把 `(A, B)` 固化成 `ClipSegment`（§9.2 C1）。

`ClipExportCoordinator.enqueue(project)` **先 `save(project)` 再入队**：切片执行器只经 `clipRepository.project(id)` 取项目，先入队必然 `CLIP_PROJECT_NOT_FOUND`。

`ClipFastExportProbe` / `MuxerClipFastExportProbe` 回答「这个源能不能走快速模式」，判据是上面那张唯一的容器能力表（`MuxerContainer.Mp4.supports(...)`），**不新建 mime 白名单**；它回答的是「容器收不收这个样本格式」，**不回答**「这个设备真的能写得进去」（G27：VP9 缺 CodecPrivate 时能力表说可以、写样本时才抛）。

