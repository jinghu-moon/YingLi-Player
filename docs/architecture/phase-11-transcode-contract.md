# Phase 11 转码契约

## 固定预设

| ID | 版本 | 视频/音频 | 最大长边 | 视频码率 | 音频码率 |
| --- | --- | --- | ---: | ---: | ---: |
| `compatible_mp4` | 1 | AVC/AAC in MP4 | 1920 | 8 Mbps | 192 Kbps |
| `balanced_mp4` | 1 | AVC/AAC in MP4 | 1920 | 5 Mbps | 160 Kbps |
| `space_saver_mp4` | 1 | AVC/AAC in MP4 | 1280 | 2.5 Mbps | 128 Kbps |

UI 不接受任意 codec、码率或容器参数。规划器取源尺寸、预设上限和设备编码器能力的交集，输出宽高强制为偶数。空间估算按目标总码率乘 1.15，并额外保留 128 MiB；空间不足不创建任务。

## 能力与降级

设备能力由 `MediaCodecList` 探测并缓存，源轨道由 `MediaExtractor` 探测。厂商 codec 查询异常只产生 `CODEC_CAPABILITY_ERROR`，不会让应用崩溃。当前不能可靠证明 HDR 编码与 tone mapping 能力，因此编码器快照一律不宣称 HDR 支持。

以下变化必须在入队前显式确认：丢弃额外音轨、不嵌入字幕、HDR 转 SDR、帧率受编码器限制。当前 Media3 路径无法可靠设置 HDR tone mapping，包含 `HDR_TO_SDR` 的计划在执行时返回 `HDR_TONE_MAPPING_UNAVAILABLE`，不会输出错误标色的视频。

## 执行与发布

Media3 Transformer 在主线程 looper 执行，进度每 250ms 采样；取消、编码失败或异常会删除私有临时输出。任务复用 Phase 9 的持久处理状态机和原子产物提交协议。

成功状态只能由独立 verifier 产生。verifier 检查文件、视频/音频轨道、目标 codec、尺寸、时长容差、压缩样本可读性，并实际解码首个同步帧。任一检查失败时不提交媒体库。

当前限制：只保留首个音轨；字幕不嵌入；HDR 转换关闭；能力快照仍需在每台目标设备上跑真实 SDR/HDR、多轨、VFR、长任务和空间耗尽矩阵。
