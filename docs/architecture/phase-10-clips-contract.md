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

## 批量导出

只导出勾选片段，名称按稳定顺序生成且清理路径字符。同一项目的每个片段生成独立持久任务；部分失败不删除已成功输出，可从处理中心单独重试。

