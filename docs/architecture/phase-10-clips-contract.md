# Phase 10 切片项目契约

## 模型与编辑

片段使用 Long 毫秒精度并满足 `0 <= start < end <= sourceDuration`。项目保留明确顺序，片段 ID 唯一，片段间允许重叠。Add/Update/Duplicate/Move/Toggle/SelectAll/Delete/Undo 命令可重放，最多保留 50 个撤销快照；编辑后 500ms 节流保存到 Room v4。

时间轴只请求当前可见窗口，单次最多 30 帧；当前 UI 全窗口最多 12 帧。帧在 I/O 调度器提取为 320x180 JPEG，缓存目录以源 URI 的 SHA-256 命名，取消项目加载会停止后续请求。

## 快速与精确模式

| 模式 | 实现 | 时间边界 | 回退 |
| --- | --- | --- | --- |
| 快速 | MediaExtractor + MediaMuxer | 从前一个安全关键帧开始 | 不支持的轨道/加密样本提示使用精确模式 |
| 精确 | Media3 Transformer | 按请求区间重新编码/混流 | 编码失败返回稳定错误码，不静默降质 |

快速模式仅接受平台 MP4 Muxer 支持的 AVC/HEVC/MPEG-4 Video/AAC/MP3 轨道。所有输出都必须再次 probe；探测失败不得标记成功。

### 轨道白名单的来源（G11 / 阶段 2 步骤 7）

**【现状 — 缺陷】** `PlatformClipEngine.kt:207-213` 把可快速切片的轨道 mime 硬编码为 `FAST_MP4_MIME_TYPES = setOf("video/avc", "video/hevc", "video/mp4v-es", "audio/mp4a-latm", "audio/mpeg")`。该集合**不含 `video/av01`、`video/x-vnd.on2.vp9`、`audio/opus`、`audio/flac`、`audio/vorbis`、`audio/ac3`**，因此 WebM（VP9/Opus）或 AV1 源在快速模式下必然返回 `FAST_CONTAINER_UNSUPPORTED`，即使**手写 `MediaExtractor` + Media3 `Mp4Muxer` 这条路径完全能处理它们**（`Mp4Muxer` 是多轨、无 API 26 限制的应用内 muxer，见 `docs/architecture/phase-11-transcode-contract.md`「轨道与字幕的硬边界」）。

**【目标】** 删除硬编码集合，改为在探测阶段**反查实际 muxer 的能力**：平台路径用 `MediaCodecInfo.CodecCapabilities.getSupportedSampleMimeTypes()`，Media3 路径用 `Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES` / `SUPPORTED_AUDIO_SAMPLE_MIME_TYPES`（正是 `Muxer.Factory.getSupportedSampleMimeTypes()` 的现成来源）。**这是破坏性改动**：白名单从常量变成探测结果，`ClipCapabilities` 的构造点与所有依赖它的判断都要改。

**【目标】** 该反查与 Phase 11 的格式转换入口共用同一份能力来源，不再各写一份。

## 批量导出

只导出勾选片段，名称按稳定顺序生成且清理路径字符。同一项目的每个片段生成独立持久任务；部分失败不删除已成功输出，可从处理中心单独重试。

