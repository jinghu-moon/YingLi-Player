# Phase 4 播放契约

## 架构边界

- `domain.playback` 只包含 Kotlin 模型、状态归约器、续播策略、进度节流与错误分类，不依赖 Android 或 Media3。
- `YingLiPlaybackService` 是唯一 `ExoPlayer` 与 `MediaSession` 所有者；Activity、ViewModel 和 Composable 均不创建 Player。
- 应用级 `Media3PlaybackController` 连接 Service，将 Media3 事件映射为只读 `StateFlow<PlaybackState>`。断连时命令明确返回 `NOT_CONNECTED`。
- `RoomPlaybackRepository` 负责逻辑媒体 ID 到可用位置 URI 的解析和进度落库。URI 不进入 `PlaybackRequest`，也不写日志。
- `PlayerScreen` 只消费领域状态和命令回调；`Media3VideoSurface` 是隔离在 app 边界的 Surface 适配器。

## 状态图

```text
Idle --Prepare--> Preparing --Ready--> Ready --Play--> Playing
                              |                    |       |
                              |                    |       +--Pause--> Paused --Play--+
                              |                    |       |                            |
                              |                    |       +--End----> Ended --Replay--+
                              |                    |
                              +--Fail------------> Failed --Retry--> Preparing

Ready/Playing/Paused --Seek--> same state with clamped timeline
any active state --Stop--> Idle
illegal or duplicate command --> state unchanged / explicit rejection
```

## 会话生命周期

```text
Application creates one Media3PlaybackController
        |
        +-- SessionToken connects --> MediaSessionService
                                      |
                                      +-- onCreate: ExoPlayer + MediaSession + notification session activity
                                      +-- controller reconnect: reuse the same session/player
                                      +-- pause/end/stop/task removed: force progress snapshot
                                      +-- playing: progress snapshot at most every 5 seconds
                                      +-- onDestroy: release session/player and complete final async write
```

Service 在 Manifest 中显式设置 `exported=false` 与 `foregroundServiceType=mediaPlayback`。会话返回 Activity 的 `PendingIntent` 使用 `FLAG_IMMUTABLE`。

## 续播与结束规则

- 常规续播位置为保存位置前 3 秒，小于 3 秒钳制为 0。
- 已完成、剩余不超过 5 秒或进度达到 95% 时从头播放。
- 默认不自动播放下一项；结束页的“下一项”在无候选时禁用。
- 无痕请求从不写入进度。

## 错误矩阵

| 技术信号 | 领域分类 | 用户动作 | 日志码 |
| --- | --- | --- | --- |
| 无访问权限 | `PERMISSION` | 重新授权 | `PLAYBACK_ACCESS_DENIED` |
| 文件不存在 | `FILE_MISSING` | 重新扫描/定位 | `PLAYBACK_SOURCE_NOT_FOUND` |
| 容器不支持 | `UNSUPPORTED_CONTAINER` | 查看兼容说明/返回 | `PLAYBACK_CONTAINER_UNSUPPORTED` |
| 解码器不可用 | `UNSUPPORTED_DECODER` | 查看兼容说明/返回 | `PLAYBACK_DECODER_UNAVAILABLE` |
| 媒体格式损坏 | `CORRUPT_MEDIA` | 重试 | `PLAYBACK_MEDIA_CORRUPT` |
| 未分类错误 | `UNKNOWN` | 重试 | `PLAYBACK_UNKNOWN` |

日志仅记录稳定诊断码与 Media3 数字错误码，不记录 URI、路径、查询参数或异常消息。
