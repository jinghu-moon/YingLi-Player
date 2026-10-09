# 参考仓库调研报告与改造方案

> 调研对象：`refer/video-deduplication-repos/`（7 个仓库）与 `refer/video-transcode-repos/`（10 个仓库）。
> 调研方式：**逐仓库读源码**，结论均附 `文件路径:行号` 证据；未运行任何仓库的代码，所有性能数字若来自源码注释或 issue 引用则明确标注「未实测」。
> 本文只做调研与方案设计，**不修改代码**。
>
> **约束前提**（来自 `AGENTS.md` 与本项目架构）：Android 12+（minSdk 31）、Kotlin + Compose + Room + Media3 1.10.1；**禁止 FFmpeg / Rust / JNI / 向量库 / 音频指纹 / 深度学习**；编码只能用 MediaCodec 与 Media3 Transformer，封装只能用 `MediaMuxer` 或 Media3 `Muxer`；项目处于开发期、未发布，**鼓励破坏性重构、不考虑向后兼容**，但每个改动必须配「改前 / 改后」测试。

---

## 0. 调研范围与方法

### 0.1 仓库清单与规模

实测（文件数 / 总字节 / 代码文件数 / 代码字节，均排除 `.git`）：

| 仓库 | 文件 | 总字节 | 代码文件 | 代码字节 |
| --- | ---: | ---: | ---: | ---: |
| `video-deduplication-repos/dublette` | 57 | 631,594 | 13 | 109,685 |
| `video-deduplication-repos/duplicates-detector-oss` | 337 | 8,297,479 | 288 | 4,638,154 |
| `video-deduplication-repos/MediaDedupe` | 17 | 558,780 | 6 | 161,548 |
| `video-deduplication-repos/video-duplicate-finder` | 18 | 271,117 | 8 | 63,112 |
| `video-deduplication-repos/VideoCull` | 341 | 73,920,548 | 138 | 940,873 |
| `video-deduplication-repos/videoduplicatefinder` | 505 | 4,541,314 | 413 | 2,690,425 |
| `video-deduplication-repos/videohash` | 44 | 1,009,511 | 18 | 67,228 |
| `video-transcode-repos/ab-av1` | 36 | 271,887 | 25 | 203,554 |
| `video-transcode-repos/compressO` | 384 | 711,503,084 | 71 | 299,576 |
| `video-transcode-repos/FastFlix` | 503 | 31,802,506 | 239 | 1,678,550 |
| `video-transcode-repos/FFmpeg` | 10,842 | 95,102,149 | 4,743 | 73,564,523 |
| `video-transcode-repos/filmcompress` | 15 | 32,731 | 4 | 14,087 |
| `video-transcode-repos/HandBrake` | 2,368 | 33,416,805 | 981 | 9,345,612 |
| `video-transcode-repos/lossless-cut` | 327 | 9,776,297 | 105 | 518,012 |
| `video-transcode-repos/shutter-encoder` | 198 | 27,845,235 | 101 | 3,002,900 |
| `video-transcode-repos/staxrip` | 333 | 7,538,718 | **145**（`*.vb`，实测计数） | **4,304,098**（`*.vb`） |
| `video-transcode-repos/VidCoder` | 1,140 | 128,115,652 | 460 | 2,002,344 |

**许可实测核对**（逐仓库读取 `LICENSE`/`COPYING`/`package.json`/`Cargo.toml`，**本节结论以实测为准，不采信简介文档**）：

| 仓库 | 实测许可 | 与简介/首轮记录的差异 |
| --- | --- | --- |
| `dublette` | **AGPL-3.0-or-later**（`Cargo.toml`） | 简介标为 MIT → **已更正** |
| `duplicates-detector-oss` | MIT | 一致 |
| `MediaDedupe` | MIT | 一致 |
| `video-duplicate-finder` | MIT | 一致 |
| `VideoCull` | **AGPL-3.0-only**（`package.json`） | 简介未标 |
| `videoduplicatefinder` | **AGPL-3.0** | 简介未标 |
| `videohash` | MIT | 一致 |
| `ab-av1` | MIT | 一致 |
| `compressO` | **AGPL-3.0-only**（`package.json` + `LICENSE`） | 简介标为 LGPLv2.1 → **已更正** |
| `FastFlix` | MIT | 一致 |
| `FFmpeg` | LGPL-2.1+（多数文件） | 一致 |
| `filmcompress` | MIT | 一致 |
| `HandBrake` | **GPL-2.0-or-later**（`COPYING` 为 GPLv2，源码头声明 "or any later version"） | 简介只写 GPL |
| `lossless-cut` | **GPL-2.0-only**（`package.json`；**全仓无 "any later version"**） | 简介写 GPL-2.0 → 补全为 **only** |
| `shutter-encoder` | **GPL-3.0-or-later**（`LICENSE.txt` 为 GPLv3，源码头声明 "or any later version"） | 子代理曾报 GPL-2.0-or-later → **已更正** |
| `staxrip` | MIT | 一致 |
| `VidCoder` | **GPL-2.0-or-later**（`License.txt` + 源码头） | 简介标为 MIT → **已更正** |

**许可兼容性结论**：本项目 `LICENSE:1-2` 实测为 **AGPL-3.0**。
- **可直接参考实现**：MIT 组（`duplicates-detector-oss` / `MediaDedupe` / `video-duplicate-finder` / `videohash` / `ab-av1` / `FastFlix` / `filmcompress` / `staxrip`）。
- **同为 AGPL-3.0，可参考实现**：`dublette` / `VideoCull` / `videoduplicatefinder` / `compressO`。
- **GPL-3.0-or-later，与 AGPL-3.0 兼容**：`shutter-encoder`（GPLv3 与 AGPLv3 经 GPLv3 §13 / AGPL §13 可组合）。
- **GPL-2.0-or-later，可经 GPLv3 升级路径兼容**：`HandBrake` / `VidCoder`。
- **唯一许可不兼容**：**`lossless-cut` 是 GPL-2.0-only**（`package.json` 显式 `GPL-2.0-only`，且全仓无 "any later version"）⇒ 其代码**不能**并入 AGPL-3.0 项目。`§2.4` 中所有 lossless-cut 结论**只可作为设计思想参考，不得复制代码**。

> 无论许可是否兼容，本文的可借鉴项一律只移植**算法、数据模型与流程**，不复制实现代码；三份参考实现（`shutter-encoder` / `compressO` / `staxrip`）的**运行时前提都是外部或捆绑的 ffmpeg 二进制**，这部分对本项目仍然不适用（FFmpeg No-Go 不变）。

### 0.2 证据强度分级

本文所有结论标注证据强度：

- **【源码】**：直接读到源码与行号，可复现。
- **【实测】**：主代理亲自执行的命令或统计（文件计数、字节数、逐仓库许可读取），结论优先于一切转述。
- **【注释】**：来自源码注释或 README 自述，**未经实测**。
- **【未核实】**：来自用户提供的简介文档（`视频去重项目简介.md`、`视频压缩转码项目简介.md`），尚未逐条核对源码。**本文凡与简介冲突处，一律以【实测】或【源码】为准，并在 §0.1 许可表与各节「规模更正」中标出差异。**

---

## 1. 去重仓库

### 1.1 结论汇总

| 仓库 | 定位 | 判定核心 | 对本项目的价值 |
| --- | --- | --- | --- |
| `videoduplicatefinder`（0x90d） | .NET 10 + Avalonia，AGPL-3.0，413 文件 | 32×32 灰度逐像素 + pHash / ONNX / 音频指纹旁路 | **可复用 14 条**，最有价值 |
| `videohash` | Python，MIT | 1 fps 全片 → 拼贴 → 小波哈希 → 64 位 | 只可复用汉明距离与 64 位编解码 |
| `MediaDedupe` | Python，MIT | pHash（DCT 8×8）+ 5 帧 | 阈值分级注释是**唯一可采信的阈值语义来源** |
| `video-duplicate-finder`（hclivess） | Python + PySide6，MIT | dHash（9×8）× 12 帧 | dHash 可 1:1 重写为 Kotlin |
| `duplicates-detector-oss` | Python + SwiftUI GUI，MIT | 加权 comparator 求和 + pair 为主单位 | 「交互复核 + 可回读 JSON + 撤销脚本」模板，见 §1.4.1 |
| `VideoCull` | Electron + React，AGPL-3.0-only | 多帧 pHash/灰度 + 时长桶化 | **价值最高的去重参考**：链式误合并防护、决策与删除分离，见 §1.4.2 |
| `dublette` | Rust CLI，AGPL-3.0-or-later | dHash（DoubleGradient）单帧 | 「CLI 契约与 ADR 纪律」模板；分组是**反面教材**，见 §1.4.3 |

**共同结论：7 个仓库无一提供 precision/recall 曲线。** 所有阈值都是硬编码或产品预设，**提供的是算法候选，不是可采信阈值**。这直接支撑 `ADR-DEDUP-003`（相似视频维持关闭）。

### 1.2 videoduplicatefinder（0x90d）

**定位**：.NET 10 + Avalonia，**AGPL-3.0**（传染性，只可借鉴不可抄），413 文件 / 2.7 MB 代码，核心 `VDF.Core/ScanEngine.cs` 3133 行；四个前端（Core / GUI / CLI / Web）共享引擎；依赖 `FFmpeg.AutoGen 8.1.0`、`MemoryPack`、`Microsoft.ML.OnnxRuntime.Managed 1.23.2`。
**关键认知**：它**不是「哈希去重」**，而是 **32×32 灰度帧逐像素比较 + 可选 pHash / ONNX AI embedding / 音频指纹三条旁路**。

#### 扫描管线与缓存【源码】

- 无 SQL：单文件 `ScannedFiles.db` + 内存 `HashSet<FileEntry>`（`VDF.Core/Utils/DatabaseUtils.cs:41-43`）；写入走临时文件再替换 = 原子保存（`:44-56`）；格式版本 `"VDFDB001"`（整图）/`"VDFDB002"`（流式，每条目长度前缀，压峰值内存）；损坏处理：坏 temp → 删除重试，坏正式文件 → 改名 `_DAMAGED.db` 隔离，**绝不静默换成空库**（`:59-123`）。
- **缓存键 = path + FileSize + DateCreated/DateModified + OsHash**（`FileEntry.cs:83-90`，OsHash = size + 头尾 64 KiB 校验和）。失效规则 `ScanEngine.cs:756-780`：size 变 → 全部重算；size 不变但时间戳变 → **必须 OsHash 证明字节未变才复用缓存**；注释原话 "keep the cached analysis when the content fingerprint PROVES the bytes unchanged"。
- 移动文件重链 `ScanEngine.cs:787-824`：同 size 桶内找路径已不存在且 OsHash 相等的候选，**0 或 >1 个候选直接放弃**（歧义绝不复用错数据）。
- 增量三种捷径：缓存完整时完全不解码视频只补音频（`:1066-1107`）；`EntryFlags.ThumbnailError` 失败记忆（`:1038-1041`）；文件不存在且缓存不完整 → 标 invalid，**绝不启动 ffprobe**（`:1109-1118`）。
- **避免全量两两比较**（`:1651-2037`）：只比上三角（`:1887-1889`）；按时长整数秒分桶 `bucketSizeSeconds = 1`（`:1721`）；`ComparePair` 过滤器**便宜→贵**（时长容差 → `PassesFolderMatchGate` → 灰度/pHash/AI → **硬链接检查放最后**，`:1855-1861`）。

#### 视觉相似度【源码】

- 采样点 `ScanEngine.cs:512-517` `BuildSamplePositions`：等距且**绝不含首末帧**，`positionCounter += 1.0F/(thumbnailCount+1)`（count=1→{0.5}；count=4→{0.2,0.4,0.6,0.8}），是扫描/缩略图/诊断/前端的**唯一真源**；默认 `ThumbnailCount = 1`。
- 帧归一化：ffmpeg `scale=32:32:flags=bicubic,format=gray`；**旋转在缩放之后施加**；每帧固定 **1024 字节**（`GrayBytesUtils.cs:25-27` `Side=32; GrayByteValueLength=1024`）。
- 相似度 = 逐像素灰度绝对差之和 `/1024/256`（`GrayBytesUtils.cs:156-192`）；判定 `differenceLimit = (1 - Percent/100) * 1024`，逐帧累加**超预算即提前退出**（`ScanEngine.cs:1634-1649`）；默认 `Percent = 96f` → 平均每像素灰度差 ≤ 0.04×255 ≈ **10.2**。
- 黑/白像素屏蔽 `BlackPixelLimit = 0x20`、`WhitePixelLimit = 0xF0`（`GrayBytesUtils.cs:37-39, 72-154`）；暗帧守卫：≤0x20 像素占比 ≥80% 判不可用（`:42-50`）。
- **pHash 旁路**：同一份 32×32 灰度，只算 8×8 低频 DCT（省 ~6.4× 乘法），阈值取 64 个系数**中位数**（`VDF.Core/pHash/PerceptualHash.cs:23-66`）；Hamming 容忍 `floor((1-percent)*64)`（percent=0.96 → 2 位）。
- **多帧投票 quorum**：`requiredMatches = ceil(sampleCount * PHashRequiredMatchingSampleRatio)`，默认 0.6（`ScanEngine.cs:1587-1632`）。
- 防误判：时长容差 = 时长×20% 再 min/max 夹紧；同目录闸门 `PassesFolderMatchGate`（`:1357`）；**合并前用代表元复验**（`:1743-1805`，防桥接对造成菊花链误合并）；扫描后 **daisy-chain 校验**。
- `CombineGrayscaleAndPHash`：两算法都跑、任一命中即重复，都命中取更小 difference。

#### 部分片段检测【源码】

- 音频指纹 `uint[]` 每秒一个 uint（`FileEntry.cs:78-82`）；`SlidingWindowCompare` 滑窗 + 比特预算提前退出 `maxAllowedBits = (int)((1f - max(bestSim,minSim)) * lenS*32)`（`ScanEngine.cs:2414-2441`）；`HammingDistance` 三级降级 Vector256/NEON/标量（`:2451-2498`）。
- 候选剪枝（`:2045-2162`）：按时长**降序**，`ratio >= 0.95 continue`、`ratio < PartialClipMinRatio(0.10) break`、`pairsChecked` 用 `long`（162k 视频约 130 亿对，int 会溢出）【注释，未实测】。
- 分组：每个 clip **只归第一个（最长的）** source（`:2388-2404`）。
- 视觉闸门「先分配后验证」（`:2202-2231`，注释称 17k 候选只有 5 个通过）【注释，未实测】。
- 验证帧选择（`:2299-2315`）：`≥9s → {0.25,0.50,0.75}`、`≥3s → {0.33,0.66}`、否则 `{0.5}`，丢弃越界，**刻意避开窗口两端**；`clipTimes.Count == 0` 直接 return false。
- `PartialClipOffset` 随结果持久化供 UI 时间轴跳转（`:2169-2189`）。
- daisy-chain 校验（`:2518-2607` + `DaisyChainSplitter.cs`）：上三角**位矩阵** `PairBitMatrix`（n²/16 字节，替代 `bool[n,n]`，注释称 46341 成员直接 OOM）【注释，未实测】，迭代剪掉「与剩余成员相似数 < 半数」的成员，就地更新度数为 O(n²)（`:259-276`）；组太大（>1 GiB 或 1/8 可用内存）则 `Skipped` + 告警「阈值太松」（`:170-191`）。

#### AI 与音频指纹【源码】

- `dinov2-small-int8.onnx`（`AI/AiComponents.cs:45`）+ SHA256 校验，ONNX Runtime 1.23.2 按 RID 下载 native（**不打包**，CLI 提示约 100 MB）；224×224 RGB24 ImageNet 归一化，输出 L2 归一化后**量化成 unit-vector 字节**；**embedding 故意不放 FileEntry**（会永久膨胀主库与常驻内存），改存 `UnionEmbeddingStore` sidecar，**按 path 索引、用 size+mtime 校验**（`FileEntry.cs:91-94`）。
- AI 部分片段检测（`ScanEngine_AiPartial.cs`）：密集关键帧间隔 `Math.Max(Math.Clamp(duration/60, 5, 15), duration/400)` 秒、上限 400 帧；匹配**不是逐帧相似度**而是「**多个命中一致指向同一时间偏移**」（cos ≥ 阈值记 hit 并累计 `offset = s*interval - clipTime`，要求 ≥4 个 hit、取 offset 中位数、只保留 ±30s 内）；注释标定依据「真匹配的重编码片段 cos 在 0.89-0.95，与同场景噪声逐帧无法区分」；阈值 `AiPartialHitPercent = 89f`。
- 音频指纹是**真 Chromaprint 算法纯 C# 重写**（派生自 AcoustID.NET，LGPL 2.1，`Chromaprint/ChromaContext.cs:16`）：mono 16-bit PCM @ **11025 Hz**，`FrameHop = 1365` → ~8.07 fps，chroma→时间 FIR 平滑→归一化→量化→**按 1 秒桶多数投票**成每秒 1 个 uint。
- **对本项目的判定**：音频侧算法纯数学 Kotlin 可写，但输入 PCM 仍需解码器，且本项目约束禁止音频指纹；AI 侧必须 onnxruntime native + 100MB 模型，与禁 JNI/禁深度学习直接冲突。

#### UI / 交互【源码】

- 组 = `Guid GroupId`；徽章来自 `DuplicateFlags`（`Flipped/PartialClip/AiMatched/GrayscaleMatched/PHashMatched`）。
- **「保留哪一份」= 有序准则表 + 近似并列**：`QualityRanker.PickKeeperWithReason` 按优先级走准则，**并列时只把并列者带入下一轮**，返回 `(Keeper, DecidedBy)` 供 BEST 徽章 tooltip（`VDF.Core/Utils/QualityRanker.cs:60-84`）；`Criterion<T>` 有 `Ascending`（小者优）与 `NearTie`（近似并列），注释动因：duration/bitrate 这类近连续值会让「长 200ms 的文件」打败「分辨率翻倍的文件」。准则表顺序（用户可重排 + 可禁用，新准则自动追加兜底）：`Duration → Resolution → Bitrate → FPS → Bits per pixel → Audio Bitrate → Size → SizeLarger`（`VDF.GUI/ViewModels/MainWindowVM_Utils.cs:64-93`）；`BitsPerPixel = BitRateKbs*1000/(W*H*Fps)`。
- 自动勾选命令 `CheckWhenIdentical` / `CheckWhenIdenticalButSize` / `CheckOldest` / `CheckNewest` / `CheckLowestQuality`（把 keeper 保持不勾）/ `CheckMissingFiles`（`MainWindowVM_Selection.cs:140-208`）；CLI 侧 `--strategy lowest-quality|smallest-file|shortest-duration|worst-resolution|100-percent-only`，`100-percent-only` 时整组只要有相似度 <100 就跳过（`VDF.CLI/Actions/DeletionStrategy.cs:21-117`）。
- 表达式选择用 DynamicExpresso，**禁用赋值**、只用 PrimitiveTypes 防恶意设置文件；「组内全部成员都匹配」的组**只问一次**用户（`MainWindowVM_Selection.cs:97-113`）。
- 部分重复的时间轴核实：`PartialClipOffset` 持久化后 Web 端把 clip 与 source **都放到 source 的时间轴上、展示共享的那一段**（`VDF.Web/Services/FramePositions.cs:28-33, 88-102`）；部分重复对至少给 3 帧（25/50/75%）。
- 删除始终显式：分块回收站 `RecycleChunkSize = 50`（注释：整批 SHFileOperation 会让进度停在 0/N）；`Outcome { Deleted, AlreadyRecycled, MissingEntryOnly }` 三态区分「真的删了」「批量已回收」「文件本就不在（整批意味着盘不可用，必须提示用户）」；删完**再验证文件确实不在**否则抛异常（`DiskDeletion.cs:33-85`）。

#### 工程实践【源码】

- **两套并发度分开**——媒体读取用 `Settings.MaxDegreeOfParallelism`（HDD 默认 2），CPU 匹配用 `CalculateMatchingParallelism`（≥8 核留 2 核、不超过 80% 核数）（`ScanEngine.cs:154-179`）。
- 进度用不可变 `record ScanProgressSnapshot` + `Interlocked.CompareExchange` 节流 + `Heartbeat()` 无进展时重发快照防「看起来卡死」（`ScanProgress.cs:25-93`）；ETA = `elapsed*remaining/(processed+1)` 负值夹 0；**驱动器进度按字节而非文件数**。
- **暂停 ≠ 取消**：`PauseTokenSource` 用 `ManualResetEventSlim`（set=运行/reset=暂停），worker 阻塞在事件上而非 sleep 轮询；并行循环体用不抛异常的 `TryWaitWhilePaused`（`PauseTokenSource.cs:19-59`）。
- **原生崩溃恢复**：`ScanCrashJournal` 每个工作线程解码前写 `scan-inflight-<threadId>.txt`（内容 `phase|path`），处理完清空，`AppDomain.ProcessExit` 钩子正常关闭时清空全部；下次扫描 `CollectLeftovers()` 读到的非空面包屑 = 上轮崩溃时正在处理的文件，**标记为失败从而跳过**（`ScanCrashJournal.cs:19-147`）。
- 每 N 分钟数据库检查点（默认 5，0=仅阶段边界）；暂停时显式 flush；`AbortScanOnError` 出错后**不保存数据库**（内存库可能还没加载完，保存会把好库覆盖成空库）。
- **一次性自愈标记** `ScannedFiles.fpheal1` 侧车文件，**每个库自动重试恰好一次**（`DatabaseUtils.cs:125-140`）。
- ffmpeg 退出码分类、原生库缺失快速失败（`PrepareSearch` 先抛 `FFNotFoundException`）。

#### CLI/GUI 分层【源码】

- VDF.Core 承载全部逻辑，GUI/CLI/Web 只是前端，`InternalsVisibleTo` 暴露 internal；无 DI，靠静态 `Settings` + 静态 `DatabaseUtils`。
- CLI 用适配器把 `async void` + 事件 API 桥成可 await 的 Task（`TaskCompletionSource` + `ct.Register(() => { engine.Stop(); tcs.TrySetCanceled(); })`），并显式 `StartSearch(searchAndCompare: false)` 避免重复比较 + 两个并发 `SaveDatabase` 在 temp 文件上相撞（`VDF.CLI/Commands/ScanRunner.cs:23-95`）。
- `SharedOptions.ApplyToSettings` 把 CLI 开关直接写进 GUI 用的同一个 `Settings` 类。

#### 可复用 / 可借鉴 / 不采纳

**可复用（Android/Kotlin 可直接借用，14 条）**：① 32×32 灰度帧 + 逐像素绝对差百分比（1024 字节/帧）；② 采样点单一真源 + 等距且不含首末帧；③ 64 位 pHash（32×32 → 8×8 低频 DCT → 中位数）+ Hamming 判定 + **多帧投票 quorum ≥0.6**；④ 缓存键 = path+size+mtime+内容校验和，且「时间戳变了必须内容指纹证明未变才复用」；⑤ 时长分桶 + 上三角 + 便宜→贵过滤器顺序；⑥ daisy-chain 多数剪枝（就地更新度数的 O(n²)）+ 合并前代表元复验；⑦ 比特预算滑窗 `maxAllowedBits = (1-bestSim)*lenS*32`；⑧ 部分片段剪枝规则 + 「先分配后验证、失败回退下一候选」多轮收敛；⑨ 分档取验证帧且丢弃贴近两端的点；⑩ 进度/并发/暂停三件套；⑪ 数据库原子保存 + 损坏隔离 + 一次性自愈标记；⑫ 崩溃面包屑；⑬ 质量准则排序器（优先级 + 并列进入下一轮 + `Ascending`/`NearTie`）；⑭ 删除安全（分块回收 + `Outcome` 三态 + 删后验证 + 「整批都不在 = 盘不可用」必须提示）。

**可借鉴**：音频指纹的「降采样到每秒一个 token 再做滑窗」时间轴压缩思想；AI 部分片段的**投票式偏移一致性**判别器（可套在灰度帧上，不需要模型）；sidecar 缓存大对象、主库只存身份 + 轻量派生数据；结果列表结构（组头 `3 files · 1.9 GB · save up to 1.2 GB` + 成员行 + BEST 徽章）；「组内全匹配 → 只问一次」；流式序列化避免峰值内存。

**不采纳**：FFmpeg（FFmpeg.AutoGen native + ffmpeg/ffprobe 进程 + swscale）；ONNX/DINOv2；音频指纹 Chromaprint；文件级二进制数据库 + 全库 HashSet 常驻内存（可保留「身份字段 vs 派生缓存字段分离」「原子保存」「损坏隔离」这些**模式**）；巨型组 n² 位矩阵（移动端应压到几十 MB 并对超大组 `Skipped` + 提示提高阈值）；DynamicExpresso 动态求值；16×16 旧灰度格式兼容逻辑（`AGENTS.md` 明确不考虑向后兼容）。

**不确定**：未运行任何代码；所有性能数字（162k 视频约 130 亿对、17k 候选仅 5 个通过、46341 成员 OOM、320k 图片库）**均来自源码注释与 issue 引用，未实测**；未读 `ScanEngine_Diagnostic.cs`、`DriveScanPlanner.cs`、`FfmpegErrorClassifier.cs`、`HardLinkUtils.cs`、`GroupBlacklistFilter.cs`；「Android 上 32×32 灰度帧提取」的实际成本未验证（`getFrameAtTime` 只给关键帧）；`Percent = 96` 与 `PHashRequiredMatchingSampleRatio = 0.6` 的误判/漏判率未验证。

### 1.3 videohash / MediaDedupe / hclivess·video-duplicate-finder

三个仓库横向对比（行号为仓库内相对路径）：

| 维度 | videohash | MediaDedupe | hclivess |
| --- | --- | --- | --- |
| 特征 | whash（拼贴）XOR 主色模板，64 位 | pHash（DCT 8×8），64 位；视频 5 帧 | dHash（9×8），64 位 × 12 帧 |
| 采样 | 1 fps 全片 | 时长 10/30/50/70/90% | 首尾 5% 外均匀 N 帧 |
| 判据 | 汉明 ≤10/64（15%） | 图片 ≤8；视频 4/5 帧 ≤10 + 时长 ±3 s | 平均相似度 ≥90%，时长 ±3% |
| 阈值依据 | 无 | 仅图片侧有语义分级注释 | 三档产品预设，无实测 |
| precision/recall | 无 | 无 | 无 |
| 片头/片尾 | 无 | 固定首尾 30 s 窗口 all-pairs（非检测） | 无（主动丢弃首尾 5%） |
| 缓存 | 无 | SQLite，mtime+size(+intensive_n) | SQLite，size+mtime+frames |
| 成本/视频 | 极高（149 Mpx 常驻） | 中（5–7 次 ffmpeg 进程） | 低（12 帧解码 + 64 位比较） |
| 聚簇 | 两两 | 贪心（非传递） | union-find |

**videohash**（MIT，纯 Python，10 模块 67 KB）【源码】：
- `videohash/videohash/videohash.py:106` `bits_in_hash=64`；默认 `frame_interval=1`（`:40`）→ N=ceil(时长秒) 张 144×144 JPEG；`collagemaker.py:142-148` `scale = 1024/(per_row*144)`；`imagehash.whash`（v4.3.2）做 Haar 小波：抹掉最低频 LL（`coeffs[0] *= 0`）→ 重建 → 再分解取 8×8 LL 子带 → **与中位数比较**；主色位表用 `tilemaker.py:176-187` 切成 64 块（`tile_w=18N`, `tile_h=18`）；`videohash.py:582-647` 硬编码 64 项模板（下标 0–15=`r`、16–31=`g`、32–47=`b`、48–63=`l`）；最终位表 = 主色位 XOR 小波位（`:656-665`）；`is_similar` 阈值 `similar_percentage=15`（`:107`）→ 汉明 ≤10。
- 黑边预处理：在 `time_start_list=[2,5,10,20,40,100,300,600,1200,2400,7200,14400]` 秒各跑 `cropdetect` 取**众数**（`framesextractor.py:113-175`）。
- **无缓存**。2 小时视频 N=7200 帧 → 水平拼接图 `1,036,800 × 144 ≈ 149 Mpx`，RGB 常驻约 **450 MB**。
- README:35 自述：**不能做 video fingerprinting（子片段判定）**；**反转或旋转 >10° 失效**。
- 可复用：`hamming_distance` + 64 位十六进制编解码（纯 Kotlin `Long.bitCount`）；「灰度 → 低频子带 → 中位数阈值 → 64 位」范式。可借鉴：黑边检测多采样取众数；时空二维编码；主色模板比对。不采纳：1 fps 全片抽帧（Android 必崩）；`-s 144x144` 非等比拉伸；无缓存；无依据阈值。

**MediaDedupe**（MIT，Python，`engine.py` 52.5 KB + `app.py` 53.0 KB）【源码】：
- 图片 pHash `convert('L')` → `resize((32,32), LANCZOS)` → 二维 DCT → 左上 8×8 → 中位数（`engine.py:253-266`）；视频 `VIDEO_FRAME_POSITIONS = [0.10,0.30,0.50,0.70,0.90]`（`engine.py:73`）各抽 1 帧；`engine.py:790-804` `_compare_frames` **按位置 zip**；`engine.py:807-871` 判定 = 时长差 ≤3.0 s 且 5 帧中 ≥4 帧汉明 ≤10（贪心分组）。
- **唯一有书面依据的阈值**在 `engine.py:59-64` 注释：`0 = pixel-identical`、`1–5 = same image, different resolution or minimal JPEG compression`、`6–8 = same image, stronger compression or small crop`、`> 10 = thematically similar (burst shots) — intentionally excluded`；`PHASH_THRESHOLD = 8`（`:64`）。
- 「片头/片尾检测」实为 `engine.py:584-614` 的固定首尾 N 秒 × 1 fps 抽帧 + `engine.py:874-950` all-pairs 比对（阈值 10、命中比例 ≥0.3），**无黑帧/淡入淡出/静音/场景切分**，README 措辞是营销。
- 缓存键 `mtime == stat.st_mtime and size == stat.st_size`（`:302`），**特例**：`intensive_n != intensive_seconds` 时强制重算（`:659-663`）。
- 可复用：阈值分级注释（唯一可采信的阈值语义来源）；pHash 纯 Kotlin 定义；「按位置 zip + 命中数 ≥4/5 + 时长预过滤」骨架；mtime+size 缓存失效范式（与本项目三元组同构）。可借鉴：首尾固定窗口 all-pairs 作**候选生成器**（不可作删除依据）；三阶段流水线；**采样配置变化必须使特征失效**（`intensive_n` 先例）。

**hclivess/video-duplicate-finder**（MIT，Python + PySide6，`core.py` 15.0 KB，v2.0.2）【源码】：
- 签名 = N 个 **dHash**，`core.py:94-98`：`cv2.resize(gray, (9,8), INTER_AREA)` → `small[:,1:] > small[:,:-1]` → `packbits` 成大端 u64（**是 dHash 不是 pHash**）；`hamming_similarity = 1.0 - popcount(a^b)/64`（`:101-102`）；`signature_similarity` 取逐帧**算术平均**（`:141-149`）。
- 采样 `core.py:123-124`：`lo,hi = int(total*0.05), max(int(total*0.95), ...)`，`np.linspace(lo, hi-1, num=min(frames, hi-lo))`，默认 `frames=12`。
- 阈值：`similarity` 默认 90%、`duration_tolerance` 默认 3%；三档预设 Exact 98%/同分辨率/tol 1%、Re-encode 90%、Loose 80%/tol 8%（`main.py:44-49`）。
- **union-find 聚簇**（`core.py:216-227`、`:315-327`），2.0 重写理由即「旧代码成对比对会漏掉传递性重复」（`README.md:72-74`）。早停剪枝 `core.py:305`。最短时长保护 1.0 s（`:255`）。缓存 `sig(path PK, size, mtime, frames, data)`，命中条件 `path=? AND size=? AND abs(mtime-?)<1 AND frames=?`（`:162-165`）——**`frames` 是缓存键的一部分**。
- 保留规则 `pick_keep`：`resolution`（默认）/`bitrate`/`largest`/`smallest`/`newest`/`oldest`/`shortest_path`（`:230-241`）。
- 可复用：**dHash 可 1:1 重写为 Kotlin**（成本最低的 SIMILAR 特征）；`hamming_similarity` + union-find；`frames` 参与缓存键；时长排序 + 早停剪枝。可借鉴：三档预设（98/90/80%）而非暴露任意阈值；首尾各弃 5%。不采纳：逐帧算术平均（时序敏感，应改命中率统计）；无黑边处理；无分辨率约束的跨分辨率合并；无分桶全对全。

**对本项目的结论**：① 三仓库无一可直接移植（都依赖 ffmpeg/OpenCV 抽帧），可移植的只有纯算法层；② EXACT 链路保持现状（L0 分桶 → 头尾 64 KiB → 流式 SHA-256 已强于三仓库），唯一可吸收的是「缓存失效键必须含算法/采样配置版本」；③ **SIMILAR 维持关闭**——三仓库全都没有 precision/recall；④ 若重开：第一层 dHash 12 帧 + **命中率**判据（非算术平均）+ 首尾各弃 5%；第二层 pHash 仅对高相似候选；片头/片尾用首尾固定窗口 all-pairs 只作候选生成器；⑤ 阈值初始锚点可用 MediaDedupe 的 0/1–5/6–8/>10 四档语义，但**必须用本项目自己的标注集重新标定**。

### 1.4 duplicates-detector-oss / VideoCull / dublette

#### 1.4.1 duplicates-detector-oss

**定位 / 技术栈 / 许可 / 规模**【源码】：Python 核心 `duplicates_detector/`（~30 模块：`cli.py` 3771 行 / `pipeline.py` 1600+ / `scorer.py` 57 KB / `comparators.py` 799 行 / `advisor.py` 841 行 / `cache_db.py` 791 行 / `reporter.py` 1210 行）+ 原生 macOS SwiftUI GUI `DuplicatesDetectorGUI/`（`State/SessionStore.swift` 124 KB、`Views/ResultsScreen.swift` 78 KB、`Bridge/CLIBridge.swift` 55.6 KB）。**CLI 是唯一真源**，GUI 通过子进程驱动并解析 stderr 上的 JSONL 进度。依赖 Python 3 + rich + Pillow + ffmpeg/ffprobe + scipy(tfidf) + send2trash。**许可 MIT**（`LICENSE` 1090 B「MIT License, Copyright (c) 2026 Omri Kaisari」；`pyproject.toml` `license = {text = "MIT"}`，`target-version = "py310"`）。~80 个 `test_*.py` + ~50 个 Swift 测试文件。

**数据模型与分组**【源码】：
- **主单位是 pair，不是 group**：`ScoredPair(file_a, file_b, total_score, breakdown, detail)`（`scorer.py:103-109`）。`breakdown: dict[str,float|None]` = 每个 comparator 的加权贡献（None = 无数据）；`detail: dict[str,tuple[float,float]]` = (raw 0..1, weight)。GUI 的 `PairResult`（`Models/ScanResult.swift:96-145`）一比一对应，JSON 里 `detail` 序列化成 `[raw, weight]` 二元数组。
- 分组是**可选的第二阶段**：`grouper.py:55-110 group_duplicates(pairs)` 用 `_UnionFind`（路径压缩 + 按秩合并，`grouper.py:23-52`）对所有已评分 pair 求连通分量 → `DuplicateGroup{group_id, members, pairs, max_score, min_score, avg_score}`（`grouper.py:11-20`）。docstring（`grouper.py:56-59`）明说「all files transitively connected by at least one scored pair」= **纯传递闭包，无链式误合并防护**。
- 「一个文件属于多组」不可能：并查集单根 ⇒ 文件只属一组。**三仓库在这一点上一致。**
- 排序：`sorter.py:7-46` 仅支持 `score|size|path|mtime`（默认 score）；组按 `max_score` 降序并**顺序编号 1..N**（`grouper.py:93-97`）——注意这个 id 每次重跑都会变。
- 缓存：`cache_db.py` 单 SQLite（`_SCHEMA_VERSION = 2`）7 张表，`metadata/content_hashes/audio_fingerprints/scored_pairs/pre_hashes/sha256_hashes/clip_embeddings`。**`scored_pairs` 主键是 `(path_a, path_b, config_hash)`**（`cache_db.py:17-73`）⇒ 换权重/阈值自动 miss；每行都带 `file_size + mtime` 做失效；`prune(active_paths)`（`:721`）清理已消失文件；`PRAGMA user_version` 不符则整库重建（`:138-173`）。

**并排对比面板**【源码】：
- `ComparisonPanel.swift:46-83` 布局：ScrollView{scoreHeader → mediaComparison → MetadataDiffTable → ScoreBreakdownDetail} + Divider + `ComparisonActionBar` + `ProgressView(index+1/total)`。
- **同步播放是 leader/follower 模型**（`VideoComparisonView.swift`）：A 是 leader（`PlayerRepresentable(isLeader: true)`），B 是 follower。
  - `effectiveDuration = max(durationA, durationB)`（`:197`）——时间轴取长者，长尾可达。
  - `seekBoth(to:)`（`:234-244`）：`absoluteTime = position * effectiveDuration`，然后**各自 clamp 到自身时长**，用 `toleranceBefore/After: .zero` 精确 seek。
  - `syncFollower()`（`:285-296`）：仅播放中生效，`drift = abs(leaderTime - followerTime)`，**`drift > 0.1` 才纠偏**，纠偏用 `CMTime(value:1, timescale:10)` = 100ms 容差，注释原文「avoids expensive frame-accurate decode」。
  - `handleFollowerTimeUpdate`（`:270-283`）：**只在 A 播完后 follower 才接管 transport**（`guard currentTimeA >= durationA - 0.05`），解决 B 比 A 长。
  - EOF：`maxTime >= effectiveDuration - 0.05` 时两个都 pause；已在 EOF 时播放先 `seekBoth(to: 0)`（`:206-209`）。逐帧用 `player.currentItem?.step(byCount: ±1)`（`:216-232`）。倍速分段 0.25/0.5/1/2 直接写 `player.rate`；**A/B 各自独立静音**（`:156-172`）。时间观察器 30 Hz，`AVPlayerView.controlsStyle = .none` 自绘 transport。
- 展示属性 `MetadataDiffTable.swift:222-321`：三列 Grid（字段名 | A | B），**值不同才高亮**（`differs ? textPrimary + accent.opacity(0.1) 背景 : textMuted`，`:311-320`）。Size 恒显示，其余 `addIfPresent` 仅在有值时加行：Duration / Resolution / Codec / Bitrate / FPS / Audio / Modified / Title / Artist / Album / Pages / docTitle / docAuthor / docCreated。
- **同名文件消歧**：`fileLabels(fileA:fileB:)`（`:29-36`）在两个文件名相同时改用**父目录名**。
- 评分呈现：`ScoreBreakdownDetail.swift:326-360` = `BreakdownBar` 堆叠条 + 每个 comparator 一行；排序规则「权重降序，再键升序」（`:354-359`）；每行显示 raw%（`"%.0f%%"`）+ 贡献点（`"%.1f pts"`），无数据显示「—」（`:365-398`）。总分用 `ScoreRing`。

**比对算法可配置**【源码】：
- `Comparator` 抽象基类（`comparators.py:57-68`）：`name`、`weight  # max contribution to total score (points out of 100)`、`score(a,b) -> float|None`（0.0–1.0，缺元数据返回 None）。**这是加权求和模型，不是「选一个算法」。**
- 视频默认权重：filename 35 + duration 35 + resolution 15 + file_size 15 = 100；`--content` 加 `ContentComparator` weight 40；`AudioComparator` weight 30。
- 具体算法（全是纯元数据的廉价比较）：
  - `DurationComparator:151-164`：**`MAX_DIFF = 5.0` 绝对秒差**内线性衰减 `1 - diff/5`，≥5 s 直接 0。
  - `ResolutionComparator:167-180`：`min(pixels)/max(pixels)`（1080p vs 4K = 0.25）。
  - `FileSizeComparator:183-192`：字节比 `min/max`。
  - `FileNameComparator:89-148`：`fuzz.token_sort_ratio/100`，外加三层否决：① 两边都是纯数字 ID（Telegram/WhatsApp）时数字串必须完全相等否则 0；② 去数字后文字骨架相同但数字列表不同（`"Movie Part 1"` vs `"Movie Part 2"`）→ 0；③ `ratio < 0.85` 时若双方各有一个对方没有的 ≥3 字符字母词（`"Mexico Guadalupe"` vs `"Mexico Izamal"`）→ 0。
  - `ContentComparator:266-318`：**优先级链**（每步 lazy import 重模块）tfidf 矩阵 → 文档 simhash → clip 嵌入 → ssim 帧 → content hash(phash)，否则 None。
- **「灰度差异 vs pHash 的取舍」在本仓库的答案是「分档」而非二选一**：默认档只用元数据 comparator，**完全不抽帧**；`--content-method phash|ssim|clip|simhash|tfidf` 才引入内容 comparator，且它仍只是 +40 分的一项。`phash` 是内容档默认。
- 门控与早退（`scorer.py:264-284`）：无内容证据时 filename raw < `_MIN_FILENAME_RATIO = 0.6` **直接丢弃该对**（注释「coincidental metadata similarity alone is not enough」）；`total + remaining_weight < threshold` 早退。两条同时是性能与误报优化。
- 可配置接口：`--weights "filename=30,duration=20"` → `parse_weights`（`comparators.py:422-464`）校验未知键/重复键/非有限值/负值；`_apply_weights`（`:467-484`）**仅当 `directory` 权重 > 0 时**把总权重归一到 100。`_WEIGHT_KEY_MAP` 同时接受 `filesize` 与 `file_size`。各 mode 有各自键白名单（`_DEFAULT_KEYS`/`_CONTENT_KEYS`/image/audio/document 变体，`:406-419`）。
- 桶化：`_bucket_by_field/_bucket_by_duration/_bucket_by_page_count`；`_MAX_BUCKET_PAIRS = 50_000`（`scorer.py:454`）超限后用 `_resolution_tier`/`_filesize_tier` 再分桶（`_refine_large_buckets` `:483`）。

**防误删设计**【源码】：
- 六种删除策略抽象 `Deleter`（`deleter.py:21-47`），`make_deleter`（`:290-308`）：`delete`(unlink) / `trash`(send2trash) / `move-to`(MoveDeleter，冲突时 `stem_1.suffix` 递增) / `hardlink` / `symlink` / `reflink`。抽象类声明 4 个 verb 属性（`verb`/`dry_verb`/`prompt_verb`/`gerund`）让**所有面向用户的文案随策略变形**（"Would delete" vs "Would trash"）。
- reflink/hardlink/symlink 都是**原子操作**：`_tmp_link_path` 在同目录 `tempfile.mkstemp`（前缀截断到 128 字符以避开 NAME_MAX）→ `os.link`/`symlink_to`/`_create_reflink` → `os.replace(tmp, path)`，任何 `BaseException` 都清理临时文件（`:137-162/177-191/257-275`）。`_create_reflink` 用 `cp -c`(APFS) 或 `cp --reflink=always`(Btrfs/XFS)，**失败就失败，绝不静默降级成普通拷贝**（`:243-250`）。
- 交互复核 `advisor.py:372-511 review_duplicates`：`a` 删A / `b` 删B / `s` 跳过 / **`s!` 跳过并记住（写入 ignore list）** / `q` 退出（剩余全计 skipped）。
  - **`default_choice = "s"`**（`:447`）——只有 keep 策略给出推荐且推荐目标不是 reference 时才改默认（`:446-461`）。**回车永远等于跳过。**
  - **选择集随 reference 状态收窄**（`:437-444`）：A 是 reference → 只给 `["b","s","q"]`；B 是 reference → `["a","s","q"]`；否则 `["a","b","s","q"]`。两个都是 reference 直接自动跳过（`:429-432`）；已删除文件的对自动跳过（`:418-424`）。
  - 删除时 `sidecars=target.sidecars` 随主文件走（`:488-501`），`ignore_list.save()` 只在最后调一次。
- reference 保护：`--reference <dir>`；`_is_reference`（`cli.py:935-943`）对 **resolved 与原始路径都做** `is_relative_to` 判断，docstring 说明「symlinks inside a reference directory are still treated as reference files even when their target lives outside」。GUI 里 reference 文件按钮禁用，tooltip 区分「Cannot act on the keep file」/「Cannot act on a reference file」（`GroupActionBar.swift:222-224, 281-303`）。
- 两阶段：`--dry-run` 走**同一个** `auto_delete(..., dry_run=True)`，结果塞进 JSON 的 `dry_run_summary`（`cli.py:2746-2772`）——预览与执行只有 `dry_run` 布尔不同。
- 审计 + 可回滚：`actionlog.py` 追加式 JSONL，每条 `timestamp/action/path/score/strategy/kept/bytes_freed`（+`destination`/`dry_run`/`sidecar_of`），写一行 flush 一次（注释「for crash safety」）。`undoscript.py:155-206` 从该日志**生成 bash 撤销脚本**：`moved` → 条件 `mv` 回来；`hardlinked/symlinked/reflinked` → 从记录的 `kept` `cp` 回来；`trashed` → 打印 `MANUAL: Restore <path> from OS trash`；`deleted` → 打印 **`IRRECOVERABLE: <path> was permanently deleted`**。脚本自带交互确认 + `restored/failed/warnings` 计数。
- 事后对账：`PairResolutionStatus.swift:4-11` = `.active` / `.resolved(HistoryAction)`（有 sidecar 记录）/ `.probablySolved(missing:[String])`。面板在 `.resolved` 时把按钮换成**收据横幅**「Kept X, trashed Y / Saved 12 MB / 3m ago」；在 `.probablySolved` 时显示「This pair was likely resolved outside the app」——**外部改动被显式识别而不是静默**。
- CLI 互斥校验：`--quiet` 与 `--interactive` 互斥（`cli.py:3248-3250`）；`--action hardlink|symlink|reflink` 必须配 `--keep` 或 `--interactive`（`:3350-3351`）。

**工程实践**【源码】：
- 六阶段流水线 `_CANONICAL_STAGES = ("scan","extract","filter","content_hash","audio_fingerprint","score")`（`pipeline.py:39`）。`PipelineController`（`:168+`）用**单个 `threading.Event`** 统一 pause/resume（CLEARED = 阻塞等待者），**`cancel()` 设标志「并且」set event** 以唤醒暂停中的等待者（`:182-184, :234-240`）。提供 `wait_if_paused()`（async）与 `wait_if_paused_blocking()`（executor 线程）两套（`:335-350`）。阶段推进点 20+ 处统一为 `await controller.wait_if_paused(); if controller.is_cancelled: ...`。
- 跨进程暂停：`watch_pause_file(path)`（`:315-333`）**轮询一个文件内容 `"pause"`/`"resume"`** 来驱动控制器，docstring 明说这样对信号处理器安全。配套 `_PauseAwareTextWriter`（`cli.py:158`）让 stdout 也遵守暂停，`_start_parent_liveness_monitor`（`cli.py:181`）监听父进程存活（GUI 死了 CLI 自杀）。
- 进度：`ProgressEmitter`（`progress.py:64-272`）向 **stderr 写 JSON-Lines**。事件：`session_start`（含 `resumed_from`/`prior_elapsed_seconds`）、`stage_start`、`progress`（stage/current/total/file/cache_hits/cache_misses/**rate**/**eta_seconds**）、`stage_end`、`pause`/`resume`、`session_end`（含 **`cache_time_saved`**）。按 stage 节流 100 ms（`_THROTTLE_INTERVAL = 0.1`），最后一个事件强制发出；速率用 EMA α=0.3 平滑（`:236-253`）。`threaded=True` 时把 stderr 写放到守护线程，注释原文：「a full stderr pipe buffer (GUI subprocess) can freeze the entire pipeline」（`:76-79`）。
- 续跑：`session.py` 的 `ScanSession`（`:86`）+ `SessionManager`（`:170`）；`_STAGE_WEIGHTS`（`:15`）算续跑进度，`:107` 注释说明某阶段「contributes 0% — this is intentionally conservative」——**续跑进度条宁可保守**。
- 并行：`ProcessPoolExecutor` + `_score_bucket_chunk_worker`（`scorer.py:303`，注释「must be top-level for pickling」）；`_effective_workers`（`pipeline.py:49`），`workers: 0` = auto。缩略图用 `ThreadPoolExecutor` + `as_completed`。任务组失败时 cancel 其余并重抛第一个真实错误（`pipeline.py:122-142`）。
- 缓存工程：`_canonical_pair(path_a,path_b,mtime_a,mtime_b)`（`cache_db.py:500`）做顺序无关规范化；每线程一个连接（`threading.local()`）；`WAL + synchronous=NORMAL`（注释「skips per-commit fsyncs in WAL mode」）+ `busy_timeout=5000`；损坏时重命名坏文件重来（`:76-136`）。
- 忽略列表：`ignorelist.py` 持久化「这不是重复」的 pair，路径**字典序排序**存储使 `contains(B,A) == contains(A,B)`，用 resolved 路径，**原子写（tempfile + os.replace，`:43-55`）**，损坏则从空开始。
- 输出：`reporter.py` 支持 `table/json/csv/shell/html/markdown` 六格式，pair 与 group 各一套实现；`_MAX_TABLE_ROWS = 500` 截断。
- **JSON 是双向的**：`write_json`（`:412-461`）每条含 `file_a/file_b/score/breakdown/detail([raw,weight])/file_a_metadata/file_b_metadata/file_a_is_reference/file_b_is_reference/keep?`；外层可选 envelope（含完整有效配置 `args_dict`）+ `dry_run_summary{files_to_delete[{path,size,size_human}],total_files,total_bytes,total_bytes_human,sidecars_to_delete?,strategy?}`（`:353-384`）。`load_replay_json`（`:290-350`）能读回重建成 `ScoredPair`，`cli.py:2245 _run_replay` 据此**不重扫而重新执行/复核**；裸 JSON 数组（无 envelope）明确 raise ValueError（`:294`）。
- 缩略图：`thumbnails.py` **每视频只抽一帧**，位置 `duration * 0.1`（`:69`），160×90、JPEG q8、15 s 超时，直接输出 base64 data URI 塞进 JSON；>10 个文件才显示进度条（`_PROGRESS_THRESHOLD = 10`）。

**可复用 / 可借鉴 / 不采纳**：
- **可复用**：加权 comparator 模型（0–1 分数 + weight + None=无数据，`breakdown`/`detail` 双字典直出 UI）；`detail` 用 `[raw, weight]` 序列化；reference 目录保护 + **选择集收窄**；交互默认值 = 跳过；`s!` 跳过并记住；JSONL 审计日志 + 生成撤销脚本；pair 缓存主键含 `config_hash`；进度 JSONL 事件 schema（rate/eta/cache_hits）；`threaded` 进度写盘避免 stderr 管道阻塞；暂停文件通道 + 父进程存活监控。
- **可借鉴**：`MetadataDiffTable` 的「不同才高亮」三列布局；同名时用父目录消歧；leader/follower 同步播放（100 ms 容差纠偏 + 长尾接管）；`.resolved`/`.probablySolved` 事后对账横幅；`--dry-run` 与执行共用代码路径；`_create_reflink` 失败不降级；「IRRECOVERABLE」显式文案；续跑进度宁可保守。
- **不建议采纳**：`ProcessPoolExecutor` + pickling 的复杂度（Android 用 coroutine/WorkManager 更合适）；DCT pHash / CLIP / ssim / tfidf / chromaprint（本项目禁用）；`send2trash` 依赖；单帧缩略图（视频整理场景不够）。
- **不确定**：`html_report.py`(41 KB) 未读；`AggregatingProgressEmitter`/`_SubEmitter`（auto 模式并发子流水线聚合进度，`progress.py:280-321`）未细读；`auto_delete`/`review_groups` 与 `review_duplicates` 的差异未逐行比对。

#### 1.4.2 VideoCull

**定位 / 技术栈 / 许可 / 规模**【源码】：Electron 主进程 + React 渲染进程。`electron/`：`main.js` 124 KB、`cache.js` 52.7 KB、`duplicates.js` 49 KB、`processor.js` 19.5 KB、`duplicate-worker.js`、`visual-worker.js`、`keyed-operation-queue.js`、`processing-pause.js`；`src/`：`store.ts` 73 KB、`App.tsx` 59 KB、`SettingsModal.tsx` 69 KB、`Sidebar.tsx` 56 KB、`ReviewMode.tsx` 33 KB。
**许可 AGPL-3.0-only**（`package.json` `"version": "2.3.2"`, `"license": "AGPL-3.0-only"`；`LICENSE` = AGPL v3, 19 Nov 2007）。~138 代码文件 / 941 KB。依赖外部 ffmpeg/ffprobe 二进制（`ffmpeg-toolchain.json`、`media-process.js`）——**这正是本项目必须替换的部分**。`docs/features/*.mdx` 是产品契约文档，权威性高于代码注释。

**数据模型与分组**【源码】：
- SQLite 缓存（`cache.js:256-318`）三表：`videos`（~33 列，含 `status TEXT DEFAULT 'pending'`、`rating`、`favorite`、`bookmarks TEXT`、`file_signature_quick`、`file_signature_full`、`duplicate_hash`、`metadata_version`、`fingerprint_failure_key`）、`thumbnails(video_id, idx, file_path, PK(video_id,idx))`、`video_fingerprints(video_id, sample_index, timestamp_secs, phash_hex, flipped_phash_hex, gray_bytes BLOB, frame_dark_ratio, fingerprint_key, PK(video_id,sample_index))`。索引 `idx_videos_status`/`idx_videos_duplicate_hash`/`idx_videos_metadata_date`。
- **迁移是加列式**：`PRAGMA table_info` → 缺哪列 `ALTER TABLE ADD COLUMN`（`:388-399`）；写入用 `INSERT ... ON CONFLICT(id) DO UPDATE SET`。`journal_mode=WAL`、`busy_timeout=5000`、`foreign_keys=ON`。
- 组模型 `buildGroups`（`duplicates.js:1005-1165`）：`{id, videoIds, similarity, averageSimilarity, matchType, suggestedKeeperId, exactVideoIds, reason}`。**`id = 'dup-' + sha1(排序后成员 id).slice(0,16)`**（`:1001-1003`）——跨运行稳定，UI 选择/滚动位置可挂靠。
- **组的 `similarity` = 最弱一对向下取整到 0.1**（`:1142-1146`），注释原文「The weakest matched pair, rounded down, so filtering groups by this score behaves like scanning at that threshold」——**展示分数与阈值语义完全一致**。
- 「一个文件属于多组」不可能：`deriveVideos`（`:1167-1197`）把组信息**反规范化**到每个 video 上（`duplicateGroupId/duplicateSimilarity/duplicateMatchType/duplicateSuggestedKeeper/duplicateExact/duplicateGroupSize/duplicateMatchReason`，未分组为 null/0/false）。同一份数据同时服务「按组看」和「按视频看」。
- 展示排序：精确组优先，然后 similarity 降序（`:1159-1164`）。用户状态与决策分离：`VideoStatus = 'pending'|'keep'|'delete'|'skipped'`（`types.ts:2`），`rating/favorite/bookmarks` 是独立列。

**并排对比面板**【源码】：
- **没有并排双播放器**。对比 = **多帧缩略图条**：`ThumbnailStrip.tsx` 用 `calcThumbGrid(n)`（`utils.ts:58-66`）排成固定网格（1→1×1、2→2×1、4→2×2、6→3×2、9→3×3，其他 `ceil(sqrt(n))`），CSS Grid `repeat(cols, minmax(0,1fr))`。无缩略图时回退系统缩略图（跨 3×2）或脉冲占位；图片走自定义协议 `thumb://local/<encoded>`，`decoding="async"`，`FadingThumbImage` 在 onLoad 淡入**并在 `useEffect` 里补检已完成的图**（否则缓存命中时永不淡入）。
- 重复组视图用**虚拟滚动行模型**（`duplicateVirtualRows.ts`）：行是判别联合 `'group-header'|'video-row'|'gallery-card-row'`；常量写死 `HEADER_HEIGHT=64`/`VIDEO_ROW_HEIGHT=79`/`GALLERY_ROW_HEIGHT=361`；`computeDuplicateGalleryLayout(availableWidth)` 动态算列数再分卡宽；行 key 形如 `${groupId}:gallery:${startIndex}`。
- 「并排」实际发生在 **Review 模式**：单播放器 + `getReviewMediaWidth(vw,vh,isPlaying)`（`ReviewMode.tsx:49-59`）按视口算宽度（上限 1950，16:9 约束）。

**比对算法可配置**【源码】：
- 两模式 `COMPARISON_MODES = ['phash','visual']`（`duplicate-utils.js:29-31`）。`comparisonMode` 默认 `'phash'`，并做**一次性迁移**：`if (raw.duplicates.phashDefaultApplied !== true) { comparisonMode='phash'; phashDefaultApplied=true }`，注释原文「pHash became the default because it produces far fewer false positives; move existing installs over once, then respect whatever the user picks afterwards」（`keybind-defaults.ts:160-165`）。
- `phash`：真 DCT pHash（`calculateDctPHash`，`duplicate-utils.js:137-170`）——32×32 灰度，取左上 8×8 DCT 块（`cu/cv` 归一化、`0.25*cu*cv*sum`），**跳过 DC 系数**（注释「it's almost always above the median so bit 63 would universally be 1, wasting a hash bit」），其余 63 个取中位数置位，输出 16 位十六进制（63 有效位）。相似度 `((64 - popcount(a XOR b))/64)*100`（`:200-205`）。
- `visual`：32×32 灰度**逐像素平均绝对差**，`100 - (sum/count/255)*100`（`rawGraySimilarity`，`:207-223`），可选忽略黑像素（双侧 ≤5）/白像素（双侧 ≥250）。`visual-worker.js:83-113` 有早退：`maxDiffSum = (100 - threshold) * sampleCount`，一旦超限立即返回 null。
- **何时用哪个**（`duplicate-review.mdx:25-30` 原文）：pHash =「strict image-signature comparison with few false positives」但「can miss transformations that visual similarity still recognizes」；Visual 能抓转码/缩放/近乎相同素材但「broader matching can produce more false positives」；建议「Run them separately when coverage matters more than processing time」。**阈值语义：`finalSimilarityThreshold: 95` 在 pHash 下 = 64 位里最多差 3 位。**
- **打分必须与阈值同义**：`combineSampleScores`（`:240-245`）注释原文「The pair score must be the value the threshold actually tests, so a pair shown as X% is exactly what a scan at X% would find. With every-sample matching that is the weakest sample; otherwise it is the average.」→ `requireEverySample ? min(scores) : average(scores)`。
- 其余可配：`sampleCount ∈ [1,2,3,4,5,7,9]`（白名单）、`samplingWindow ∈ {even,25-75,20-80,15-85,custom}`、`durationTolerancePercent: 20`、`ignoreBlackPixels/ignoreWhitePixels`、`compareFlipped`、`maxSamplingDuration`、`protectKeep: true`、`protectSkipped: false`、`keeperOrder: ['resolution','videoBitrate','duration','fps','size']`、`checkpointIntervalMinutes: 5`、`ignoredDuplicatePairs`。
- **指纹缓存键必须含抽取器版本**：`getDuplicateFingerprintKey` = `['gray32-v2', sampleCount, bounds.start, bounds.end, maxDuration].join('|')`（`:121-135`），注释原文解释 v2 由来：「the bundled FFmpeg moved from 2018 to 9.0.2, which decodes some frames differently (mixed old/new pHash fell to 62–94% similarity for identical files…)」。**换解码器 = 必须整体失效指纹缓存。**
- 时长桶化：候选生成按 `Math.floor(duration/1s)` 分桶并向两侧扩展 `durationTolerancePercent`（`duplicate-worker.js:43-58`），**只比较时长接近的视频**，避免 O(n²)。

**防误删设计**【源码】：
- 核心产品规则（`delete-and-safety.mdx:12` 原文）：「VideoCull separates review decisions from file deletion. Keep, Delete, Skip, and Reset are reversible status changes. **Files only leave disk when you run the final delete action and confirm the batch.**」Delete 的含义是「进入下一批删除」而非「已删」，状态表原文「Unchanged until confirmation」。
- **删除集合是全局的，不是过滤后的**：`delete-and-safety.mdx:40` 原文「The sidebar delete count is global to the loaded session, not the current filter. A filtered grid is useful for reviewing the Delete set, but hiding a marked video does not remove it from the batch.」确认框也再次警告（`:14-16`）。
- 确认文案由 `formatDeleteConfirmation`（`utils.ts:12-26`）生成，固定形如 `Move N videos (1.2 GB) to the Recycle Bin? If the Recycle Bin is unavailable, VideoCull will ask before permanently deleting.` ——**把降级路径写进确认框**。
- 回收站优先 + 失败才问：`deletion.ts`（**24 行**）`deleteWithPermanentReview`：先 `moveToTrash(filePaths)`，收集 `failedPaths`，`if (failedPaths.length === 0 || !await confirmPermanentDelete(failedPaths)) return trashResults;`，否则永久删并**按路径合并结果**保证调用方每个路径一条结果（`:10-24`）。**整个防误删设计就这 24 行。**
- 路径白名单：删除请求只接受**当前会话已加载目录内**的视频，目录外直接拒绝（`:52-56`）。成功删除后清缓存行 + 缩略图文件；失败可重试。空目录清理默认关闭且在确认框里说明。
- 复核批次四步清单（`:33-40`）：① 过滤到 Delete 再看一遍 ② 核对确认框的数量与总大小 ③ 导出 HTML 报告留档 ④ 不对就取消（「Cancelling does not change statuses or files」）。
- 键盘工作流：`K/D/S/R/Z` = Keep/Delete/Skip/Reset/Undo（`keybind-defaults.ts:32-53`），`Tab` = 下一个未决，`Ctrl+Backspace` = 执行删除（固定键不可改），`Esc` = 先停播再退出 Review。
- **快捷键优先级**：Review 的 keydown 监听器用 **capture 阶段**注册，注释原文「Run before the embedded player so configured Review shortcuts (notably K) cannot be consumed by the player's own keyboard bindings first」（`ReviewMode.tsx:552-554`）；`isFocusableKeyboardTarget`（`:158-162`）让输入框获得焦点时全部让路。
- 撤销：`UndoEntry {videoId, previousStatus, previousIndex, videoIds?, previousStatuses?}`（`types.ts:241-247`）。**批量操作合并成一条** UndoEntry（`store.ts:1195-1201`）；`undo()` 既恢复状态**也恢复 Review 位置**（`reviewIndex: action.previousIndex`，`store.ts:1259`）；撤销栈在删除完成后清空（`:1615`）。持久化只写变更的视频与字段（`persistReviewState(..., [updatedVideo], ['status'])`）。
- **范围冻结**：Review 打开时快照 id 列表（`scopeIdsRef`，`ReviewMode.tsx:194-197`），`reviewScope` memo 只依赖 `videosById`（`:200-202`），所以后续改状态不会让条目从队列里消失（文档原文「Review freezes its scope when it opens」）。
- 误报反馈闭环：`Not a match`（恰好两个视频时）把该 pair 写进设置 `ignoredDuplicatePairs`，**后续扫描直接排除**；两个视频的组立即消失，更大的组需重跑（`duplicate-review.mdx:89-98`）。正则校验 `/^[0-9a-f]{16}\|[0-9a-f]{16}$/i`（`keybind-defaults.ts:172-178`）。与 ddoss 的 IgnoreList 是同一设计。

**工程实践**【源码】：
- 并发：`processVideos`（`processor.js:396-465`）是**无锁索引式工作窃取的 worker pool**（`createQueueCursor`，`:192-200`），`workerCount = min(concurrentLimit, queue.length)`。`getConcurrentLimit`（`:380-394`）auto 时 `cpuBased = cpuThreadsLimited===false ? max(1,floor(cpu/2)) : max(2,ceil(cpu*1.25))`、`memBased = max(1, floor((freeMemGb-1.5)/0.25))`，取 `clamp(min(cpuBased, memBased), 1, 24)`；显式值 clamp [1,32]，默认 3。
- GPU 冷却：硬件加速时按 `getGpuCooldownBatchSize`（默认 `max(75, floor(1200/thumbsPerVideo))`，≤500）分批，批间 `sleep(1250 ms)`（≤10000）（`:172-190`）。
- 暂停/取消：`processing-pause.js` 三态机 `running/pausing/paused`，**只有 `pauseRequested && activeOperations===0 && 所有 worker 都 quiescent` 才算 paused**（`:11-19`）；worker 侧通过 `SharedArrayBuffer` + `Atomics.wait/notify` 真阻塞（`duplicate-worker.js:12-20`）；主线程用 `checkpoint()` await（`:61-67`）。取消 = `token.cancelled`，在 worker 循环和抽帧内部都检查。
- **每资源串行**：`keyed-operation-queue.js`（67 行）按 key 串行 + 优先级 `background(0) < foreground(1) < interactive(2)`，不同 key 并行——避免同一文件的缓存写竞争。
- 进度：`duplicate-worker` 先做一遍**廉价计数 pass** 得到 pair 总数供进度条用，再跑真实 pass（`:110-114/147-152`）；每 1024 对检查一次暂停（`(compared & 1023) === 0`）；**进度每 ≥250000 对才上报一次**（避免 IPC 洪水）。缩略图侧每完成一个视频报一次 `onProgress({current,total})`。
- 抽帧：`calculateTimestamps(duration,count,skipDelaySecs)`（`processor.js:90-112`）——`start=skipDelay`（默认 3 s 跳过片头）、`end=duration*0.97`，N 帧各取**自己区间的中心**（`start + step*0.5 + step*i`），避开前 3 s 与最后 3%；`duration < 10s`（`SINGLE_THUMBNAIL_VIDEO_DURATION_SECS`）只出 1 帧。默认 `THUMB_COUNT = 6`。每帧重试偏移序列 `[t, t+0.25, t-0.25, t+0.75, t-0.75]`（去重）；单帧超时 120 s（`FRAME_EXTRACTION_TIMEOUT_MS`），单视频总预算 300 s（`VIDEO_EXTRACTION_BUDGET_MS`）；**一旦 ETIMEDOUT 就放弃该视频剩余所有帧**（注释「a decoder that hung once on a file usually hangs again at the next offset」）；预算按实际耗时扣减，帧间暂停不计入；全帧失败后还会用 t=0 再赌一次。
- 缩略图缓存复用：`getReusableThumbnailPaths`（`:131-149`）按**精确文件名集合** `thumb_01.jpg … thumb_NN.jpg` 判断，数量不等或有空文件就返回 null 强制重建；重建时先清空该视频目录。
- 缓存位置三档（Centralised/Per-drive/Distributed 内嵌 `.videocull`），切换走迁移流程。`cache-and-processing.mdx:52` 原文「Changing a generation setting does not silently rebuild existing thumbnails」；`:90-92` 区分「缩略图重生成」（保留状态）与「清缓存重载」（丢失人工决策，`Ctrl+Shift+R`）。
- 大文件指纹：`quickSignature` = sha256(`size` + 首 1 MiB + **中间 1 MiB**)（`duplicates.js:181-193`，≤2 MiB 直接整文件）；`fullFileHash` 4 MiB 分块流式 + 每块取消检查点（`:195-211`）。**中间块的设计值得抄**。
- 先精确后模糊两级：`buildExactRepresentativeIndex`（`:805-858`）把精确组**折叠成一个代表**再参与模糊比对；`expandRepresentativePairs`（`:860-879`）再把命中的代表对**展开回全组成员叉积**。这是 O(组数²) 而非 O(文件数²) 的关键优化。
- **链式误合并防护（三仓库唯一有实现者）**：① 贪心聚合时**代表锚定**——合并两组要求 `directSimilarity(groupA.representativeId, groupB.representativeId) >= threshold`（`:1072-1073`），加入新成员要求 `directSimilarity(target.representativeId, newVideoId) >= threshold`（`:1083-1084`）；② `pruneWeakDaisyChainMembers`（`:886-918`）以 `requiredConnections = Math.ceil((active.length - 1) / 2)` 反复剔除连接数不足的最弱成员；③ `splitDaisyChainIds`（`:946-962`）对 `ids.length < 3` 直接返回，否则把剪掉的成员**递归再分组**并丢弃单例。三者串联在 `buildGroups` 尾部（`:964-999`）。
- 暗帧处理：`DARK_SAMPLE_RATIO_THRESHOLD = 0.8`（`duplicate-worker.js:11`）——任一侧样本 `darkRatio ≥ 0.8` 时**跳过该样本**（黑帧会匹配任何东西）。样本不足（`sampleCount > 1 && sampleScores.length < 2`）直接拒绝该对。

**可复用 / 可借鉴 / 不采纳**：
- **可复用**（几乎零改动可移植）：状态机四态 + 决策与文件操作分离；`deletion.ts` 的「先回收站、失败再问」24 行流程；确认框写清降级路径与总大小；删除集合是全局的且显式警告；`UndoEntry` 批量合并为一步 + 恢复位置；Review 打开时冻结范围；capture 阶段拦截快捷键 + 输入框让路；`ShortcutContext = 'playing' | 'not-playing'` 区分同一个键的两种含义；`ignoredDuplicatePairs` 误报反馈；组的 `similarity` 取最弱对向下取整；稳定组 id（sha1 排序成员）；组信息反规范化到每个视频；`calcThumbGrid` 固定网格表；指纹缓存键含抽取器版本。
- **可借鉴**：`calculateTimestamps` 的「N 帧各取区间中心 + 跳片头 3 s/片尾 3%」；单帧重试偏移序列；**一次超时即放弃整个视频**；单视频总预算；`quickSignature` 的中间 1 MiB；`keyed-operation-queue` 按资源串行 + 三级优先级；worker 计数 pass 先算总量；进度节流阈值；三态暂停机（quiescent 才叫 paused）；暗帧跳过；时长桶化候选生成。
- **不建议采纳**：ffmpeg/ffprobe 外部进程（本项目禁用）；`SharedArrayBuffer`/`Atomics.wait` 阻塞式暂停（Android 主线程不能这样阻塞，要用协程/WorkManager）；每视频 6 帧的顺序抽帧（Android `MediaMetadataRetriever` 有真实成本，需重新定档）；`video://` / `thumb://` 自定义协议（Android 用 FileProvider/Coil）。
- **不确定**：`duplicates.js` 的 `backfillFingerprints`（`:449`，maxConcurrency=2）、`createSimilarityRevalidator`（`:640`）、`findDuplicates`（`:1199`）未细读；`store.ts` 的 `persistReviewState` 实现未读；`cache.js` 缓存迁移的完整 UI 流程未读。

#### 1.4.3 dublette

**定位 / 技术栈 / 许可 / 规模**【源码】：Rust CLI，10 个源文件共 **2970 行**（`dedupe.rs` 1060 / `audio.rs` 499 / `report.rs` 320 / `scan.rs` 309 / `cli.rs` 218 / `hash.rs` 197 / `delete.rs` 121 / `lib.rs` 102 / `skip.rs` 80 / `main.rs` 64）。版本 0.6.1，edition 2024。
**许可 AGPL-3.0-or-later**（`Cargo.toml`；`LICENSE` = AGPL v3, 19 Nov 2007）。依赖：clap 4、rayon 1.10、indicatif 0.18、image 0.25 + image_hasher 3.1、lofty 0.25、rusty-chromaprint 0.3、serde/serde_json、tabled、dialoguer、walkdir、which、tempfile；外部依赖 ffmpeg。`meta/adr/000{1,2,3}-*.md` 是高质量架构决策记录（写清被否决方案与理由），`docs/.../cli-reference.md` 把 CLI 契约（含退出码与 JSON schema）写成文档。

**数据模型与分组**【源码】：
- `DuplicateGroup { kind: MediaKind, keep: PathBuf, duplicates: Vec<PathBuf> }`（`dedupe.rs:40-44`）——**一个组恰好一个 keep + N 个 duplicates**；组没有 id、没有分数、没有平均相似度。
- 分组算法 `scan.rs:63-110 build_duplicate_groups`：输入是**对称邻接表**，对每个未访问节点做**迭代式 DFS 泛洪**（`HashSet` + `Vec` 栈），得到连通分量；成员排序后 `keep = sorted.remove(0)` = **字典序第一个**（`:99-101`）。**纯连通分量 = 完全传递闭包，没有任何链式误合并防护。**
- 证据：`scan.rs:269-285` 单测 `grouping_transitive()` 断言 a↔b、b↔c 产生**一个**组，keep=a.jpg，duplicates=[b.jpg, c.jpg]。全 `*.rs` 搜 `transitive|chaining|clique|union|max_group|anchor` 只命中无关的迭代器 `.chain()`。
- 三个 kind（image/video/audio）**分别独立跑三趟**；组带 `kind` 字段但 JSON 输出里**不输出 kind**。组内 duplicates 排序、组间按 keep 路径排序（`report.rs`）；没有「按分数排序」——因为没有分数。
- **没有持久化缓存/数据库**：每次运行全量重算（只有进程内 HashMap 桶）。

**并排对比面板**：不存在。CLI 的「对比」是表格里每组一段（`report.rs:35-66 format_table`，列 `Group|Kind|File|Action`，keep 行 action=`"keep"`，duplicates 行 action=`"would delete"` 或 `"delete"`）；`--verbose` 时 stderr 打印每对距离（`dedupe.rs:320-325 progress.diag("{a} <-> {b}: distance={distance}")`）。

**比对算法可配置**【源码】：
- 不是加权模型，是**按 kind 选算法**：
  - 图像/视频：`image_hasher` 的 `HashAlg::DoubleGradient`，`hash_size(8,8)` = **64 位 dHash（DoubleGradient），不是经典 pHash**（`hash.rs:8-13`）。距离 = 汉明距离，阈值 `-t/--threshold`（默认 1，即最多差 1 位）。
  - 视频：**每视频只抽一帧**，seek 依次尝试 `["1","0"]`（`hash.rs:71-96`），两次都失败记 `SkipReason::DecodeFailed`。
  - 音频：`--audio-match recording`（默认）= ffmpeg 解码前 ~120 s PCM → 纯 Rust `rusty-chromaprint`，相似度用归一化 `dissimilarity`，阈值 `--audio-threshold`（`audio.rs:16 DEFAULT_THRESHOLD = 0.1`，范围 0.0–1.0）；`--audio-match encoding` = 把文件字节**去掉标签区域**后哈希成 64 位摘要，无阈值。
- ADR 0002 记录：阈值**按 kind 不同语义**（图像/视频 = 汉明位数，音频 = 归一化差异度）；被否决方案包括 fpcalc、symphonia 进程内解码、对 PCM 做哈希（会让「严格」模式比「模糊」还慢）、把指纹压成固定长度汉明哈希（丢失对裁剪静音的鲁棒性）。
- 图像哈希优先用 `image::open`，失败才回退 ffmpeg（`hash.rs:15`）；`--only images|videos|audio` 限定，不指定则三趟都跑。

**防误删设计**【源码】：
- `delete.rs:8-25 delete_files(paths, label, yes)`：空则返回 0；`!yes` 时 `dialoguer::Confirm` 问 `Delete {n} {label} file(s)?`，拒绝就**返回 0 而不是报错**；同意则逐条 `fs::remove_file` —— **永久删除，无回收站、无备份、无撤销、无审计日志、无「这不是重复」反馈**。
- 两层保护全靠 CLI 语义：① `--dry-run` 分支（`lib.rs:74-90` 只在 `!args.dry_run` 时才调 delete）；② 交互确认（除非 `-y`）。
- **重要局限**：dry-run 不是「生成计划再执行」，而是同一个 `run()` 里的一个布尔分支——**执行时会重新跑整个流水线**，然后删内存里那份 report 的 duplicates。中间没有任何「计划文件」或版本校验，扫描与执行之间文件被改动不会被察觉。
- `--keep-in <DIR>`（可重复，顺序即优先级）是唯一的选择集收窄机制。ADR 0003 记录动机：把 staging 目录对着已整理的 beets 库去重时，35 个跨树组里有 1 组保留了 staging 副本、把 `library.db` 引用的文件标成删除，导致 `-y` 不可用。实现 `apply_keep_in`（`dedupe.rs:233-262`）在**分组之后统一应用一次**（注释「one shared site instead of threading it through the three per-kind selection paths」），只改组内选择、不改组成员；包含判定用**规范化后的祖先检查**（`canonicalize` 后 `Path::starts_with`，组件级，所以 `/lib/foo` 不会误匹配 `/lib/foobar`）。被否决：`--prefer-dir`/`--authoritative` 命名、把限制塞进每个 kind 的选择点、用扫描目录顺序隐式定权威（会静默改变所有现有多目录行为）、把幸存者移动到 keep-in 目录（「dublette deletes, it does not organize」）、只扫描 keep-in 树。
- keeper 选择：`apply_keep_in` 对音频+recording 用 `audio::quality_keep`（无损 > 高码率 > 字典序），其余用 `alphabetical_keep`；没有 keep-in 时**一律字典序第一个**。

**工程实践**【源码】：
- 三阶段流水线 `plan()`（`dedupe.rs:124-231`）：逐 kind 收集文件 → `hash_in_parallel` → `compare_and_build_groups`。`compare_and_build_groups`（`:298-342`）是**无条件的 O(n²) 全对比较**（`for i in 0..len { for j in (i+1)..len }`），没有任何分桶/索引/早退；`total_pairs = n*(n-1)/2` 用来驱动进度条（`:309-310`）。
- 并行：`rayon` 的 `files.par_iter()`（`hash_in_parallel` `:344-383`），**并发度无上限**（不读 CPU 数）；哈希错误收进 `SkippedFile` 而不中断（`:355-382`）。
- 进度：`Progress` trait 只有 4 个方法（`phase_start`/`tick`/`phase_finish`/`diag`，`:53-58`），两个实现 `NoopProgress`（`--quiet`）与 `IndicatifProgress`（模板 `"{msg} [{bar:40}] {pos}/{len} ({eta})"`）。`diag` 只在 `--verbose` 时写 stderr。ADR 0001 说明：`Progress` 是唯一通过「两个适配器」检验的抽象；`plan()` 返回**纯值**、CLI 外壳负责所有 I/O；明确**否决**了 Terraform 式 `plan`+`apply` 拆分和一个 `Confirmer` trait，理由是「a seam is real only when ≥2 adapters exist」。
- 取消：`main.rs:13-19` `ctrlc::set_handler` 设静态 `INTERRUPTED: AtomicBool`，**第二次 Ctrl+C 才 `exit(130)`**（第一次是优雅取消）。
- 跳过原因：`skip.rs` 的 `SkipReason {DecodeFailed, TooShort, UnsupportedContainer, Unreadable}` 有稳定 snake_case `tag()`，且有单测 `tags_are_stable_snake_case`（`:64-72`）——**机器可读的失败原因被当作 API 对待**。
- 文件收集：`scan.rs:27-61 collect_files` 递归 walkdir、扩展名大小写不敏感、**跳过 0 字节文件**（`:50-52`）、结果 `sort + dedup`。`delete.rs:30 find_empty_files` 专门处理 0 字节文件（`--delete_empty`）。
- 输出契约：`docs/.../cli-reference.md` 明列**退出码**（`0` 成功 / `1` **dry-run 发现重复** / `2` 参数非法 / `130` Ctrl+C，`:154-163`），并注明「The exit code `1` in dry-run mode is intentional: it allows scripts to detect whether duplicates exist without deleting them.」（`:163`）；JSON 输出「All seven fields are always present.」（`:202`，缺失用 `[]`/`0` 而非省略）；跳过原因表 `:206-217`。

**可复用 / 可借鉴 / 不采纳**：
- **可复用**：`Progress` trait 的最小接口（4 方法）与「plan 返回纯值、外壳负责 I/O」的分层；稳定的 snake_case 跳过原因 tag（+ 单测锁死）；退出码契约（含 dry-run 发现重复返回 1）；JSON 输出「七个字段永远都在」；`--keep-in` 的「只在分组后统一应用一次」+ 规范化祖先判定；「第二次 Ctrl+C 才强杀」。
- **可借鉴**：ADR 的写法（写清被否决方案与理由、以及什么条件下该重新考虑）；把 CLI 契约写成文档（含 JSON schema 与退出码表）；`skip.rs` 的失败原因枚举；`collect_files` 跳过 0 字节。
- **不建议采纳**：**纯连通分量分组 + 无任何链式防护**（正是 VideoCull 要修的问题）；无条件 O(n²) 全对比较；dry-run 与执行共用一个布尔分支、执行时重跑全流程（缺少「计划-执行」之间的身份校验）；`fs::remove_file` 永久删除；rayon 无上限并发。
- **不确定**：`dedupe.rs` L385-1060 的单测块、`report.rs` L200-320、`audio.rs` L1-105 与 L150-499、`tests/cli.rs` 未读；`--delete-empty` 与 `--keep-in` 的交互未验证。

#### 1.4.4 三仓库横向对比

| 维度 | duplicates-detector-oss | VideoCull | dublette |
|---|---|---|---|
| 主数据单位 | pair（group 为可选并查集结果） | video + group（反规范化到 video） | group（keep + duplicates） |
| 一个文件多组？ | 不可能（并查集单根） | 不可能（至多一组） | 不可能（DFS visited） |
| 组分数 | max/min/avg | **similarity = 最弱对向下取整** + averageSimilarity | 无 |
| 组 id | 顺序整数（重跑即变） | `dup-<sha1(成员)>`（**稳定**） | 无 |
| 链式误合并防护 | **无** | **有**（代表锚定 + `ceil((n-1)/2)` 连接剪枝 + 递归再分组） | **无**（且有单测断言传递闭包） |
| 分数=阈值语义 | 同一量纲但未显式保证 | **显式注释保证** | 不适用 |
| 算法模型 | 加权求和（weight 满分 100，缺数据=None） | 模式二选一（phash/visual）+ 多帧采样取 min 或 avg | 按 kind 选算法 |
| 抽帧 | 每视频 1 帧 @10%（仅缩略图，不参与比对） | 每视频 **6 帧**，区间中心，跳片头尾 | 每视频 1 帧 @1 s |
| 阈值 | `--threshold 50`（加权总分） | `finalSimilarityThreshold 95`（=64 位最多差 3） | `-t 1`（汉明距离）；音频 0.1 |
| 缓存 | SQLite 7 表，pair 主键含 `config_hash` | SQLite 3 表，指纹键含抽取器版本 | **无** |
| 进度 | stderr JSONL（含 rate/eta/cache_hits） | IPC + 计数 pass + 节流 | indicatif 进度条 + `diag` |
| 暂停/取消 | PipelineController + 暂停文件 + 父进程监控 | 三态机 + SharedArrayBuffer/Atomics | ctrlc 两次才强杀 |
| 删除动作 | 6 策略（含 trash/move-to/hardlink/reflink） | 回收站优先，失败再问 | 永久 unlink |
| 预览/执行 | `--dry-run` 共用代码路径 + JSON `dry_run_summary`；**JSON 可 replay** | 决策与删除分离，确认框写明全局计数 | `--dry-run` 布尔分支，执行时重跑 |
| 审计/撤销 | JSONL action log + **生成 bash 撤销脚本** | 内存 undo 栈 + 决策持久化到 SQLite | 无 |
| 误报反馈 | IgnoreList（JSON，字典序键，原子写） | `ignoredDuplicatePairs`（正则校验） | 无 |
| 引用保护 | `--reference <dir>`（**选择集收窄** + 按钮禁用） | `protectKeep: true`（keep 不进删除建议） | `--keep-in <dir>`（选择集收窄） |
| 许可 | MIT | AGPL-3.0-only | AGPL-3.0-or-later |

#### 1.4.5 对本项目的结论

1. **数据模型**：采用 VideoCull 的「video 表 + 组信息反规范化到 video」双层模型，落到 Room 三张表：`videos`（含 `status`/`rating`/`favorite`/`bookmarks`/`sizeBytes`/`quickFingerprint`/`sha256` 及 `duplicateGroupId` 等冗余列）、`duplicate_groups`（`id TEXT PK` = **成员 id 排序后哈希，跨扫描稳定**；`similarity`、`matchType`、`suggestedKeeperId`、`sizeBytes` 汇总）、`group_members`（`groupId`+`videoId` 唯一约束保证「一个文件至多一组」）。**不要**照抄 ddoss 的顺序整数 `group_id`（重跑就变，UI 选择会漂）。当前只做 EXACT 时不需要 pair 表——EXACT 就是哈希相等，按 `sha256` 直接分组；只有未来放开相似匹配才需要 pair 级的 `breakdown`/`detail`。
2. **分组策略**：当前 EXACT 天然没有链式问题。一旦放开相似匹配，**必须**抄 VideoCull 的三层防护（代表锚定合并 → `requiredConnections = ceil((n-1)/2)` 剪枝 → 对剪掉的成员递归再分组），**不要**抄 ddoss/dublette 的纯连通分量。同时抄「组的展示分数 = 最弱一对向下取整」，保证 UI 显示的百分比就是扫描阈值语义；抄稳定组 id 以便 UI 选择/滚动位置可挂靠。
3. **多帧缩略图**：抄 `calculateTimestamps`（跳片头 3 s / 片尾 3%，N 帧各取区间中心；`duration < 10s` 只 1 帧）与 `calcThumbGrid` 固定网格表；但**帧数要重新定档**（Android `MediaMetadataRetriever` 每帧有真实成本，建议先做 3 帧并实测，而不是默认 6）。重试偏移序列 `[t, ±0.25, ±0.75]` 可抄；**「单帧超时即放弃整个视频」必须抄**（Android 上卡死的解码器更常见）；单视频总预算 + 每帧超时必须抄。缓存复用按**精确文件名集合**判定。**指纹缓存键含抽取器版本**这条对 Android 尤其重要：一旦换 `MediaMetadataRetriever` 实现或系统升级，旧指纹必须整体失效。
4. **并排对比面板**：抄 ddoss 的 `MetadataDiffTable`（三列，**值不同才高亮**；行只在有值时显示）与「同名文件用父目录消歧」。**不要**抄 ddoss 的双 AVPlayer 同步播放（Android 双 Media3 实例同步代价高、收益低）；改用 VideoCull 的**单播放器 + 多帧缩略图条**，把「同步对比」降级为「缩略图条并排 + 点选切换播放」。若确需双画面，借鉴 leader/follower 的**容差纠偏**（100 ms 容差、只在漂移超限时 seek、长尾由 follower 接管），不要逐帧精确对齐。
5. **评分可配置**：抄 ddoss 的 comparator 抽象（`name`/`weight`/`score→0..1|None` + `breakdown`/`detail` 双字典直出 UI），但**算法清单按本项目禁用项裁剪**：保留 filename（含三层否决规则：纯数字 ID 精确匹配、编号序列骨架、独特词否决）、duration（**绝对秒差线性衰减**比百分比更符合直觉）、resolution（像素比）、file_size（字节比）；**删除** phash/ssim/clip/tfidf/chromaprint。灰度差异（`rawGraySimilarity`）属于「像素级」而非「感知哈希」，无 FFmpeg 前提下可用 Media3 抽帧后自己算，可作为唯一的「相似」档保留，但**默认关闭**（与现状一致）。`--weights` 的解析校验（未知键/重复键/非有限/负数）+ 只在 `directory` 权重 >0 时归一化到 100，可直接移植。
6. **两阶段预览/执行**：抄 ddoss 的「dry-run 与执行共用同一条代码路径，只有 dry_run 布尔不同」+ JSON `dry_run_summary`（`files_to_delete[{path,size,size_human}]` + `total_files` + `total_bytes` + `total_bytes_human` + 可选 `strategy`）；**并且抄「JSON 是双向的」**（ddoss `load_replay_json` 能读回重建，dublette 的 JSON 只是单向报告）——让「整理预览」导出可回读的 JSON，实现先预览、后执行、可复核。**不要**抄 dublette 的「dry-run 是布尔分支且执行时重跑全流程」——中间必须有一次身份校验（记录 `size + mtime + quickFingerprint`，执行前逐条复核，不匹配就跳过并报告）。退出码语义可借鉴（发现重复=1 便于脚本判断），Android 端更适合用「预览结果对象 + 明确状态枚举」。
7. **防误删**：本项目已决定「移入应用回收站、不自动删除」，与 VideoCull 的回收站优先语义一致。应补上：① 决策（`pending/keep/delete/skipped`）与文件操作**彻底分离**，Delete 只改状态、文案明说「确认前文件不动」；② 删除集合是**全局的**而非当前筛选结果，确认框必须同时给出「总条数 + 总大小」并显式警告筛选不影响批次；③ 确认文案写明降级路径（回收站不可用时怎么办）；④ 删除请求只接受**已加载目录内**的路径（防越界）；⑤ 撤销要把批量操作**合并成一步**且恢复界面位置；⑥ 提供「这不是重复」的持久化忽略对（字典序键 + 原子写，抄 ddoss `IgnoreList` / VideoCull `ignoredDuplicatePairs`），并在后续扫描中排除。
8. **引用/元数据处理**：抄 ddoss 的 reference 概念（受保护目录内的文件既不能被删、也不出现在可删选项里——**在 UI 层收窄选择集，而不只是禁用按钮**）。本项目对应「已收藏 / 已加入播放列表 / 有观看进度 / 已在自建回收站内」的视频。ddoss 的 `sidecars`（伴随文件随主文件走、`action_log` 记 `sidecar_of`）不直接适用，但**「删除一个视频时它的关联状态（进度/书签/缩略图缓存/指纹缓存）如何级联」**要显式设计（VideoCull 的做法是成功删除后清 cache 行 + 缩略图文件，失败可重试）。
9. **工程实践**：抄 VideoCull 的 `keyed-operation-queue`（按资源串行 + 三级优先级）与「worker 计数 pass 先算总量再跑真实 pass」；抄「进度节流阈值」避免 IPC/Flow 洪水；抄「三态暂停机」（所有 worker 都 quiescent 才算 paused）。Room/SQLite 侧抄 ddoss 的 `config_hash` 进缓存主键（换权重/阈值自动失效）与 `PRAGMA user_version` 版本迁移；抄 VideoCull 的**加列式迁移**（`PRAGMA table_info` + `ALTER TABLE ADD COLUMN`）。**抄 VideoCull 的 `quickSignature` 中间 1 MiB 块**——本项目目前只用头尾 64 KB，加上中间块能在几乎不增加成本的前提下显著降低「同长度不同内容」的误判。取消语义抄 ddoss 的「取消要唤醒所有暂停中的等待者」，以及「父进程存活监控」（Android 上对应 WorkManager 取消 / 前台服务停止）。
10. **明确不要做的**：不要抄 dublette 的纯连通分量分组、无条件 O(n²) 全对比较、`fs::remove_file` 永久删除、dry-run 重跑全流程；不要抄 ddoss 的 `ProcessPoolExecutor`+pickling、DCT pHash/CLIP/ssim/tfidf/chromaprint、`send2trash` 依赖；不要抄 VideoCull 的 ffmpeg/ffprobe 外部进程、`SharedArrayBuffer`+`Atomics.wait` 阻塞式暂停、`video://`/`thumb://` 自定义协议。

---

## 2. 转码仓库

### 2.0 结论汇总

| 仓库 | 定位 | 对本项目的价值 |
| --- | --- | --- |
| `HandBrake`（GPL-2.0-or-later） | 预设=纯 JSON 数据、能力=C API、四套前端共享同一字段集合 | 「**模板即 schema**」与 `preset_clean` 净化函数；几何求解 `hb_set_anamorphic_size2` 可直接移植 |
| `VidCoder`（GPL-2.0-or-later） | HandBrake 的 Windows 前端；**Picker 系统** | **速率控制三模式互斥** + 「体积目标→码率」换算 + 队列状态复位 + 错误码分段 |
| `FastFlix`（MIT） | Python GUI；**每编码器声明自己有哪些模式** | 离散档位表（不让用户输任意值）+ 能力探测降级 + per-encoder Optional 子模型 |
| `lossless-cut`（**GPL-2.0-only**） | Electron；**不重新编码**，关键帧层切割 | 时间轴「区间层 + 补集层」双语义 + 导出对话框信息层级 + 三条防黑帧/防时长错乱规则；**在「实际起止对比」上是缺口**。⚠ **唯一许可不兼容，只能借鉴设计不能抄代码** |
| `ab-av1`（MIT） | Rust CLI；**CRF 搜索 + VMAF** | 整数 q 空间搜索 + **保守体积外推（取两种外推的较小值）** + 「体积上限内达不到质量就明确失败」 |
| `filmcompress`（MIT） | Python 单文件 169 行 | **默认哲学的正面样本**（不动分辨率/帧率/pix_fmt）+ **反面教材**（无能力探测、无回退、`intel` 死选项） |
| `shutter-encoder`（GPL-3.0-or-later） | Java/Swing，FFmpeg GUI，100 `.java` / 78,135 行 | **反向参考**：功能名=全局字符串+到处 switch、`setVisible` 手动编排；可复用「探测强制超时」+「HEVC 自动 `hvc1`」+「faststart 按容器门控」+ 两模式取消 |
| `compressO`（**AGPL-3.0-only**） | Tauri 2 + Rust + React，FFmpeg sidecar，71 文件 / 300 KB | **容器 × 编码器显式声明表**（最值得照抄）；`-progress -` 进度契约；cancel 回调删半成品；**「Lossless 名不副实」的反面教材** |
| `staxrip`（MIT） | VB.NET，**编排器**，333 文件 / 7.19 MB（145 `.vb`） | **价值最高**：声明式设置页 + 字段绑定、**目标体积扣减法公式**、「重封装=空编码器」、容器能力谓词、退出码→人话、异常三分、HDR 元数据派生链、产物过小即判失败 |
| `FFmpeg`（LGPL-2.1+） | 本体（**已 No-Go，只做交叉验证**） | **`-c copy` 的五条损坏条件**（X4 的依据）、`query_codec` 白名单机制、`hvc1` vs `hev1` 权威注释、三种码控模式词汇表 |

**共同结论：三项目里只有 compressO 有显式声明式「容器 × 编码器」表；只有 staxrip 真正实现「目标体积」（且是「先扣减再换算」）；「重封装 vs 重编码」做得最干净的是 staxrip 的 `NullEncoder`；进度 / 队列 / 逐项失败记录三者全部是短板。** 三者共同印证了本项目 `ADR-TRANSCODE-002` 的双引擎边界裁决。
**两条许可更正**：`compressO` 是 **AGPL-3.0-only**（简介标 LGPLv2.1，错）；`shutter-encoder` 是 **GPL-3.0-or-later**（子代理报 GPL-2.0 / GPL，错）。**唯一与 AGPL-3.0 不兼容的是 `lossless-cut`（GPL-2.0-only）。** 详见 §0.1。

### 2.1 HandBrake

**定位 / 技术栈 / 许可 / 规模**：GPL v2（`HandBrake/COPYING`）。核心是 C/C++ 库 `libhb/`，其上四套前端：GTK4（`gtk/`）、macOS（`macosx/`）、Windows WPF C#（`win/CS/`）、CLI。约 981 代码文件 / 9.3 MB。
**架构决定性事实**：**预设是纯数据（JSON 字典），能力是 C API，前端只做 UI**。四套前端共享同一份字段集合定义。

#### 预设数据模型【源码】

**不存在 `hb_preset_s` 结构体**（全仓库 grep `hb_preset_s\b` 零命中）。预设已完全改为 `hb_value_t`/`hb_dict_t` 字典模型，**schema 由模板文件承担**（「schema 即模板」）：
- `HandBrake/preset/preset_template.json`（136 行）= 全字段清单 + 默认值
- `HandBrake/preset/preset_builtin.json`（611 KB）= 官方预设树；`preset/preset_cli_default.json`（5774 B）= CLI 默认
- `HandBrake/preset/preset_builtin.list` 声明 `VersionMajor=73 / VersionMinor=0 / VersionMicro=0`

字段清单（按前缀分组）：
- 元数据：`PresetName`、`PresetDescription`、`PresetDisabled`、`Type`(0=OFFICIAL/1=CUSTOM)、`Default`、`Folder`、`FolderOpen`、`ChildrenArray`、`UsesPictureFilters`、`AlignAVStart`
- 输出：`FileFormat`、`Optimize`、`Mp4iPodCompatible`、`ChapterMarkers`、`MetadataPassthru`、`InlineParameterSets`
- 视频：`VideoEncoder`、`VideoQualityType`(1=ABR/2=CRF)、`VideoQualitySlider`、`VideoAvgBitrate`、`VideoPreset`、`VideoTune`、`VideoProfile`、`VideoLevel`、`VideoOptionExtra`、`VideoFramerate`、`VideoFramerateMode`、`VideoMultiPass`、`VideoTurboMultiPass`、`VideoScaler`、`VideoColorMatrixCodeOverride`、`VideoColorRange`、`VideoGrayScale`、`VideoHWDecode`、`VideoPasshtruHDRDynamicMetadata`
- 音频：`AudioList[]`（`AudioEncoder`、`AudioBitrate`、`AudioTrackQualityEnable`、`AudioTrackQuality`、`AudioMixdown`、`AudioSamplerate`、`AudioCompressionLevel`、`AudioDitherMethod`、`AudioNormalizeMixLevel`、`AudioTrackGainSlider`、`AudioTrackDRCSlider`、`AudioFilterList`）、`AudioCopyMask[]`（`"copy:aac"`,`"copy:ac3"`,`"copy:dts"`,`"copy:dtshd"`,`"copy:eac3"`,`"copy:flac"`,`"copy:mp3"`,`"copy:truehd"`）、`AudioEncoderFallback`、`AudioSecondaryEncoderMode`、`AudioTrackSelectionBehavior`("first")、`AudioLanguageList`
- 字幕：`SubtitleTrackSelectionBehavior`、`SubtitleLanguageList`、`SubtitleBurnBehavior`、`SubtitleBurnDVDSub`、`SubtitleBurnBDSub`、`SubtitleAddCC`、`SubtitleAddForeignAudioSearch`、`SubtitleAddForeignAudioSubtitle`
- 画面：`PictureWidth`/`PictureHeight`、`PictureForceWidth`/`PictureForceHeight`、`PictureAllowUpscaling`、`PictureUseMaximumSize`、`PictureAutoCrop`、`PictureCropMode`(0 自动/1 loose/2 无/3 自定义)、`PictureTopCrop`/`BottomCrop`/`LeftCrop`/`RightCrop`、`PictureKeepRatio`、`PictureModulus`(默认 2)、`PicturePAR`("auto"/"off"/"strict"/"custom"/"loose")、`PicturePARWidth`/`PicturePARHeight`、`PictureDARWidth`、`PictureItuPAR`、`PictureRotate`("angle=0:hflip=0")、`PicturePadMode`/`PicturePadTop`/`Bottom`/`Left`/`Right`/`PicturePadColor`
- 滤镜组（统一三段式 `XxxPreset` / `XxxTune` / `XxxCustom`）：`PictureDeband*`、`PictureDeblock*`、`PictureCombDetect*`、`PictureDeinterlace*`、`PictureDenoise*`、`PictureChromaSmooth*`、`PictureColorspace*`、`PictureSharpen*`、`PictureDetelecine*`

文件夹树深度上限 `HB_MAX_PRESET_FOLDER_DEPTH 8` @ `HandBrake/libhb/handbrake/preset.h:15`。遍历/递归框架：`presets_do(preset_do_f, hb_value_t*, ctx)` @ `libhb/preset.c:209`，回调返回码 `PRESET_DO_SUCCESS/FAIL/PARTIAL/NEXT/SKIP/SKIP_LEVEL/DELETE/DONE` @ `libhb/preset.c:40-49`，天然支持数组与文件夹递归。

#### 质量 vs 码率两种模式【源码】

**核心思想：两者互斥写入 job dict，永不同时存在。** `hb_preset_apply_video()` @ `libhb/preset.c:2052`，判定块 @ `:2176-2207`：`vqtype = VideoQualityType`；`vqtype==2 && hb_video_quality_is_supported(vcodec)` → 写 `Quality` 并 `hb_dict_remove(video_dict,"Bitrate")`；`vqtype==1` → 写 `Bitrate` 并 remove `Quality`；否则回退。多遍受 `hb_video_multipass_is_supported(vcodec, 是否 CRF)` 门控 @ `:2209`。

质量范围按编码器查询：`hb_video_quality_get_limits(codec,&low,&high,&granularity,&direction)`、`hb_video_quality_get_name`、`hb_video_quality_get_default` @ `libhb/handbrake/common.h:541-543`；`hb_video_quality_is_supported(uint32_t)` @ `libhb/common.c:1956`（VideoToolbox 走 `hb_vt_is_constant_quality_available`）；`hb_video_bitrate_is_supported` @ `libhb/common.c:1972`（DNxHR/FFV1/ProRes 返回 0）。

C# 侧：`HandBrakeEncoderHelpers.GetVideoQualityLimits(HBVideoEncoder)` @ `win/CS/HandBrake.Interop/Interop/HandBrakeEncoderHelpers.cs:651` → `VideoQualityLimits` 类（`Low`/`High`/`Granularity`/`Ascending` 四字段）@ `win/CS/HandBrake.Interop/Interop/Interfaces/Model/Encoders/VideoQualityLimits.cs`。

UI 切换：`win/CS/HandBrakeWPF/ViewModels/VideoViewModel.cs` — `IsBitrateSupported => SelectedVideoEncoder?.SupportsBitrate` @ `:144`；`SetQualitySliderBounds()` @ `:1002-1031`（granularity≠1 时把 low/high 除以用户 X264Step）；`HandleRFChange()` @ `:1158-1167` 越界回落到 `qualityMax/2`。

#### 参数校验与按编码器净化【源码】

入口 `hb_presets_clean(hb_value_t*)` @ `libhb/preset.c:2920` → `presets_clean(preset, hb_preset_template)` → 递归 `do_preset_clean` → `preset_clean(hb_value_t *preset, hb_value_t *template)` @ `:2761`：先 `dict_clean(preset, template)`（**按模板删未知键、补默认值**），再规范化 FileFormat/VideoEncoder 短名（无效则回落 `hb_video_encoder_get_default(muxer)`），最后写 `PresetDisabled = hb_video_encoder_is_supported(vcodec)==0` @ `:2812-2813`。

编码器 × 容器兼容校验 @ `hb_preset_apply_video()` `:2081-2087`：`if (!(encoder->muxers & mux)) { hb_error("Incompatible video encoder (%s) for muxer (%s)", ...); return -1; }`。

音频净化 `hb_sanitize_audio_settings(const hb_title_t*, hb_value_t*)` @ `libhb/preset.c:681`，内部 `sanitize_audio_codec(int in_codec, int out_codec, ...)` @ `:649`。

**UI 侧的「按编码器动态显隐 + 重建可选值」范式**：`VideoViewModel.HandleEncoderChange(HBVideoEncoder)` @ `win/CS/HandBrakeWPF/ViewModels/VideoViewModel.cs:1033-1156`：重建 Profiles/Tunes/Levels/Presets 列表，并设置 `DisplayOptimiseOptions = encoder?.Presets?.Count > 0` @ `:1122`、`DisplayTurboAnalysisPass = IsX264||IsX265` @ `:1124`、`DisplayTuneControls = encoder?.Tunes?.Count > 0` @ `:1126`、`DisplayLevelControl = encoder?.Levels?.Count > 0` @ `:1128`、`DisplayFastDecode = IsX264||IsSVTAV1` @ `:1130`、`DisplayProfileControl = encoder?.Profiles?.Count > 0` @ `:1138`；若 `!SupportsMultiPass()` 则强制 `MultiPass=false; TurboAnalysisPass=false` @ `:1146-1150`。

#### 编码器能力探测【源码】

编译期静态表 + 链式遍历。`struct hb_encoder_s { const char *name; const char *short_name; const char *long_name; int codec; int muxers; }` @ `libhb/handbrake/common.h:234-241`（`name` 给预设用、`short_name` 给 CLI 用、`muxers` 是位掩码）。

遍历 API @ `libhb/common.c`：`hb_video_encoder_get_next(const hb_encoder_t*)` :3396、`hb_video_encoder_get_from_name` :3330、`hb_video_encoder_get_from_codec`、`hb_video_encoder_get_default(int muxer)` :3289、`hb_video_encoder_get_name`/`short_name`、`hb_video_encoder_is_supported(int)` :2067。

**每编码器可选值由独立 C API 提供**（不靠猜）：`hb_video_encoder_get_presets/tunes/profiles/levels(id)`，C# 侧封装见 `win/CS/HandBrake.Interop/Interop/Interfaces/Model/Encoders/HBVideoEncoder.cs`：`Presets` @ `:85`、`Tunes` @ `:96`、`Profiles` @ `:107`、`Levels` @ `:118`；容器支持用位与 `CompatibleContainers & NativeConstants.HB_MUX_MASK_*` @ `:140-150`；`SupportsQuality` @ `:159`、`SupportsBitrate` @ `:163`。

硬件解码能力：`struct hb_hwaccel_s { int id; const char *name; const int *encoders; int type; int hw_pix_fmt; can_filter/find_decoder/upload 函数指针; int caps; }` @ `common.h:251-265`，caps 位 `HB_HWACCEL_CAP_SCAN/ROTATE/FORMAT_REQUIRED/COLOR_RANGE` @ `:245-248`，实例 `hb_hwaccel_videotoolbox/qsv/nvdec/mf/amfdec` @ `:267-271`。

#### 滤镜链与尺寸处理【源码】

`hb_preset_apply_dimensions(hb_handle_t*, int title_index, const hb_dict_t *preset, hb_dict_t *job_dict)` @ `libhb/preset.c:2303`。顺序：**先 rotate**（`hb_generate_filter_settings(HB_FILTER_ROTATE, NULL, NULL, rotate)` 解析 `"angle=/hflip="`，非零才 `hb_add_filter2(filter_list, {ID:HB_FILTER_ROTATE, Settings:...})`）→ `hb_rotate_geometry(&srcGeo,&srcGeo,angle,hflip)` 把旋转**先应用到源几何** → 再算 crop（`PictureCropMode` switch @ `:2361-2381`）→ pad → `geo.modulus = PictureModulus`（`if (geo.modulus < 2) geo.modulus = 2`）→ PAR 模式映射（off/strict/custom/auto/默认 loose → `HB_ANAMORPHIC_*`）→ `geo.keep = keep_aspect*HB_KEEP_DISPLAY_ASPECT` + 可选 `HB_KEEP_WIDTH`/`HB_KEEP_HEIGHT` → `geo.flags |= allow_upscaling*HB_GEO_SCALE_UP | use_maximum_size*HB_GEO_SCALE_BEST` → `geo.maxWidth/maxHeight = PictureWidth/PictureHeight` → 算 `displayWidth`。

**核心求解器** `hb_set_anamorphic_size2(hb_geometry_t *src_geo, hb_geometry_settings_t *geo, hb_geometry_t *result)` @ `libhb/hb.c:1231`（~300 行；声明 @ `libhb/handbrake/handbrake.h:93`）：`int mod = (geo->modulus > 0) ? EVEN(geo->modulus) : 2` @ `:1248`；keep/upscale/best 从位取 @ `:1236-1242`；PAR 为 0 时强制 1 @ `:1254-1261`；`maxWidth = MIN(MAX(MULTIPLE_MOD_DOWN(geo->maxWidth, mod), HB_MIN_WIDTH), HB_MAX_WIDTH)` @ `:1336-1353`；**`if (!upscale) maxWidth = MULTIPLE_MOD_DOWN(cropped_width + pad_width, mod)`** @ `:1354-1360` —— **不允许放大时把上限压回源分辨率**。标志 `HB_KEEP_WIDTH 0x01` @ `common.h:318`、`HB_GEO_SCALE_BEST 0x02` @ `common.h:326`。

#### 预设版本与迁移【源码】

版本常量 `libhb/preset.c:24 int hb_preset_version_major;`（当前 73.0.0）。`hb_presets_version(preset,&major,&minor,&micro)` @ `:4262`；`hb_presets_update_version` @ `:4284`；`hb_presets_import(const hb_value_t *in, hb_value_t **out)` @ `:4311`；`hb_presets_import_json` @ `:4344`；`hb_presets_clean_json` @ `:4367`。

**迁移链 = 逐级 if-else 版本门**：`static int preset_import(hb_value_t *preset, int major, int minor, int micro)` @ `:4139`，`cmpVersion(...)` 依次判断 `0.0.0→import_0_0_0`、`10.0.0`、`11.0.0`、`11.1.0`、`12.0.0`、`20.0.0`、`25.0.0`、`35.0.0`、`40.0.0`、`44.0.0`、`47.0.0`、`50.0.0`、`51.0.0`、`53.0.0`、`55.0.0`、`57.0.0`、`60.0.0`、`61.0.0`、`63.0.0`、`64.0.0`、`69.0.0`、`72.0.0`，末尾 `preset_clean(preset, hb_preset_template)` @ `:4257`。层级重构 `import_hierarchy_29_0_0` @ `:2994` + `import_folder_hierarchy_29_0_0` @ `:2948` + `fix_name_collisions(list,name)` @ `:2925`（重名加 " - N"）。

#### 队列与任务模型【源码】

libhb 状态机 `struct hb_state_s` @ `libhb/handbrake/common.h:1459`；状态位 `HB_STATE_IDLE 1 / SCANNING 2 / SCANDONE 4 / WORKING 8 / PAUSED 16 / WORKDONE 32 / MUXING 64 / SEARCHING 128` @ `:1461-1468`；进度负载 `param.working{pass_id, pass, pass_count, progress, rate_cur, rate_avg, eta_seconds, hours, minutes, seconds, paused, error}` @ `:1472-1510`；多遍常量 `HB_PASS_SUBTITLE -1 / HB_PASS_ENCODE 0 / HB_PASS_ENCODE_ANALYSIS 1 / HB_PASS_ENCODE_FINAL 2` @ `:1487-1490`。

WPF 队列 `win/CS/HandBrakeWPF/Services/Queue/QueueService.cs`：`activeJobs` :51、`queueFile` :62、`hardwareResourceManager(QueueResourceService)` :65、`allowedInstances` :67、`queueTaskPoller(Timer)` :73；事件 `JobProcessingStarted/QueueChanged/QueueCompleted/QueuePaused/QueueJobStatusChanged/EncodeCompleted` :97-107；`Pause(bool pauseJobs)` :542、`RetryJob(QueueTask)` :349、`GetNextJobForProcessing()` :413、`BackupQueue(string)` :176。

**`RestoreQueue(string importPath)` @ `:491` 把遗留的 InProgress/Paused 一律重置为 Error/Waiting** @ `:521-524` —— 跨重启持久化的关键安全网。

状态枚举 `QueueItemStatus { Waiting=0, InProgress, Completed, Error, Paused, Cancelled }` @ `.../Queue/Model/QueueItemStatus.cs`。

**错误上报范式** `QueueProgressStatus.Update(EncodeCompletedEventArgs e)` @ `.../Model/QueueProgressStatus.cs`：把 `e.ErrorInformation` 字符串码映射为本地化文案 —— `"1"` HB_ERROR_CANCELED→Cancelled、`"2"` WRONG_INPUT→InvalidInput、`"3"` INIT→InitFailed、`"4"` UNKNOWN→Unknown、`"5"` READ→ReadError、`"-11"`→WorkerCrash、default→NoErrorCode。

#### 可复用 / 可借鉴 / 不采纳

- **可复用**：① 「质量与码率互斥、只写一个进 job」的落盘规则；② 「模板即 schema」——字段清单外置为一份可 diff 的 JSON；③ 尺寸求解器的完整规则集（mod 对齐、不放大的上限压回、旋转先于裁剪）；④ 按编码器查询质量上下限/粒度/方向的三元组 API 形状。
- **可借鉴**：① 版本门 if 链式迁移 + 迁移末尾统一 clean；② 文件夹重名自动加后缀；③ 队列载入时把 InProgress/Paused 重置；④ 错误码字符串→本地化文案的映射表；⑤ `presets_do` 式「带返回码的递归遍历」。
- **不建议采纳**：① 用 `ShortName.Contains("nvenc")` 之类**字符串猜能力**（`HBVideoEncoder.cs:166-179`）；② 30+ 平铺字段的 `Video*`/`Picture*` 命名空间靠前缀分组而无类型承载；③ GPL v2 代码直接抄。
- **不确定**：`hb_set_anamorphic_size2` 内 PAR/DAR 分支有历史遗留（`PictureItuPAR` 等），本项目只需其 mod/keep/upscale 子集，不必全量移植。

### 2.2 VidCoder

**定位 / 技术栈 / 许可 / 规模**：MIT。C#/.NET + WPF + SQLite；约 460 文件 / 2 MB。是 HandBrake 的 Windows 前端，**通过 `EncodeProxy`/`HandBrakeWorker` 子进程调用 libhb**（进程隔离，崩了不拖垮 UI）。工程分 `VidCoder`、`VidCoderCommon`（模型/JSON 工厂）、`VidCoderWorker`、`VidCoderCLI`、`VidCoderWindowlessCLI`、`VidCoderFileWatcher`。

#### 预设数据模型【源码】

两层：`Preset`（容器）+ `VCProfile`（编码设置），另有 `Picker`（**文件/轨道选择规则，与编码预设正交**）。

`VidCoder/VidCoderCommon/Model/Preset/Preset.cs`：`class Preset : ReactiveObject`，字段 `Name`、`FolderId`(0=根 "Custom")、`IsBuiltIn`、`IsModified`、`IsQueue`、`EncodingProfile(VCProfile)`。

`VidCoder/VidCoderCommon/Model/Preset/VCProfile.cs` 字段：容器/输出（`PreferredExtension(VCOutputExtension)`、`IncludeChapterMarkers`、`Optimize`、`AlignAVStart`、`IPod5GSupport`、`ContainerName`）；尺寸（`SizingMode(VCSizingMode{Automatic,Manual})`、`Width`、`Height`、`CroppingType`、`CroppingMinimum=2`、`CroppingConstrainToOneAxis`、`Cropping(VCCropping)`、`PaddingMode`、`UseAnamorphic`、`ScalingMode`、`PixelAspectX/Y`）；滤镜（`Detelecine`/`CustomDetelecine`、`DeinterlaceType`/`DeinterlacePreset`/`CustomDeinterlace`、`CombDetect`、`DenoiseType`/`DenoisePreset`/`DenoiseTune`/`CustomDenoise`、`ChromaSmooth*`、`SharpenType`/`SharpenPreset`/`SharpenTune`/`CustomSharpen`、`Deblock*`、`ColorspacePreset`、`Grayscale`）；视频（`VideoProfile`、`VideoLevel`、**`VideoEncodeRateType`**、`Quality(decimal)`、`TargetSize(double, 单位 MB)`、`VideoBitrate(int, kbps)`、`MultiPass`、`TurboFirstPass`、`ConstantFramerate`、`Framerate`、`VideoOptions`、`VideoEncoder`、`VideoTunes(List<string>)`、`Rotation`、`FlipHorizontal/Vertical`）；音频（`AudioEncoderFallback`、`TwoPass`、`AudioCopyMask`）。

**Picker 字段**（`VidCoder/VidCoder/Model/Picker.cs`，295 行）：`IsDefault`(特殊 "None" 预设) :35、`IsModified` :39、`Name` :47、`DisplayName` :288；`OutputDirectory` :53、`UseCustomFileNameFormat` :55、`OutputFileNameFormat` :57、`OutputToSourceDirectory` :61、`PreserveFolderStructureInBatch` :65、`WhenFileExistsSingle=Prompt` :67、`WhenFileExistsBatch=AutoRename` :69；标题重写 `ChangeWordSeparator=true` :74、`WordSeparator=" "` :79、`WordBreakCharacters=[" ","_"]` :81-90、`ChangeTitleCaptialization` :95、`TitleCapitalization=EveryWord` :102；扫描 `VideoFileExtensions`("avi,mkv,mp4,m4v,mpg,mpeg,mov,webm,wmv" @ `VidCoder/VidCoderCommon/Utilities/PickerDefaults.cs:11`)、`IgnoreFilesBelowMb=30` :109、`TitleRangeSelectEnabled/StartMinutes=40/EndMinutes=50` :113-132、`WidthFilterEnabled/WidthFilterDirection(=true 表示大于)/WidthFilterValue=640` :134-141、`HeightFilter*` :143-150、`PickerTimeRangeMode` :154、`ChapterRangeStart/End` :160-172、`TimeRangeStart/TimeRangeEnd=10min` :174-186；音频 `AudioSelectionMode=Disabled` :188、`AudioIndices="1"` :190、`AudioLanguageCodes` :194、`AudioLanguageAll` :201；字幕 `SubtitleSelectionMode=Disabled` :207、`SubtitleAddForeignAudioScan=true` :209、`SubtitleIndices="1"` :211、`SubtitleDefaultIndex` :213、`SubtitleLanguageCodes` :217、`SubtitleLanguageAll` :224、`SubtitleLanguageOnlyIfDifferent=true` :228、`SubtitleDefault` :231、`SubtitleForcedOnly` :234、`SubtitleBurnInSelection=ForeignAudioTrack` :236、`EnableExternalSubtitleImport`/`ExternalSubtitleImportLanguage="eng"`/`Default`/`BurnIn` :242-248；其它 `PassThroughMetadata=true` :253、**`UseEncodingPreset` :256 + `EncodingPreset` :263（Picker 可携带一个编码预设名）**、`AutoQueueOnScan` :269、`AutoEncodeOnScan` :271、`PostEncodeActionEnabled/Executable/Arguments="\"{file}\""` :273-277、`SourceFileRemoval=Disabled` :279、`SourceFileRemovalTiming=AfterClearingCompletedItems` :281、`SourceFileRemovalConfirmation=true` :283、**`[JsonExtensionData] Dictionary<string,object> ExtensionData` :293（未知/废弃字段隔离区）**。

模式枚举：`AudioSelectionMode { Disabled=0, None=5, First=3, ByIndex=4, Language=1, All=2 }` @ `.../Model/AudioSelectionMode.cs`（**数值故意非顺序，保证旧 JSON 的数值含义永不变**）；`SubtitleSelectionMode { Disabled=0, None=4, First=5, ByIndex=6, Language=2, All=3, ForeignAudioSearch=1 }`；`SubtitleBurnInSelection { None, ForeignAudioTrack, First, ForeignAudioTrackElseFirst }`；`PickerTimeRangeMode { All, Chapters, Time }`；`WhenFileExists { Prompt, Overwrite, AutoRename, Skip }`；`SourceFileRemoval { Disabled, Recycle, Delete }`。

#### 质量 vs 码率【源码】

**三模式互斥（比 HandBrake 多出 TargetSize）**：`enum VCVideoEncodeRateType { AverageBitrate = 1, ConstantQuality = 2, TargetSize = 3 }` @ `VidCoder/VidCoderCommon/Model/Preset/VCVideoEncodeRateType.cs`。

落盘互斥 switch @ `VidCoder/VidCoderCommon/Model/JsonEncodeFactory.cs`：`TargetSize` → `quality = profile.TargetSize`；`AverageBitrate` → `video.Bitrate = profile.VideoBitrate; video.MultiPass = profile.MultiPass; video.Turbo = profile.TurboFirstPass;`；`ConstantQuality` → `video.Quality = profile.Quality;`（**只有 CRF 分支不带 MultiPass/Turbo**）；`default: throw new ArgumentOutOfRangeException()`。

**目标体积 → 码率换算（可直接复用的公式）** `public int CalculateBitrate(VCJob job, SourceTitle title, double sizeMb, double overallSelectedLengthSeconds = 0)` @ `VidCoder/VidCoderCommon/Model/JsonEncodeFactory.cs`：
```
outputFramerate = profile.Framerate == 0 ? title.FrameRate.Num/Den : profile.Framerate
frames = (long)(lengthSeconds * outputFramerate)
containerOverheadBytes = frames * ContainerOverheadBytesPerFrame
availableBytes = targetBytes - containerOverheadBytes - GetAudioSize(lengthSeconds, title, resolvedAudio, profile.AudioCopyMask)
if (availableBytes < 0) return 0;
resultBitrateKilobitsPerSecond = (int)(availableBytes / (125 * lengthSeconds))   // 1 kbps = 125 bytes/s
```
调用点 `VidCoder/VidCoder/ViewModel/Panels/VideoPanelViewModel.cs:709`，默认 `DefaultTargetSizeMB = 700` @ `:26`。

#### 参数校验与按编码器净化【源码】

无独立 sanitize 模块：**净化就是「HandBrake 的 clean 结果 + VidCoder 侧 profile 合法性检查」**。`VidCoder/VidCoder/Model/EncodeJobStorage.cs:91 PresetStorage.ErrorCheckEncodingProfile(...)`（**载入队列时校验 profile 合法性**）。UI 侧 `VidCoder/VidCoder/Services/EncodeSettingOverrideService.cs` 用 `WhenAnyValue` 订阅 `SelectedPreset.Preset.EncodingProfile.{CroppingType,Rotation,FlipHorizontal,FlipVertical}` @ `:75/:85/:89/:93`，实现响应式联动。

#### 编码器能力探测【源码】

**不自建探测，全部透传 HandBrake 的 `HBVideoEncoder`**（`CompatibleContainers`、`SupportsQuality`、`SupportsBitrate`、`Presets`/`Tunes`/`Profiles`/`Levels`）。`HardwareResourceService` 只按 `Config.CapNVEnc` 之类的**用户声明的能力开关**决定并发槽数，不主动探测硬件。

#### 预设版本与迁移【源码】

**JSON blob + 版本门 if 链 + ExtensionData 隔离区**（`VidCoder/VidCoder/Model/PickerStorage.cs`）：整条 Picker 序列化为 JSON 存 SQLite 表 `pickersJson`（`SerializePicker` 用 `JsonSerializer.Serialize(picker, JsonOptions.WithUpgraders)` @ `:22`；`SavePickers` 先 `DELETE FROM pickersJson` 再逐条 INSERT @ `:369-381`）。

迁移链 `UpgradePickerUpTo37(picker, oldDatabaseVersion)` @ `:85` → `UpgradePicker(picker, oldDatabaseVersion)` @ `:98`，逐版本 `if (oldDatabaseVersion < 41) UpgradePickerTo41(...)` / `<43` / `<45` / `<47`。例 `UpgradePickerTo41` @ `:289` 把 `ExtensionData["SubtitleBurnIn"]` 迁成 `SubtitleBurnInSelection` 再 `Remove`；`UpgradePickerTo43` @ `:322` 直接 `picker.ExtensionData = null`。

旧字段可空化的兼容写法：`[JsonPropertyName("OutputToSourceDirectory2")]` @ `Picker.cs:61`、`[JsonPropertyName("PreserveFolderStructureInBatch2")]` @ `:65` —— **改名靠换 key 名，不靠迁移代码**。

#### 队列与任务模型【源码】

**轨道自动选择引擎（本项目「不静默丢轨」的核心参考）**，`VidCoder/VidCoder/Services/ProcessingService.cs`：
- `ChooseAudioTracks(IList<SourceAudioTrack> audioTracks, Picker picker)` @ `:3265`：`Disabled` 抛 `ArgumentException("Disabled is an invalid mode.")`；`None` 直接 break（**唯一允许空结果的模式**）；`First` 取 `[0]`；`ByIndex` 用 `ParseUtilities.ParseCommaSeparatedListToPositiveIntegers(picker.AudioIndices)` 且 `Where(i => i <= audioTracks.Count)` 过滤越界（1-based）；`Language` → `ChooseAudioTracksFromLanguages(...)`；`All` → 先按语言排序再补齐其余。
- **安全网 @ `:3324-3328`**：`if (result.Count == 0 && audioTracks.Count > 0 && picker.AudioSelectionMode != AudioSelectionMode.ByIndex && picker.AudioSelectionMode != AudioSelectionMode.None) { result.Add(audioTracks[0]); }` —— **除显式 None/显式索引外永不允许空结果**。
- `AutoPickAudio(VCJob job, SourceTitle title, bool useCurrentContext = false, Picker picker = null)` @ `:3207`：`Disabled` = 沿用当前 UI 选中的轨，匹配不上回落第一轨 @ `:3235-3238`。
- `ChooseAudioTracksFromLanguages(...)` @ `:3340`：先按 `track.LanguageCode == code` 精确匹配，再退回「轨名包含语言的 NativeName 或 EnglishName（忽略大小写）」模糊匹配 @ `:3351-3356`。
- 字幕 `ChooseSubtitles(SourceTitle title, Picker picker, int chosenAudioTrack, string containerName)` @ `:3469`：`int containerId = HandBrakeEncoderHelpers.GetContainer(containerName).Id` @ `:3474`；`SubtitleAddForeignAudioScan` 时插入伪轨 `TrackNumber = 0, ForcedOnly = true` @ `:3476-3485`；`First` 模式 `BurnedIn = picker.SubtitleBurnInSelection.FirstTrackIncluded() || !HandBrakeEncoderHelpers.SubtitleCanPassthru(...)` @ `:3499-3500` —— **容器不支持直通时改为「烧入」而不是丢弃**；`ByIndex` 模式多轨时先剔除不能直通的 @ `:3514-3523`。

**设置分层与继承（三层）**：① 全局 `Config`（SQLite 键值；`Config.EncodeRetries` 默认 0 @ `VidCoder/VidCoder/Model/Config/Config.cs:147`、`Config.KeepFailedFiles` 默认 false @ `:131`）→ ② `Preset.EncodingProfile`（可编辑草稿，用 `OriginalProfile` 保留原件：`VidCoder/VidCoder/Services/PresetsService.cs:403 EncodingProfile = this.SelectedPreset.Preset.EncodingProfile.Clone()`、`:462` 回滚、`:735 SetEncodingProfileSilent(newProfile)`、`:771 EncodingProfile = presetVM.OriginalProfile`；Picker 同理 `PickersService.cs:322/278` 的 `ModifyPicker`/`RevertPicker` + `OriginalPicker`）→ ③ **入队时按 Picker 覆盖** `ProcessingService.cs:1618 if (picker.UseEncodingPreset && !string.IsNullOrEmpty(picker.EncodingPreset) && allowPickerProfileOverride)` → `:1635 encodeJobViewModel.Job.EncodingProfile = overridePresetViewModel.Preset.EncodingProfile.Clone();`。任务快照 `ProcessingService.cs:1537 EncodingProfile = profile.Clone()`、`:667 job.Job.EncodingProfile = newProfile`。

**并发 = 硬件池**：`class HardwarePool` @ `VidCoder/VidCoder/Model/HardwarePool.cs`：`SlotCount`、`CanAcquireSlot() => jobs.Count < SlotCount` @ `:36`、`AcquireSlot(job)`（超限抛 `InvalidOperationException($"Hardware pool {this.Name} has no slots available for {job.Job.FinalOutputPath} .")` @ `:47`）、`ReleaseSlot(job)`。`VidCoder/VidCoder/Services/HardwareResourceService.cs:26-37` 持有 `qsvPool/nvencPool/vcePool/mfPool/totalPool` + `discDrivePools`；构造 `:51-55`：`new HardwarePool("QSV", qsvCount)`、`new HardwarePool("NVEnc", GetNVEncSlotCount(Config.CapNVEnc))`、`new HardwarePool("VCE", 3)`、`new HardwarePool("MF", 1)`、`new HardwarePool(TotalPoolName, Config.MaxSimultaneousEncodes)`；`:162 string videoEncoder = job.Job.EncodingProfile.VideoEncoder;` 决定需要哪些池；`:89-92 GetRequiredHardware(job)` 逐个 `CanAcquireSlot()`。

重试 `RetryJobIfNeeded(EncodeJobViewModel)` @ `ProcessingService.cs:1587`：`if (encodeJobViewModel.FailedTries < Config.EncodeRetries) { FailedTries++; int encodingItemCount = this.EncodeQueue.Items.Count(i => i.Encoding); this.EncodeQueue.Insert(encodingItemCount, encodeJobViewModel); }` —— **重试插到「正在编码」之后，不抢占**。

**失败解释** @ `ProcessingService.cs:2409-2513`：**成功码但产物缺失也判失败** —— `:2415 "Encode failed. HandBrake reported no error but the expected output file at {InProgressOutputPath} was not found."`、`:2423 "...but the output file was empty."`；`TryHandleFailedFile(FileInfo, logger, reason, finalOutputPath)` @ `:2853` 按 `Config.KeepFailedFiles` 保留/改名/删除失败产物。

错误码分段 `enum VCEncodeResultCode` @ `VidCoder/VidCoderCommon/Model/VCEncodeResult.cs`：**HandBrake 段 0-5 保留原义** `Succeeded=0, Canceled=1, ErrorWrongInput=2, ErrorInit=3, ErrorUnknown=4, ErrorRead=5`，**VidCoder 自有段从 100 起** `ErrorHandBrakeProcessCrashed=100, ErrorProcessCommunication=101, ErrorCouldNotCreateOutputDirectory=102, ErrorScanFailed=103`。

持久化：`VidCoder/VidCoder/Model/EncodeJobStorage.cs` — `static IList<EncodeJobWithMetadata> EncodeJobs` @ `:12`、`LoadQueueFile(queueFile)` @ `:31`、`SaveQueueToFile(jobs, filePath)` @ `:57`。`EncodeJobWithMetadata` 字段 @ `VidCoder/VidCoder/Model/EncodeJobWithMetadata.cs`：`Job(VCJob)`、`SourceParentFolder`、`ManualOutputPath`、`NameFormatOverride`、`PresetName`、`PickerName`、`VideoSource`、`VideoSourceMetadata` —— **存「预设名 + Picker 名」而非只存最终结果，可追溯来源**。

#### 可复用 / 可借鉴 / 不采纳

- **可复用**：① 轨道选择模式枚举 + **「除 None/显式索引外永不为空」安全网**（直接解决本项目「静默丢轨」）；② 「容器不支持直通 → 烧入而非丢弃」的降级策略；③ 目标体积→码率换算公式（含容器开销与音轨占用）；④ 三模式互斥的 switch 落盘；⑤ 错误码分段（外部 0-99 / 自有 100+）。
- **可借鉴**：① `ExtensionData` 隔离未知字段 + 版本门 if 链迁移；② 旧字段改名用 `...2` 后缀换 key 而非写迁移；③ 三层合并（全局默认 → 预设草稿 → 入队时覆盖），并用 `Clone()` 快照到任务；④ 队列项存「预设名 + Picker 名」以可追溯；⑤ 硬件池式并发（按编码器家族分池 + 总池）；⑥ 载入时校验 profile 合法性。
- **不建议采纳**：① `AudioSelectionMode` 那种故意乱序的枚举数值（本项目未发布，无历史包袱，`AGENTS.md` 明确允许破坏性变更）；② 完全依赖外部库做能力探测（本项目没有 libhb，必须自建 `MediaCapabilityProbe`）。
- **不确定**：Picker 的 `WidthFilterDirection` 用 bool 表示「大于」是隐晦设计，不值得照抄。

### 2.3 FastFlix

**定位 / 技术栈 / 许可 / 规模**：MIT。Python 3.13+ / PySide6(Qt6)，要求 FFmpeg 8.0+；约 239 文件 / 1.7 MB。**多进程**：`entry.py` 主进程 → `application.py` GUI 进程 → `conversion_worker.py` 工作进程（`fastflix/conversion_worker.py:26 def queue_worker(gui_proc, worker_queue, status_queue, log_queue)`）。
**编码器插件化**（`FastFlix/CLAUDE.md` 明示）：每个编码器一个目录 `fastflix/encoders/{name}/`，含 `__init__.py`（元数据/注册）、`command_builder.py`（`build(fastflix) -> List[Command]`）、Pydantic 设置模型（`fastflix/models/encode.py`）、UI 面板 `widgets/panels/{name}/`、测试 `tests/encoders/test_{name}_command_builder.py`。

#### 预设数据模型【源码】

`fastflix/models/profiles.py`：`class Profile(BaseModel)` @ `:146`：
- **版本**：`profile_version: Optional[int] = 1`（**显式版本号字段**）
- 几何：`auto_crop=False`、`fast_seek=True`、`rotate=0`、`vertical_flip`、`horizontal_flip`
- 容器/元数据：`copy_chapters=True`、`remove_metadata=True`、`remove_hdr=False`、`output_type=".mkv"`
- 编码器：`encoder: str = "HEVC (x265)"`、`resolution_method: str = "auto"`、`resolution_custom: str|None`
- 轨道：`audio_filters: Optional[list[AudioMatch]|bool]`、`data_passthrough: Optional[bool]`
- **legacy 字段区**（仅用于导入旧 profile）：`audio_language`、`audio_select`、`audio_select_preferred_language`、`audio_select_first_matching`、`subtitle_language`、`subtitle_select`、`subtitle_select_preferred_language`、`subtitle_automatic_burn_in`、`subtitle_select_first_matching` @ `:165-175`
- `advanced_options: AdvancedOptions` @ `:177`
- **每个编码器一个 Optional 子模型**（`x265/vvc/x264/rav1e/svt_av1/svt_av1_avif/vp9/aom_av1/gif/gifski/webp/modify_settings/copy_settings/ffmpeg_hevc_nvenc/ffmpeg_av1_nvenc/qsvencc_hevc/qsvencc_av1/qsvencc_avc/nvencc_hevc/nvencc_avc/nvencc_av1/vceencc_hevc/vceencc_av1/vceencc_avc/hevc_videotoolbox/h264_videotoolbox/vaapi_h264/vaapi_hevc/vaapi_vp9/vaapi_mpeg2`）全部 `Optional[...] = None` @ `:179-208` —— **一个 profile 装下所有编码器的设置，只填当前 encoder 对应的那个；切换编码器不丢另一套设置**。

`class AdvancedOptions(BaseModel)` @ `:125`：`video_speed=1`、`deblock`、`deblock_size=16`、`tone_map="hable"`、`vsync`、`brightness`、`saturation`、`contrast`、`maxrate: Optional[int]`、`bufsize: Optional[int]`、`source_fps`、`output_fps`、`color_space`、`color_transfer`、`color_primaries`、`denoise`、`denoise_type_index`、`denoise_strength_index`。

轨道规则 `class AudioMatch(BaseModel)` @ `:66`：`match_type(Union[MatchType,list])`、`match_item(Union[MatchItem,list])`、`match_input="*"`、`conversion`、`bitrate`、`downmix`、`title_mode=ORIGINAL`、`custom_title`；枚举 `MatchItem { ALL=1, TITLE, TRACK, LANGUAGE, CHANNELS }` @ `:45`、`MatchType { ALL=1, FIRST, LAST }` @ `:53`、`TitleMode { ORIGINAL=1, NO_TITLE, GENERATE, CUSTOM }` @ `:59` —— **规则 =（匹配对象, 匹配方式, 匹配值）→ 动作（转码/码率/下混/改名）**，比 VidCoder 的「模式枚举」更可组合。校验器：`downmix_as_string` 把 1..8 映射为 mono/stereo/2.1/3.1/5.0/5.1/6.1/7.1 @ `:99-109`；`bitrate_k_end` 自动补 `k` @ `:111-116`；枚举校验器容忍 YAML 存成 list（取 `v[0]`）@ `:76-97`。

每编码器设置类 @ `fastflix/models/encode.py`：`class EncoderSettings(BaseModel) { max_muxing_queue_size: str = "1024"; pix_fmt: str = "yuv420p10le"; extra: str = ""; extra_both_passes: bool = False }` @ `:72-76`（**共同字段基类**）；`class x265Settings(EncoderSettings) { name: str = "HEVC (x265)"  # MUST match encoder main.name; preset="medium"; crf: Optional[Union[int,float]] = 22; bitrate: Optional[str] = None; bframes=4; aq_mode=2; bitrate_passes: int = 2; ... }` @ `:79-101`；`class VVCSettings` @ `:104`（用 `qp` 而非 `crf` @ `:107`）；`class x264Settings` @ `:118`（`crf=23` @ `:127`、`bitrate=None` @ `:128`、`level="auto"` @ `:126`、`pix_fmt="yuv420p"` @ `:123`）；`class FFmpegNVENCSettings` @ `:133`（`preset="slow"` @ `:135`）。

#### 质量 vs 码率【源码】

**单一返回值的互斥模型**，`fastflix/encoders/common/setting_panel.py`：
- 推荐值表 `recommended_bitrates = ["150k","276k","512k","1024k","1800k","3000k","4000k","5000k","6000k","7500k","9000k","10000k","12000k","15000k","17500k","20000k","25000k","30000k","40000k","50000k","Custom"]` @ `:23-45`；`recommended_qp = ["16"..."32","Custom"]` @ `:47-66` —— **离散档位 + Custom，默认不让用户输任意值**。
- `def _add_modes(self, recommended_bitrates, recommended_qps, qp_name="crf", add_qp=True, disable_custom_qp=False, show_bitrate_passes=False, disable_bitrate=False, qp_display_name=None)` @ `:368` —— **每个编码器插件调用它声明「我有哪些质量模式」**：`disable_bitrate=True` 则整块码率 UI 根本不创建 @ `:398`；`qp_name` 决定叫 crf/qp/qscale @ `:372`；`disable_custom_qp` 关掉自定义框 @ `:452`。UI 用 `QButtonGroup widgets.mode` + 两个 `QRadioButton`（`qp_radio` 默认 checked @ `:439`、`bitrate_radio` @ `:399`），切换信号 `self.widgets.mode.buttonClicked.connect(self.set_mode)` @ `:392`。
- `def get_mode_settings(self) -> Tuple[str, Union[float, int, str]]` @ `:632`：Bitrate 分支返回 `("bitrate", "3000k")`（Custom 且空 → `logger.error("No custom bitrate provided, defaulting to 3000k")` 后回落 @ `:637-640`）；QP 分支返回 `("qp", 30)`（空/非数字 → 回落 30 @ `:649-655`）。**一次只返回一个模式的一个值，永不并存；非法输入不抛异常，回落默认 + 记日志**。
- `update_profile()` @ `:516` / `reload()` @ `:576`：读配置后**按「哪个字段有值」反推模式** —— `bitrate` 有值 → `self.mode = "Bitrate"` + `bitrate_radio.setChecked(True)`，在推荐列表里 `rec.startswith(bitrate)` 匹配，匹配不上则 `setCurrentText("Custom")` 并把裸数值填进自定义框 @ `:541-551`；否则 `self.mode = self.qp_name; self.qp_radio.setChecked(True)` @ `:553-563`。
- `determine_default(widget_name, opt, items: List, raise_error: bool = False)` @ `:97`：把配置值映射回下拉索引，含大量特例（`pix_fmt` 去前缀 @ `:99`、`crf/qp/qscale` 去 `(...)`/`-` 后缀 @ `:101`、`bitrate` 同理 @ `:106`、`auto_alt_ref/lag_in_frames/aq_mode/sharpness` 的 `-1`→0、`gpu`）；匹配失败先按 `" - "` 前缀再试 @ `:135-138`，仍失败且原值是合法 int 就当索引用 @ `:141`。未设值时 `crf` 默认索引 6、`bitrate` 默认索引 5 @ `:103/:108`。
- **目标体积：不支持**。全仓库 grep `target_size`/`TargetSize` 无命中；只有「离散码率档 + 离散质量档」。

#### 参数校验与按编码器净化【源码】

**净化 = Pydantic 模型校验 + per-encoder 面板只创建合法控件**。没有集中式 sanitize 函数。三重防线：① `disable_bitrate` / `disable_custom_qp` 等开关让非法控件**根本不出现** @ `setting_panel.py:398/:452`；② Pydantic `field_validator` 归一化 @ `models/profiles.py:76-116`；③ `get_mode_settings` 对空/非法值回落默认 + `logger.error` 而不抛 @ `:637-655`。编码器切换时靠「profile 里 30 个 Optional 子模型」实现「切回来不丢设置」，无需净化掉其它编码器的字段 @ `models/profiles.py:179-208`。

#### 编码器能力探测【源码】

`fastflix/application.py:88 def init_encoders(app: FastFlixApp, **_)`：显式 import 31 个插件；`encoders = [hevc_plugin, nvenc_plugin, hevc_videotoolbox_plugin, h264_videotoolbox_plugin, ffmpeg_av1_nvenc_plugin, av1_plugin, rav1e_plugin, svt_av1_plugin, svt_av1_avif_plugin, avc_plugin, vp9_plugin, gif_plugin, webp_plugin, vvc_plugin, vaapi_hevc_plugin, vaapi_h264_plugin, vaapi_vp9_plugin, vaapi_mpeg2_plugin, copy_plugin, modify_plugin]` @ `:120-141`；按运行时条件插入 `if DEVMODE or app.fastflix.config.gifski: encoders.insert(encoders.index(gif_plugin)+1, gifski_plugin)` @ `:143`、`if app.fastflix.config.qsvencc: encoders.insert(1, qsvencc_plugin)` @ `:157-163`。

**可用性判定（两路证据）**：`requires_to_encoder = {"cuda-llvm": "nvenc"}` @ `:185-187`；`def _encoder_available(requires: str) -> bool: if requires in app.fastflix.ffmpeg_config: return True; search_term = requires_to_encoder.get(requires, requires); return any(search_term in enc for enc in (app.fastflix.video_encoders or []))` @ `:189-193` —— 即 **`ffmpeg -buildconf` 的 configure 标志 + `ffmpeg -encoders` 的输出**。最终 `app.fastflix.encoders = {encoder.name: encoder for encoder in encoders if (not getattr(encoder, "requires", None)) or _encoder_available(encoder.requires) or DEVMODE}` @ `:195-199` —— **字典按 name 索引，插件用 `requires` 字段自声明依赖，未满足则整个插件不出现**（**本项目 `MediaCapabilityProbe` 最值得抄的形状**）。

硬件探测另一路 `fastflix/gpu_detect.py`：`@dataclass class DetectGPU { NVIDIA: bool=False; AMD: bool=False; INTEL: bool=False; fingerprint: str=None; exist() }` @ `:14-32`；`windows_gpu_detect()` 用 `wmi.WMI().Win32_VideoController()`，按 `adapter.AdapterCompatibility.split()[0].upper()` 分类，`fingerprint = "~~".join(sorted(VideoProcessor))` @ `:35-54`（**指纹用于「同一台机器只探测一次」——可直接映射为本项目 `DeviceMediaCapabilities.capturedAtEpochMillis` 的缓存键**）。

探测失败降级：`fastflix/models/config.py:510 def check_hw_encoders(self)` 调 `get_all_encoder_formats_and_devices(self.nvencc, is_nvenc=True)` / `is_vce=True` / `is_qsv=True`，每个都 try/except，失败置空列表 @ `:511-540` —— **探测失败 = 该编码器不可用，不崩**。

#### 滤镜链与尺寸处理【源码】

`Profile.auto_crop`（bool，自动裁剪黑边）、`resolution_method: str = "auto"` + `resolution_custom: str|None`（**分辨率用「方法名 + 自定义表达式」而非宽高数值**）、`rotate=0`/`vertical_flip`/`horizontal_flip`（三个独立 bool/int，不合并成矩阵）、`AdvancedOptions.denoise` + `denoise_type_index` + `denoise_strength_index`。尺寸最终交给 FFmpeg `scale` 滤镜表达式生成，本仓库无独立几何求解器 → 对尺寸算法**无可复用**，但「`resolution_method` 字符串 + 可选自定义」这种**把算法选择外置为枚举**的做法可借鉴。

#### 预设版本与迁移【源码】

`Profile.profile_version: Optional[int] = 1` @ `models/profiles.py:146` —— **显式版本号**（对比 HandBrake 的三段 major/minor/micro、VidCoder 的「无版本号靠 DB 版本门」）。

内置预设 `def get_preset_defaults()` @ `fastflix/models/config.py:44`：`{"Standard Profile": Profile(x265=x265Settings()), "UHD HDR10 Film": Profile(auto_crop=True, x265=x265Settings(crf=18, hdr10=True, hdr10_opt=True, repeat_headers=True, preset="slow")), "1080p Film": Profile(auto_crop=True, encoder="AVC (x264)", x264=x264Settings(crf=22, preset="slow"))}` —— **三个内置预设 = 质量目标 + 分辨率的自然语言命名，不暴露 CRF 数值给用户**（CRF 只写进 `x265Settings`，正好符合本项目产品决策）。

持久化 `Config.save()` @ `:542`：`model_dump()`，Path 转 str，**`items["profiles"] = {k: json.loads(v.json()) for k, v in self.profiles.items() if k not in get_preset_defaults().keys()}`（只持久化用户自定义预设，内置预设不入盘）**，最后 `Box(items).to_yaml(filename=self.config_path, default_flow_style=False)`；`@property profile` → `self.profiles[self.selected_profile]` @ `:554`。

#### 参数预览【源码】

`fastflix/widgets/panels/command_panel.py`：`def _command_to_display_string(command)` @ `:17`（str 直返；Windows 用 `subprocess.list2cmdline(command)`，其他平台 `shlex.join(command)`）；`class Command(QtWidgets.QTabWidget)` @ `:39` 接收 `(command, number, name="", enabled=True, height=None)`，内部只读 `QtWidgets.QTextBrowser()` @ `:42-44`，`self.widget.setDisabled(not enabled)` @ `:49`；`class Loop(QtWidgets.QGroupBox)` @ `:27` 表示「循环执行的命令组」。**即：把最终参数拆成命名分组 + 编号 + 禁用态直接展示给用户。** 触发重算 `fastflix/widgets/main.py:2002 def build_commands(self) -> bool`。

#### 队列与任务模型【源码】

`fastflix/widgets/panels/queue_panel.py`：`class EncodeItem(QtWidgets.QTabWidget)` @ `:49`；`class EncodingQueue(FlixList)` @ `:219`，方法 `queue_startup_check(queue_file=None)` @ `:326`、`manually_save_queue()` @ `:360`、`manually_load_queue()` @ `:371`、`reorder(update=True)` @ `:394`、`new_source()` @ `:416`、`clear_complete()` @ `:437`、`remove_item(video, part_of_clear=False)` @ `:443`、`reload_from_queue(video)` @ `:461`、`reset_pause_encode()` @ `:469`、`pause_resume_queue()` @ `:474`、`pause_resume_encode()` @ `:486`、`set_after_done()` @ `:509`、`retry_video(current_video)` @ `:525`、`move_up/move_down` @ `:535/:539`、`add_to_queue()` @ `:543`、`run_after_done()` @ `:590`、`set_priority()` @ `:602`。

**`queue_startup_check` @ `:326`**：`new_queue = get_queue(queue_file or self.app.fastflix.queue_path)`；**移除 `video.status.complete` 的项、其余 `video.status.clear()`（把 InProgress/失败状态清空回到待处理）** @ `:330-337`；自动恢复时弹 `yes_no_message("Not all items in the queue were completed\nWould you like to keep them in the queue?", title="Recover Queue Items")` @ `:342-346` —— **与 HandBrake `RestoreQueue` 同构的「载入时状态复位」**。队列文件 = **YAML**（`filter="FastFlix Queue File (*.yaml)"` @ `:365/:373`）。

失败判定 `fastflix/conversion_worker.py`：`process_failed = (...)` @ `:72`，`if runner.error_detected or process_failed: logger.info(t("Error detected while converting")); status_queue.put(("error", video_uuid, command_uuid))` @ `:75-78` —— **既看进程返回码也看 stderr 里检测到的错误关键字（注释说明 Windows 上返回码不可靠）**。

#### 可复用 / 可借鉴 / 不采纳

- **可复用**：① **插件用 `requires` 字段自声明依赖、不满足则整体不注册**（`application.py:189-199`）→ 直接对应本项目 `MediaCapabilityProbe`；② 「探测失败 = 置空列表」的降级约定（`config.py:511-540`）；③ 探测指纹 + 只探测一次（`gpu_detect.py:35-54`）；④ `get_mode_settings` 的「单一返回值 + 非法回落默认」形状；⑤ 离散档位表（`recommended_bitrates`/`recommended_qp`）——**正好匹配本项目「不暴露 CRF/码率任意输入」**；⑥ 内置预设用「质量目标 + 分辨率」命名。
- **可借鉴**：① per-encoder Optional 子模型（切编码器不丢设置）；② `_add_modes` 式「每个编码器声明自己有哪些模式」；③ 参数预览面板（命名分组 + 编号 + 禁用态）；④ 只持久化用户自定义预设、内置预设不入盘；⑤ `profile_version` 显式版本号；⑥ 队列载入时状态复位 + 询问用户是否保留未完成项；⑦ 「返回码 + stderr 关键字」双判失败。
- **不建议采纳**：① `determine_default` 那种「失败就把 int 当索引用」的容错（`setting_panel.py:141`）——隐式行为，难调试；② `resolution_method: str` 自由字符串（无类型约束，易存非法值）；③ 不支持目标体积（本项目产品决策需要「体积目标」，需自建）。
- **不确定**：`fastflix/models/config.py` 里 `check_hw_encoders` 用外部可执行文件探测硬件；本项目只有 MediaCodec，需换成 `MediaCodecList` 遍历，形态可借鉴但实现不可搬。

### 2.4 lossless-cut

**许可**：**GPL-2.0，传染性，只可借鉴思路不可抄代码**。Electron + React + TypeScript，调外部 ffmpeg/ffprobe；`src/` 约 105 代码文件 / 518 KB。
主战场：`src/renderer/src/hooks/useFfmpegOperations.ts`（约 1150 行，全部命令构造）、`src/renderer/src/util/streams.ts`（471 行）、`src/main/progress.ts`（46 行）、`src/renderer/src/smartcut.ts`（94 行）、`src/renderer/src/ffmpeg.ts`（613 行）。

#### 命令构造【源码】

单一入口 `losslessCutSingle()`（`useFfmpegOperations.ts:255-503`），参数顺序（`:457-496`）`-hide_banner → -itsscale → -display_rotation:v:0 → 输入(含 -ss/-t) → 章节输入 → -avoid_negative_ts → -copyinkf → -map ... → -map_metadata → -map_chapters → -shortest → mov/matroska flags → -metadata → 自定义参数 → -ignore_unknown → -video_track_timescale → -f <fmt> -y <out>`。

- **用 `-t <duration>` 而非 `-to`**（`:293-295`，issue #50）：`cutFromArgs = ['-ss', cutFrom]`；`cutToArgs = ['-t', cutTo-cutFrom]`。`-to` 是输出时间戳语义，与 `-ss` 前移的输入偏移会串味。
- 起止为 0 / 总时长时**不加对应参数**（`isCuttingStart`/`isCuttingEnd`，`:283-287`）。
- `-ss` 位置由「关键帧切割」开关决定（`:309-334`）：开 = `-ss` 放 `-i` 前（快，落到前一关键帧）；关 = 放 `-i` 后（精确，慢，开头可能空一段）。多输入文件时**强制 `-ss` 在 `-i` 前、`-t` 在 `-i` 后**（issue #896）。
- **黑帧 / 时长错乱的三条独立防护**：① `-avoid_negative_ts` **只在「有 -ss 且是关键帧模式」时才加**（`:303`）——注释：不剪头时加上会让部分视频首帧变黑（QuickLook）；② 复制 `attached_pic`（封面图）时把 `make_zero`/`make_non_negative` **降级为 `auto`**（`streams.ts:308-316`，issue #3009）——封面 packet 无时间轴位置（pts 0），`-ss` 前移会把它拖成负数且最先到达 muxer，`make_zero` 据此推导全文件校正量把其它流又推回去，mov muxer 记录成 empty edit，**输出时长变成片段的源终点时间而非片段长度（ffprobe 看不出来，VLC 能看出来）**；③ 纯音频导出且 `-ss` 在 `-i` 后时加 `-copyinkf`（`:305-307`，`streams.ts:279-295`）——音频 stss 表可能几秒才标一个 keyframe，不加会晚几秒起头；对视频有害所以只用于纯音频。
- 音画不同步防护：`-shortest`、每输入 `-itsoffset`（`:320`）、章节作独立输入再 `-map_chapters`。
- **进度可解析性优先于安静日志**：注释 `:459-460` 明说 **"No progress if we set loglevel warning :("** → **不设 `-loglevel warning`**。

#### 关键帧对齐【源码】

检测用 ffprobe `-show_packets -select_streams N -show_entries packet=pts_time,flags`，`flags[0]==='K'`（`ffmpeg.ts:76-105`），用 `-read_intervals <from>%<to>` 只读窗口（`:82`）；**窗口两级退化**先 ±10s 再 ±60s（`findKeyframeNearTime` `:131-142`；`needsSmartCut` `smartcut.ts:20-38`），仍无 → `UserFacingError('Cannot find any keyframe after the desired start cut point')`；三模式 `'nearest' | 'before' | 'after'`（`ffmpeg.ts:112-129`）；`findKeyframeAtExactTime` 用 `|Δ|<0.000001` 判「已在关键帧上」（`:107`）——**「无需任何操作」的短路条件**。手动对齐 `alignSegmentTimesToKeyframes()`（`useSegments.tsx:503-520`）弹窗选 nearest/previous/next/opposing（`dialogs/index.tsx:294-307`）。

#### 多段切割与合并【源码】

`cutMultiple()`（`useFfmpegOperations.ts:572-737`），`getGuaranteedSegments()`（`segments.ts:297`）规范化，段**串行** `pMap(..., {concurrency: 1})`（`:733`）；输出已存在 → `shouldSkipExistingFile` 跳过（`:112-127, :623`）→ **断点续做**。

- **smart cut 两段法**（`:693-729`）：① 无损 remux `[losslessCutFrom, cutTo]`，**强制 `keyframeCut: true` 且 `avoidNegativeTs: undefined`**（`:702-704`）；② 编码 `[desiredCutFrom, encodeCutToSafe]`，**`encodeCutToSafe = max(desiredCutFrom + frameDuration, losslessCutFrom - frameDuration)`**（`:716`，注释：减一帧防拼接处重复帧，并防 0 长度段）；③ `concatFiles([encoded, lossless])`（`:723-725`）；④ `finally` 删中间文件。起止落在同一关键帧区间内（`losslessCutFrom > cutTo`）→ **整段编码，不做拼接**（`:682-687`）。编码码率 = 源码率 × **1.2**（`smartcut.ts:73`，issue #126 补偿系数）；探测不到则 `size*8/duration` 估算（`:62-69`）。
- 合并 `concatFiles()`（`:131-253`）用 concat demuxer **从 stdin 读列表**：`-f concat -safe 0 -protocol_whitelist file,pipe,fd -i -`（`:175-179`）；列表行 `file 'file:/abs/path.mp4'`（`:236`）——**必须带 `file:` 前缀**，否则新版 ffmpeg 报 `Impossible to open 'pipe:xyz.mp4'`；单引号转义为 `'\''`。**限制**（注释 `:212-215`）：`-map_metadata 0` 配 concat demuxer 拿不到被合并输入的元数据，metadata 源要用**第一个真实文件（输入 index 1）**，且**只有 `-map 0`（全轨）时才成立**。进度分母 = `sum(每段 getDuration)`（`:151-152`）。合并后 `concatCutSegments()`（`:739-763`）顺便按段生成章节。

#### 轨道选择【源码】

`getMapStreamsArgs()`（`streams.ts:209-235`）逐文件逐流 `-map <fileIndex>:<streamId>` + 每流 flags，**手工递增 outputIndex** 以生成 `-metadata:s:N` / `-disposition:N` / `-bsf:N`（输入→输出索引推算在 `useFfmpegOperations.ts:343-357`）。默认挑选 `getStreamIdsToCopy()`（`streams.ts:382-405`）：**第一条真视频 + 第一条音频 + 第一条字幕**；注释 `:390-393` 关键：**一旦要保元数据合并就必须显式 map 所有流**，否则 ffmpeg 自动选择会挑到 metadata 源输入。默认包含判定 `shouldCopyStreamByDefault()`（`:237-258`）：audio/attachment/video 全要；subtitle 除 `dvb_teletext`；data 只要 GoPro `bin_data`+`gpmd` tag；其余丢。`isStreamThumbnail()` = video 且 `disposition.attached_pic === 1`（`:262-264`）——封面图**不算真视频轨**。

无损改容器/流参数（很值得抄的一组技巧）：`-bsf:N h264_mp4toannexb|hevc_mp4toannexb|hevc_metadata=aud=insert`；**无损裁剪 `h264_metadata=crop_left=..`**（`:388-403`）；**无损改 SAR `h264_metadata=sample_aspect_ratio=num/den`**，非 h264/hevc 退化为容器级 `-aspect num:den`（`:405-421`）；`-display_rotation:v:0 <360-rotation>`（`:338`）；`-disposition:N 0`。

轨道 UI `StreamsSelector.tsx`：表列 `Keep? / Codec / Duration / Bitrate / Title / Language / Data / Disposition`（`:598-605`），逐行开关、批量「Toggle {{type}} tracks」（`:541-543`）、提取单轨（`:504`）、编辑单轨 metadata/crop/aspect/参数（`:293-363`）、底部「长短轨不一致时按最短/最长输出」（`:738-740`）。

#### 时间精度与实际起止【源码】

UI 与 ffmpeg 之间**全用秒（浮点）**，最终 `formatFfmpegNumber = (t) => t.toFixed(6)`（`src/common/util.ts:11`）→ **微秒精度**；帧只在 UI 层（`getFrameDuration = (fps) => 1/(fps ?? 30)`，`util.ts:143`）；`cutDuration = Math.max(cutDuration, frameDuration)` **保证至少一帧**。区间行同时显示三种表述（`SegmentList.tsx:274-283`）：`Duration <timecode>` + `<ms> ms, <N> frames` + `~<prettyBytes>`（**估算体积 = 段时长/总时长 × 文件大小**，`useSegments.tsx:738-743`）。**「请求起止 vs 实际起止」在 UI 上没有对比显示**——只有导出对话框一句警告：「某段输出可能比预期长得多，因为你的文件在该起点附近没有关键帧」（`ExportConfirm.tsx:245`）。**这是参考实现的真实缺口。**

#### UI 交互【源码】

时间轴 `Timeline.tsx` 可滚动 div，宽度 `zoom * 100%`（`:64, :418`），三层叠放：波形（`zoom===1` 用 overview 波形，放大后换切片波形，`:64-68`）→ 缩略图条（60px 高，宽度取到下一缩略图的 90%，`:404-411`）→ 区间层。区间层（`:421-449`）：`inverseCutSegments`（`BetweenSegments`，被剪掉区域，半透明）与 `cutSegments`（保留段，高亮）**互为补集**——正是 AB 循环区间需要的双语义可视化。拖拽（`:289-345`）需**先选中该段并按住修饰键**（`segmentMouseModifierKey`），命中判定 `threshold = (0.01/2)*duration/zoom`，三种操作 `'start'|'end'|'move'`，`move` 用 `offset = mousePos - seg.start` 保持抓取点；**普通点击/拖动 = 定位播放头**，防误改区间。关键帧刻度只在「不挤」时画：`areKeyframesTooClose = keyFramesInZoomWindow.length > zoom * 200`（`:167-171`）。

区间列表 `SegmentList.tsx`：每行 = 序号 + 起止时间码 + 名称 + 标签 chips + `Duration/timecode/ms/frames/~size` + 右下勾选圈（`:243-295`）；未选中行 `opacity: 0.5`（`:234`）；**行可拖拽排序**（dnd-kit `useSortable`，`:208-215`）；底部 `Segments total: <timecode>`（`:497-498`，只统计选中段，`:393`）；右键菜单四组（`:115-151`）。

**导出对话框 `ExportConfirm.tsx`（`ExportSheet` 宽 50em，`:350-357`）信息层级最值得抄**：① 顶部**通用 notice 区**（`:372`）：简单模式、无段可导、FLAC 已知问题、FPS+切割组合警告、**关键帧缺失导致输出过长警告**（`:229-245`）；② 主表三列（标签 | 控件 | 帮助/警告图标，`:370-468`），按重要性排序：**导出模式**（分离 / 自动合并 / 合并并保留 / 仅章节，`:257-260`）→ **输出容器格式** → **轨道**（`Input has N tracks` / `Keeping M tracks`，`:410-414`）→ **输出路径** → 文件名模板 → **覆盖已有文件**；③ 底部 `Export` 大按钮 + 「导出前显示此页?」开关 + **警告总数图标**（`:355-368`）；④ `Advanced options` 折叠区（`:470-700`）：按帧平移起止（`:497-508`）、MOV faststart、保留全部 MP4/MOV 元数据、保留章节、保留元数据三档、合并时生成章节、**Smart cut 实验开关**（`:608`）、**Keyframe cut 模式开关**（`:622`）、smart cut 自动码率、lossy 模式（`:660`）。**警告贴在对应选项行上**：`renderNoticeIcon(notices.specific[...])`（`:386, :392, :411, :458`），不集中到顶部。

#### 工程（队列/进度/取消/临时文件）【源码】

**没有任务队列**。全局单任务：`useLoading.ts` 的 `workingRef` 作**互斥锁**，所有入口首行 `if (workingRef.current) return;`（`App.tsx` 约 15 处）。批量是**手动逐文件**。唯一真批处理循环 `convertFormatBatch()`（`useHtml5ify.tsx:174-216`）：`for (const path of filePaths)`，**串行、失败收进 failedFiles 不中断**，进度 `(i + fileProgress)/total`，结束 toast 列失败文件。

进度解析（`src/main/progress.ts:2-46`）：主正则 `/frame=\s*\S+\s+fps=\s*\S+\s+q=\s*\S+\s+(?:size|Lsize)=\s*\S+\s+time=\s*(\S+)\s+/`（`:7`）；**纯音频退化正则** `/(?:size|Lsize)=\s*\S+\s+time=\s*(\S+)\s+/`（`:10`）；都不中交 `customMatcher`。时间串 `^(-?)(\d+):(\d+):(\d+)\.(\d+)$`；**负值直接丢弃**（ffmpeg 会输出 `-00:00:06.46`）；小数按**百分秒**算；`progress = min(max(0,time)/duration, 1)`。从 **stderr 逐行 readline**，异常只 log（`main/ffmpeg.ts:92-115`）。有单测 `src/main/progress.test.ts`。

取消：`abortFfmpegs()`（`main/ffmpeg.ts:85-90`）**遍历所有运行中 ffmpeg 的 AbortController 全杀**；UI 是 `Working.tsx` 的 `Abort` 按钮 + **100ms 刷新的 Elapsed 计时**（注释明说：某些操作长时间无进度输出，要让用户知道没死机，issue #2746）。

临时文件：章节文件 `ffmetadata-<Date.now()>.txt`（`:31-44`）在 `finally` 删；smart cut 中间产物在 `finally` 删（`:727-729`）。另有统一**清理对话框**（`GenericDialog.tsx:218-281`）：关闭当前文件 / 删除自动生成文件 / 删除项目文件 / 删除源文件 / trash 失败就永久删除 / 导出后自动执行；`deleteFiles({paths, deleteIfTrashFails, signal})`（`dialogs/index.tsx:548-566`）。**默认走系统回收站而非永久删除**。每次导出后 `transferTimestamps({inPath, outPath, cutFrom, cutTo})` 把源文件时间戳搬到输出。

#### 可复用 / 不采纳

**可复用**：① 三条防黑帧/防时长错乱规则（不剪头不做时间戳重定位；封面图流单独处理或排除；纯音频导出不依赖 keyframe 标记）；② **至少一帧时长** + **拼接减一帧**；③ **导出前显示「请求起止 vs 实际起止」**——lossless-cut 在这里是缺口，而本项目有 `SEEK_TO_PREVIOUS_SYNC` 的 `baseTimeUs`，**能在 UI 上精确显示「实际起点比请求起点早 N ms / N 帧」**，这是相对参考实现的直接优势；④ 体积/时长预览；⑤ 幂等跳过 + 「覆盖」开关；⑥ 单任务互斥 + 全局取消 + Elapsed 计时。
**不采纳**：FFmpeg 命令行的一切（已裁决 No-Go）；GPL-2.0 代码。

### 2.5 ab-av1

**许可**：**MIT（可移植算法）**。Rust clap + tokio + indicatif + serde，约 25 文件 / 204 KB；依赖 ffmpeg + ffprobe，**VMAF 通过 ffmpeg `libvmaf` 滤镜**。子命令 `sample-encode` / `crf-search` / `encode` / `auto-encode`。

#### CRF 搜索【源码】

`src/command/crf_search.rs`（673 行）：自述（`:25-33`）**"Interpolated binary search using sample-encode to find the best crf value delivering min-vmaf & max-encoded-percent"**，输出 best crf / mean sample VMAF / predicted full encode size / predicted full encode time。

**核心设计：所有搜索在整数 q 空间做，避免浮点比较**（`QualityConverter` `:583-621`）：`q = round(crf / crf_increment)`；`crf(q) = q * crf_increment`；`crf_increment = max(increment, 0.001)`；`high_crf_means_hq`（仅 `hevc_videotoolbox`）时 q 取负号以保单调。

默认 CRF 区间（`args/encode.rs:381-406`）：min：mpeg2video=2 / libsvtav1=5 / 其它=10；max：librav1e=255 / libx264,libx265=46 / mpeg2video=30 / hevc_videotoolbox=100 / libsvtav1=70 / 其它=55；increment：x264/x265=0.1 / svtav1=0.25 / 其它=1.0。

主循环（`:251-412`）：① 首轮 `q = (min_q + max_q)/2`（**取中点，不是端点**，`:301`）；② 每轮跑一次 `sample_encode` 得 `score` 与 `encode_percent`；③ `higher_tolerance`（`:318-324`）= thorough ? 0.05 : `max(crf_increment.min(1.0) * 2^(run-1) * 0.1, 0.1)` → **轮次越往后容差越松，防无限搜索**；④ **达标分支**（score > min_score）：若 `sample_small_enough && score < min_score + higher_tolerance` → **Done**；否则找已试样本中 q 更大的最小者 `upper`：`upper.q == q+1` → Done；有 upper → 插值；无 upper 且已到 max_q → Done；**否则第二轮主动跳 `round(q*0.4 + max_q*0.6)`**（20%/80% 分位，`:376-378`）；⑤ **不达标分支**（score <= min_score）：若 `!sample_small_enough || q == min_q` → **`Error::NoGoodCrf` 整体失败**（`:383-385`，**体积上限内达不到质量就明确失败，不静默降级**）；否则找 `lower`：`lower.q+1 == q` → 校验 `lower.encode_percent <= max_encoded_percent` 后**返回质量达标的下界**（`:393-398`）；有 lower → 插值；否则第二轮跳 `round(q*0.4 + min_q*0.6)`；否则 `min_q`。

**插值公式 `vmaf_lerp_q`**（`:548-562`）：`factor = (min_vmaf - worse.score)/(better.score - worse.score)`；`lerp = round(worse.q - (worse.q - better.q) * factor)`；`clamp(better.q+1, worse.q-1)`——**clamp 保证不会重复已试过的 q，是算法收敛的关键**。作者自认（`:543-547`）：**"Crf values do not linearly map to VMAF changes (or anything?) so this is a flawed method, though it seems to work better than a binary search"**。

`cut_on_iter2`（`:280-288`）：仅当用户给的 crf 区间 > 默认区间的 50% 时才启用第二轮 20/80 切分；理由：20/80 让第二轮结束时两端边界都已算过，25/75 要到第三轮才有 min/max。无硬迭代上限（`for run in 1..`），靠 q 相邻 + clamp 收敛；`guess_progress`（`:565-575`）猜 4 或 6 轮；`--thorough` 把容差固定 0.05。

#### 采样策略【源码】

`args.rs` + `sample_encode.rs` + `sample.rs`：默认 `--sample-every 12m`、`--sample-duration 20s`、`--min-samples`、`--samples`；`sample_count = ceil(duration/sample_every)`，再 `max(min_samples ?? 1, 1)`（`args.rs:82-130`）。**整段降级**（`sample_encode.rs:175-193`）：输入是图片，或 `sample_duration * samples >= duration * 0.85` → **直接编码整段**（1 个样本，`full_pass=true`）——**短视频会被自动整段编码**（对手机短视频很重要）。**样本起点均匀铺开**（`:479-482`）：`start = (duration - sample_duration*samples)/(samples+1) * n + sample_duration * idx`——把剩余时间分成 samples+1 段，**不贴头不贴尾**。样本切片（`sample.rs:14-82`）：`ffmpeg -y -ss <start> -i <in> -frames:v <frames> -c:v copy -an -sn <dest>`；注释明说 **"`-ss` before `-i` & `-frames:v` instead of `-t`"**（`:43-44`）；`frames = max(round(sample_duration*fps), 1)`；`sample_duration >= 2s` 时起点向下取整到秒；**输出强制 mkv**（`:30-37`，比 mp4 稳）；样本文件已存在则复用（`:38-40`）→ **样本层缓存**；文件名 `{pre}.crf{crf}.{preset}.{ext}`；失败且 stderr 含 `"Can't write packet with unknown timestamp"` → 重试加 `-fflags +genpts`（`:59-78`）；产物 < 1024 字节视为失败（`sample_encode.rs:489-493`）。

样本编码（`ffmpeg.rs:63-114`）：`... -fps_mode passthrough <crf_arg> <crf> [-pix_fmt] [-preset] [-vf] -an <dest>`；注释 `:97`：**"Avoid dropping or duplicating frames as this may negatively affect input/output analysis"** → 做质量对比时**必须** `-fps_mode passthrough`；代码会**强制移除用户传的 `-fps_mode`/`-vsync`**（`sample_encode.rs:166-167`）。每个样本**独立编码、独立评分、不拼接**（`:224-433`）；样本实际时长用编码产物回填（`:298-302`）。

#### VMAF 与阈值【源码】

`libvmaf=shortest=true:ts_sync_mode=nearest:...`（`args/vmaf.rs:99`），`n_threads` 默认全核，`--vmaf-fps 25.0`；`--vmaf-scale auto`（`:40-41`）：≤2560x1440 用 1k 模型（<1728x972 则上采样 1080p），否则 4k 模型（<3456x1944 上采样 4k），**按失真视频尺寸选**。XPSNR 可替代 VMAF（`--min-xpsnr` 与 `--min-vmaf` 互斥，clap group "min_score"）；`--xpsnr-fps` 默认 60。

阈值：`DEFAULT_MIN_VMAF = 95.0`（`crf_search.rs:23`）；`max_encoded_percent` 默认 **80.0**（`:69-70`）；`min_score = min_vmaf.or(min_xpsnr).unwrap_or(DEFAULT_MIN_VMAF)`（`:130-132`）；解码校验容差 `--verify-duration` **2s**（`args.rs:62-68`）；失败 `Error::NoGoodCrf` → `{"message":"Failed to find a suitable crf","type":"crf-search-error"}`。

#### 体积与时间外推【源码】

`sample_encode.rs:593-690`：`encoded_percent_size() = sum(encoded_size)*100 / sum(sample_size)`——**按字节加权，不是算术平均**（`:611-618`）；`mean_vmaf_score() = sum(scores)/self.len()`——**分母是全部样本数**（`:620-624`）；`estimate_encode_size_by_duration()`：`sample_factor = input_duration / sum(sample_duration)`；`round(sum(encoded_size) * sample_factor)`（`:632-649`），full_pass 直接返回实测；`estimate_encode_time()` 同一 `sample_factor`；`estimate_encode_size_by_file_percent()`：`round(input_file_size * encode_percent/100)`（`:676-690`）。**最终预测取两者较小值**（`:437-448`）：`min(by_duration, by_file_percent)`；注释 `:440-441`：file-percent 容易高估（输入含音频/容器开销），取 min 抑制高估。**只预测 video stream 体积**，音频/容器不单独计入（注释 `:672-675, :754-757`），靠 file-percent 兜底间接覆盖。

JSON 输出（`:783-799`）：`{type:"sample-encode-done", crf, from_cache, predicted_encode_size, predicted_encode_percent, predicted_encode_seconds, vmaf?, xpsnr?}`。人读输出（`:699-744`）：`crf X VMAF Y predicted video stream size Z (P%) taking D`；颜色阈值 percent <80 绿 / >=100 红；VMAF >=95 绿 / <80 红。

其它：`auto_encode.rs`（206 行）两阶段 = crf-search 定 crf 再整段编码，禁止 input==output（`--overwrite-input` 才允许），中间 `temporary::clean(keep)`；keyint 默认（`encode.rs:339-358`）：输入 ≥ **3 分钟**时自动 `-g` 为 10 秒对应帧数；编码器特化（`:420-429`）：`libaom-av1`/`libvpx-vp9` 加 `-b:v 0`（恒定质量）、`libx265` 输出 mp4/mov 加 `-tag:v hvc1`（Apple 兼容）；解码校验命令 `-v error -stats -xerror -i file -map 0:v? -map 0:a? -f null -`（`ffmpeg.rs:345-355`）。

#### 可复用 / 不采纳

**可复用**：① **保守预测**（取两种外推的较小值）；② 「体积上限内达不到质量就明确报错而非静默降级」（`Error::NoGoodCrf`）；③ **短视频整段编码**的降级判断（`sample_duration*samples >= duration*0.85`）；④ 样本起点**不贴头不贴尾**的均匀铺开公式；⑤ 样本层缓存（文件名含 crf/preset）；⑥ 整数 q 空间 + clamp 防重复试值（若本项目将来做质量搜索）。
**不采纳**：VMAF / XPSNR / 所有 ffmpeg 滤镜；`--vmaf-scale` 等。

### 2.6 filmcompress

**许可**：**MIT**。Python，**单文件 `filmcompress.py` 共 169 行**；依赖 click/termcolor/ffmpeg-python，需系统 PATH 有 ffmpeg；版本 0.6.0。

- `SUPPORTED_FORMATS = ['mp4','mov','m4a','mkv','webm','avi','3gp']`（`:16`）；`SKIPPED_CODECS = ['hevc','av1']`（`:17`），在 `:97-98` 生效：`if (codec in SKIPPED_CODECS) and not roku: continue`（**位置在 `--include` 过滤之前**，且只在 `outdir` 非空时）。
- 默认（`:150-154`）：**`libx265 crf=22 preset=slow`** + `libopus 64k` + `map_metadata=0` + `movflags=use_metadata_tags`。CRF 22 的依据在注释里指向 codecalamity 的 "Encoding UHD 4K HDR10 videos with FFmpeg"（`:152`）。**不缩放分辨率、不改帧率、不动 pix_fmt**——短视频场景里这三样最容易毁掉「相机原味」，作者选择完全不动。**这是可以直接照搬的默认哲学。**
- AV1（`:134-149`）：aom → `libaom-av1 crf=28 pix_fmt=yuv420p`；svt → `libsvtav1 qp=35 preset=5 pix_fmt=yuv420p`；rav1e → 未支持直接 `exit(0)`；amf → `av1_amf usage='lowlatency'`。AV1 全部音频 `libopus 96k`。
- **硬件加速开关策略**：`--gpu {nvidia,intel,amd,none}`（`:50`），**默认 none 且 README 明确推荐软编**（README:23 "Hardware support is off by default"；README:39-40：软编质量/兼容性更好，nVidia 20 系以后才够好）。nvidia（`:124-130`）：`hevc_nvenc` + `rc-lookahead=25` + `preset=p6` + `spatial_aq=1` + `temporal_aq=1`，**不给 CRF，走 nvenc 默认 rate control**；amd（`:131-133`）：`hevc_amf quality='balanced' usage='lowlatency' rc='cqp'`。**`intel` 是死选项**：CLI choices 里声明了（`:50`），代码里**没有任何 `gpu=='intel'` 分支**。**完全没有能力探测**：不跑 `ffmpeg -encoders`、不试运行、**无 CPU 回退**——用户选错就整个进程失败。**这是反面教材。**
- 元数据：统一 `map_metadata=0` + `movflags='use_metadata_tags'`；Roku 分支额外 `-ignore_chapters 1`；**没有保留拍摄时间/GPS 的专门逻辑**，靠 `-map_metadata 0` 一把梭。
- 批量与幂等：`search_files(dirpath, recursive)`（`:21-39`）用 `os.walk`（递归）或 `os.scandir`（单层），按扩展名小写白名单过滤；`-i/--include` 做 fnmatch 过滤（`:80`）。**幂等性靠文件名存在性，不靠内容**：输出路径 = `outdir / fp.with_suffix('.'+oformat).name`（`:120`），`os.path.exists(new_fp)` 则打印 "exists" 并 `continue`（`:121-123`；Roku 分支 `:105-107`）。无并发、无取消、无进度条、无临时文件；「Total saved」那一行**误写在循环体内**（`:165`）。
- **Roku 兼容兜底**（`:101-118`，依据 rokoding.com）：**注意这是容器/音频修复，不是转码**——视频恒为 `-c:v copy`（`:110, :112`）；容器强制 `.mkv`（`:104`）；`-ac 2` 下混立体声；`-c:s svt`（字幕转 SubRip）；`-ignore_chapters 1`；`-map_metadata 0`；`-movflags use_metadata_tags`；**`-map 0 -map -0:d`**（保留全部轨再删 data 轨，`:108` 注释说明是绕过 ffmpeg-python 不支持的 map 写法）。`--notranscode` 时音频也 `-c copy`（`:110`）；否则音频 `libopus 96k` + **`-af loudnorm=I=-16:LRA=11:TP=-1.5`**（EBU R128 响度归一，`:112`）。Roku 分支用原生 `subprocess.run(command_line)` 并检查 `returncode != 0` 后 `exit(1)`（`:115-116`）。
- **兜底的关键一条**（`:155-158`）：编码后若 `原文件大小 - 新文件大小 <= 0`（压完反而更大），**`shutil.copy2` 把原文件覆盖到输出**，宁可留原文件也不给用户劣化结果。

#### 可复用 / 不采纳

**可复用**：① **默认不动分辨率/帧率/pix_fmt** 的哲学；② **「压完反而更大就用原文件」的兜底**（`:155-158`）；③ 幂等跳过（按文件名存在性）；④ Roku 式「容器/音频修复用 `-c:v copy` 而非重编码」的思路（对应本项目「快速封装」）。
**不采纳**：无能力探测 + 无回退（**反面教材**）；`intel` 死选项；`loudnorm`（Android 无 ffmpeg 滤镜）。

### 2.7 shutter-encoder

**定位 / 技术栈 / 许可 / 规模**【源码】：Java 17 + Swing，约 100 个 `.java`，GPL（FFmpeg GUI 前端），强依赖外部 `ffmpeg`/`ffprobe`/`mediainfo`/`tsMuxer`/`bmxtranswrap`/`python`+`whisper`。**它是「功能枚举驱动 UI」的极端样本，也是本项目最应该反向参考的样本。**

- **功能式 UI 架构**：功能清单是**扁平字符串数组** `shutter-encoder/src/shutterencoder/ui/main/Shutter.java:3627-3663 functionsList = new ArrayList<>(Arrays.asList(...))`，**分组靠「带冒号的条目」当标题**（`Languages/en.properties:283 itemNoConversion=- Without conversion:`；`Languages/zh_CN.properties:291` = `- 无需重编码：`）。共 11 组。平台裁剪同一数组（`Shutter.java:3665-3676 functionsList.remove("H.266")` 等），末尾追加 `- MANAGE -` 哨兵（`:3678`）。搜索过滤靠 `functionsList.get(i).toString().contains(":") == false` 排除分组行（`:3801-3827`）。用户可裁剪功能集（`ui/others/ManageFunctions.java:49-56 / :106-127 / :152-191`，用 `javax.swing.Timer(12, null)` 做高度动画）。
  → **反面**：参数面板**不是数据驱动的**，靠散落的 `grpBitrate.setVisible(...)`（`ui/main/UIController.java:908/969/1198/1322/1364/1442/1671/1954/2412/2897/3470/3501/3549/3842/3970`）+ 49 处 `comboFonctions.getSelectedItem().equals(language.getProperty("functionXxx"))` 字符串比较。业务层同样以字符串为键：`functions/VideoEncoders.java:1848 switch (comboFonctions.getSelectedItem().toString())`；`functions/settings/AdvancedFeatures.java:135/263/421/545/628/831/911/963`、`functions/utils/FilterComplex.java:51`、`functions/utils/Libplacebo.java:126`、`functions/utils/FunctionUtils.java:1122` 全是 `case "H.264"/"H.265"/"AV1"`。
- **重封装 vs 重编码**：UI 上用分组标题区分（`itemNoConversion` 组下含 Cut / ReplaceAudio / **Rewrap** / Conform / Merge / Extract / Subtitles / Insert，`Shutter.java:3633-3637`，与 `itemOuputCodecs` 组并列）。实现 `functions/Rewrap.java:315 cmd = " -c:v copy -ignore_unknown" + ... + " -map v:0?" + audioMapping + mapSubtitles + ...`、`:463 return " -c:a copy"`、`:654 -c copy -map v:0? -map a?`、`:672 Utils.copyFile(fileOut)`。**速度/质量差异没有显式文案**，只靠分组名与扩展名暗示。
- **预设数据模型**：**没有「预设」对象**。质量模式是 UI 组合框取值：`functions/utils/FunctionUtils.java:978` 判 `debitVideo.getSelectedItem()` 等于 `lblBest`/`lblGood`/`"auto"`，否则 `:1116 return Integer.parseInt(debitVideo.getSelectedItem().toString())`（用户直填 kbps）。CBR/CQ/VBR 由 `lblVBR` 文案切换（`Shutter.java:18120/18132/18168/18322/19363`），在 `VideoEncoders.java:1848-1949` 分派。**全仓无「目标体积」模式。**
- **准目标体积（仅 DVD）**：`functions/VideoEncoders.java:1883 float bitrate = (float) ((float) 4000000 / FFPROBE.totalLength) * 8;`（4 000 000 ≈ 4.7 GB ÷ 时长 × 8，**裸魔数**）；mark in/out 时用 `sommeTotal = totalOut - totalIn` 重算（`:1884-1892`）；上限 `:1896 if (bitrate > 8) { DVDBitrate = 8000; ... }`。**不含音频与容器开销**。
- **质量模式的码率**是「bits per pixel per frame × 倍率」：`FunctionUtils.java:1092 Integer videoBitrate = (int) Math.round((float) (pixels * framerate * bitDepth * 2) / compValue);`，best×4 / good×2（`:1094-1102`）；`compValue` 按编码器取常量（`:981-1008`，默认 165888，H.265/VP9 331776，AV1/H.266 414720）。
- **容器 × 编码器能力表**：**无显式表**，以 if/else 硬编码（`functions/Rewrap.java:596-622`）。编码器可用性是**运行期真跑一次 ffmpeg 合成源**：`library/LibraryUtils.java:96-179 detectHardwareAcceleration`，如 `:130-132 checkHWaccel("-f lavfi -i nullsrc -frames:v 1 -c:v " + codec + "_nvenc -b:v 5000k ...")` 后 `if (!FFMPEG.error) graphicsAccel.add("Nvidia NVENC")`；同法试 `_qsv`/`_amf`/`_vulkan`/`_videotoolbox`/`_vaapi`。**探测带强制超时**：`:1083-1092`，注释「Never wait forever: a hung GPU driver (e.g. Vulkan) must be treated as unavailable」→ `:1084 if (!FFMPEG.process.waitFor(15, TimeUnit.SECONDS)) { ... FFMPEG.process.destroy(); return false; }`。**探测失败 = 静默软降级（该编码器不出现在下拉里），不报错；实际编码失败无自动回退。**
- **HDR**：仅静态 HDR10 **重注入**：`functions/settings/AdvancedFeatures.java:1060 options += "colorprim=bt2020:transfer=" + PQorHLG + ":colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(" + (int) FFPROBE.HDRmax * 10000 + "," + (int) FFPROBE.HDRmin * 10000 + "):max-cll=" + FFPROBE.maxCLL + "," + FFPROBE.maxFALL;`。Dolby Vision 只做布尔标记（`library/FFPROBE.java:116 public static boolean hasDolbyVision = false;`、`:622 hasDolbyVision = true`），**无 RPU 处理**。
- **编排器**：**无流水线抽象**。`ui/others/RenderQueue.java` 把「功能名 + 参数快照」入列后串行重放。外部工具以 `library/<Tool>.java` 一类一工具封装。
- **队列与批量**【源码】：**队列项就是一条命令行字符串**，不是任务对象 —— `ui/others/RenderQueue.java:98 public static DefaultTableModel tableRow;`、`:677` 列 = `{columnFile, columnCommand, destination}`、`:681 table = new JTable(tableRow)`、`:709` 删行；输出名去重靠重写文件名 `library/FFMPEG.java:500-535 checkList(cmd)` → `_<n><ext>`。并发是**显式 2–10 工作进程**：`RenderQueue.java:95-96 caseRunParallel / parallelValue`、`:774` 取值 `{"2"…"10"}`、`:901 int maxProcesses = Integer.parseInt(...)`、`:802 Thread render = new Thread(...)`、`:903-922 do {…} while (filesCompleted != tableRow.getRowCount())`。**批进度是文件计数不是百分比**：`:874-875 lblCurrentEncoding.setText(filesCompleted + "/" + tableRow.getRowCount() + …)`。单文件进度 = 解析 stderr 上限为源时长（`FFMPEG.java:1186-1210`，两遍编码 `:1204 setValue((fileLength/2) + getTimeToSeconds(ffmpegTime))`）；**ETA 用帧差/fps 估算**（`:1240-1279` 解析 `frame=`/`time=` 并平滑 `if (fps == 0) fps = (frames - frame0); else if (frames - frame0 > fps + 1) fps ++; else if (frames - frame0 < fps - 1 && fps > 1) fps--;`）。
- **取消是两模式**【源码】：`ui/main/Shutter.java:2940-2962` 先确认框（`:2927`，拒绝则重新点 Start 继续 `:2931-2938`），置 `cancelled = true; FFMPEG.isRunning = false;` 后 —— 多段剪切用 `FFMPEG.process.destroyForcibly()`，否则**向 stdin 写 `q` 优雅退出**：`FFMPEG.writer.write('q'); flush(); close();`。临时目录在 `finally` 删（`functions/Rewrap.java:338-346`）。
- **错误处理 = 子串黑名单，不做退出码映射**【源码】：`library/FFMPEG.java:443-498 checkForErrors(output)` 只在命中约 30 个字面串时收集该行 —— `"No such file or directory"`、`"Invalid data found when processing input"`、`"No space left"`、`"does not contain any stream"`、`"Unknown encoder"`、`"Could not find tag for codec"`、`"not divisible by 2"`、`"is not multiple of 4"`、`"hardware accelerator failed to decode picture"` 等；两条白名单例外（`:454-455` 忽略 `"unable to decode APP fields"`、`:488-489` 跳过含 `"error code"`/`"return code"` 的行）；`:492` 去掉 `^(\[[^\]]*\]\s*)+` 前缀后追加进 `:123 public static StringBuilder errorLog`。全局 `:87 public static boolean error` 由进度行复位（`:1192`，注释含义：**ffmpeg 记了 error 仍可能跑完，所以继续**），在 `:366-381`/`:430-436`/`:1006`/`:1017` 置位；函数把循环体包在 `catch (Exception e) { FFMPEG.error = true; }`（`Rewrap.java:335-337`）。有可见控制台面板（`:760 Console.consoleFFMPEG.append(… " -hide_banner -threads " + Settings.txtThreads.getText() + " " + cmd …)`）。
- **重封装的两个工程细节**【源码】：**HEVC→MP4 自动打 tag，不询问用户** —— `functions/Rewrap.java:126-129 if (vcodec.equals("HEVC")) { flags = " -tag:v hvc1"; }`、`functions/settings/AdvancedFeatures.java:913-916 case "H.265": flags += " -tag:v hvc1";`。**`faststart` 是用户复选框但按容器门控** —— `AdvancedFeatures.java:918-925 case "H.264": case "VP8": case "VP9": if (caseFastStart.isSelected() && (comboFilter…equals(".mp4") || …equals(".mov"))) flags += " -movflags faststart";`（标签 `Languages/zh_CN.properties:226` = `启用 Faststart（边下边播）`）。**多段无损剪切 = 逐段 `-c:v copy` 再 concat**：`Rewrap.java:308-311` + `:654 " -safe 0 -f concat -i … -c copy -map v:0? -map a? -y "`。**用户可见命名区分两者**：`Languages/en.properties:295 functionCut=Cut without re-encoding`、`:296 functionRewrap=Rewrap`；`Languages/zh_CN.properties:303 functionCut=无损剪切（不重编码）`、`:304 functionRewrap=重封装（换容器）`。
- **规模与许可【源码】**：`src/` **100 个 `.java` / 78,135 行**（`ui/main/Shutter.java` 19,707 行、`ui/main/UIController.java` 5,911、`functions/VideoEncoders.java` 2,125、`library/FFMPEG.java` 1,672、`ui/others/RenderQueue.java` 1,173、`functions/Rewrap.java` 687）；26 个语言包 `Languages/*.properties`（含 zh_CN/zh_TW）。许可实测 **GPL-3.0-or-later**（`LICENSE.txt` 为 GPLv3，源码头含 "or any later version"），与 AGPL-3.0 兼容（见 §0.1 许可核对表）。

**可复用 / 可借鉴 / 不采纳**：**可复用**：探测**强制超时**（注释那句「hung GPU driver must be treated as unavailable」的原则直接适用 Android `MediaCodecList`/试编探测）；静态 HDR10 元数据注入的字段常量（master-display / max-cll）。**可借鉴**：用「带冒号的分组标题」表达无数据驱动时的最小分组。**不采纳**：功能名 = 全局可变字符串 + 到处 `switch`（100+ 处）；用 `setVisible` 手动编排面板（Compose 必须状态驱动）；DVD 4.7 GB 硬编码 `4000000`；真跑一次编码做能力探测（Android 有 `MediaCodecList` 元数据，不需要真编码）；无回退。

### 2.8 compressO

**定位 / 技术栈 / 许可 / 规模**【源码】：Tauri 2（Rust 后端）+ React 19/TanStack Router/Valtio/HeroUI + Tailwind（前端），约 71 代码文件 / 300 KB。**压缩核心完全依赖 FFmpeg 独立二进制**（Tauri sidecar：`core/ffmpeg.rs:44-50 self.app.shell().sidecar("compresso_ffmpeg")`、`core/ffprobe.rs:25 .sidecar("compresso_ffprobe")`、`core/image.rs:53/61/69` pngquant/jpegoptim/gifski；`src-tauri/tauri.conf.json:18 "externalBin"`）。**无测试框架。**
**许可实测 = AGPL-3.0-only**（`LICENSE:1` `GNU AFFERO GENERAL PUBLIC LICENSE / Version 3` + `package.json` `"license": "AGPL-3.0-only"`）——**与简介所标 LGPLv2.1 不符**，见 §0.1 许可核对表；`THIRD_PARTY_NOTICES.md` 另列捆绑二进制 FFmpeg/FFprobe(GPL-2.0+/LGPL-2.1+)、pngquant(GPL-3.0+)、jpegoptim(GPL-3.0+)、gifski(AGPL-3.0+)。仓库自述 `AGENTS.md`：`Offline app (no network requests)`、`FFmpeg sidecars bundled`。

- **功能式 UI 架构**：单页工作流 + 标签页 `src/routes/(root)/ui/output-settings/video-settings/-index.tsx:28-41 const TABS = { video, audio, others }`；视频页顺序（`:98-133`）= CompressionPreset → VideoCodec → Dimensions → Transform → Trim → Speed → FPS → CustomThumbnail → Extension。**每个设置一个组件文件**（`video/CompressionPreset.tsx`、`video/VideoCodec.tsx`、`video/CompressionQuality.tsx`、`audio/AudioBitrate.tsx`…），组件内自带 `Switch` 决定是否展开。状态 Valtio 双写批量/单文件（`-state.ts:28-29`、`CompressionPreset.tsx:51-89`）。
- **重封装 vs 重编码**：UI 有 `Lossless Compression` 开关（`video/CompressionPreset.tsx:97-115`，`const isLossless = shouldDisableCompression`），**但后端不做流复制**：`ui/StartCompression.tsx:106-111` 在 lossless 时传 `presetName: null, quality: 101`；`src-tauri/src/core/ffmpeg.rs:268-274` 对 101 走 `else` 得 `default_crf = 28`，随后 `:275-279 if preset_name.is_some() && !is_gif_target { -crf <compression_quality> } else { -crf 18 }` → **实际强制 CRF 18**。全仓 `"copy"` 只出现在字幕分支（`ffmpeg.rs:718/:751/:780`）。→ **「Lossless」名不副实，仍是重编码。这是必须避免的命名陷阱。**
- **预设数据模型**：`src-tauri/src/core/domain.rs:135-153 VideoCompressionConfig`（`video_id`/`video_path`/`convert_to_extension`/`preset_name: Option<String>`/`audio_config`/`quality: u16`/`dimensions`/`fps`/`video_codec`/`transform_history`/`strip_metadata`/`metadata_config`/`custom_thumbnail_path`/`should_enable_custom_thumbnail`/`trim_segments`/`subtitles_config`/`speed`）。**无目标体积字段。** 预设仅两个枚举值：`src/types/compression.ts:26-29 compressionPresets = { ironclad, thunderbolt }`；文案 `CompressionPreset.tsx:15-27`「ironclad: Optimal size but slightly slower processing / thunderbolt: Slightly larger size but faster processing」。质量滑块 1-100 默认 50（`CompressionQuality.tsx:62/:92-93`）。**质量模式与目标体积模式不并存。**
- **质量→CRF 映射**：`ffmpeg.rs:264-274`，`default_crf 28 / max_crf 36 / min_crf 24`，`diff = (36-24) - ((36-24)*quality)/100`，结果 `24 + diff`（quality 100→CRF 24，0→CRF 36）。无音频/容器开销概念，无二次修正。**CRF 是感知量纲，线性映射两端失真。**
- **容器 × 编码器能力表（最值得照抄）**：前端显式声明表 `video/VideoCodec.tsx:23-54 VIDEO_CODECS: {value,name,description,compatible_containers: VideoExtension[]}[]` —— libx264→mp4/mov/mkv/avi；libx265→mp4/mov/mkv；libvpx-vp9→webm/mkv；libaom-av1→mp4/mkv/webm；mpeg4→mp4/mov/mkv/avi。下拉按容器过滤 `:163-165`；**换容器时自动清空不兼容选择** `:85-114`。后端第二份判定：`ffmpeg.rs:210-215 default_codec(container)` 与 `:218-226 is_codec_compatible(codec, container)`；扩展名白名单 `:28 const EXTENSIONS: [&str; 6] = ["mp4","mov","webm","avi","mkv","gif"]`，`:73-75` 校验失败 `return Err("Invalid convert to extension.")`。
- **HDR**：**只解析不处理**——`core/ffprobe.rs:129` 采集 `color_space,color_range,color_primaries,color_transfer,chroma_location` 落进 `domain.rs:214-247 VideoStream`，无注入/传递代码。**能力缺口。**
- **编排器**：无阶段抽象。`core/media_process.rs` 的 `spawn_and_wait` 与并发多进程（`:543-658`）；批量入口 `tauri_commands/media.rs:11 compress_media_batch`；进度事件 `domain.rs:79-90 CustomEvents`。
- **失败与回退**：sidecar 缺失由 Tauri 报错，**无自愈**。统一退出码语义 `media_process.rs:663-672 struct ProcessExitStatus { exit_code: u8 } + pub fn success(&self) -> bool { self.exit_code == 0 }`；并发时 `:543-544 let mut final_exit_code = 0u8; let mut all_success = true;`，`:596-609` 任一失败即 `all_success = false` 并传播首个非零码。**无「编码器不可用→换软件编码器」回退。**

- **质量→CRF 映射的真实粒度**：整数除法 `diff = (max_crf - min_crf) - ((max_crf - min_crf) * quality) / 100` 使 0–100 的滑杆**只产出 13 个不同 CRF 值**（24…36），quality 50 → CRF 30。**滑杆的"精细感"是假的。** 全仓唯一 `-b:v` 是 `ffmpeg.rs:188` 的 `-b:v:0 0`（显式关掉码率目标）；**无 `-maxrate`/`-bufsize`**。
- **唯一"体积"控件是音频码率**：`audio/AudioBitrate.tsx:11-19` 固定列表 `64/96/128/160/192/256/320 kbps`，默认 128（`:55`）。两个预设只是 flag 集合（`ffmpeg.rs:180-203`）：`ironclad`（默认，`-state.ts:28`）→ `-pix_fmt:v:0 yuv420p -b:v:0 0 -movflags +faststart -preset slow`；`thunderbolt` → 无额外 flag。**`-movflags +faststart` 对 ironclad 无条件，未按容器门控。**
- **极简 UX 的信息层级**：单文件视频页**默认只显示两个控件** —— `CompressionPreset` + `Quality` 滑杆（`output-settings/video-settings/-index.tsx:28-41,81-96`）；其余全部藏在各自 `Switch` 后（`VideoCodec`/`VideoDimensions`/`TransformVideo`/`TrimVideo`/`VideoSpeed`/`VideoFPS`/`CustomThumbnail`/`VideoExtension`，`:98-133`）。滑杆自带心智模型 `CompressionQuality.tsx:78-103 marks = [{value:1,label:'Low'},{value:50,label:'Medium'},{value:99,label:'High'}]`，显示 `${quality}%`，默认 50（`-state.ts:42`）。**批量模式把整棵设置树压成 2 项手风琴**（`output-settings/-index.tsx:39-74`）。全局 vs 单项 = `-state.ts:92-95 commonConfigForBatchCompression` + `isConfigDirty` + `normalizeBatchMediaConfig()`（`:150-165`）；**撤销 = Valtio 快照时间旅行** `takeSnapshot('beforeCompressionStarted'|'batchCompressionStep')`/`timeTravel(...)`（`:98-144`），取消（`CancelCompression.tsx:49-56`）与出错（`StartCompression.tsx:307`）都调用。
- **进度解析**：ffmpeg 用机器可读进度 —— `ffmpeg.rs:171-178 -hide_banner -progress - -nostats -loglevel error`；`:805 Regex::new(r"out_time=(?P<out_time>.*?)\n")`；`:807-828` 发 `VideoCompressionProgress { video_id, batch_id, current_duration }`。**百分比由前端算**：`ui/CompressionProgress.tsx:80-120 clamp(currentDurationInMilliseconds * 100 / videoDurationInMilliseconds, 0, 100)`，按 trim 段缩减（`:86-90`），gif 目标时长 ×2（`:92-98`）。**全仓无 ETA/剩余时间逻辑。** 批进度 = 逐项进度均值 → macOS dock（`CompressionProgress.tsx:16-31 invoke('set_dock_progress', …)`）；图片压缩无百分比，只同步推 `progress: 0.0` 以推进游标（`tauri_commands/media.rs:132-151`，带注释说明必须同步否则乱序）。
- **取消与临时文件**：取消 = 事件广播 + 按 id 杀进程 —— `CancelCompression.tsx:39-44 emitTo('main', CustomEvents.CancelInProgressCompression, { ids: [media[0]?.id, state.batchId] })`；`core/media_process.rs:175-198` 匹配 id 后 `proc.kill().ok()` 并置 `should_cancel`。按钮内两步确认 + 5s 自动解除（`CancelCompression.tsx:20-33`）。**半成品由注册在 spawn 时的 cancel 回调删除**：`ffmpeg.rs:796-800 std::fs::remove_file(&output_file_clone).ok()`；`CancelCallback = Arc<dyn Fn() + Send + Sync>`（`media_process.rs:11`）。进程抽象 `media_process.rs:17-98 MediaProcessExecutorBuilder { commands, cancel_ids, cancel_callback, stdout_callback, stderr_callback, piped }` + `spawn_and_wait`/`spawn_and_wait_parallel`/`spawn_and_wait_piped`；窗口销毁时全杀（`:159-168`）。输出先写 scratch：`src-tauri/src/sys/fs.rs:21-32 ensure_assets_dir() = app_data_dir().join("assets")`；GC `sys/fs.rs:99-121 delete_stale_files(path, duration_in_millis)`，`tauri_commands/fs.rs:46-53 delete_cache()` 传 0 即全清；保存时 `move_file` = copy+delete（`fs.rs:25-36`）。
- **离线保证靠 CSP 而非代码**：`src-tauri/tauri.conf.json app.security.csp` 的 `connect-src` 无远程源。唯一例外是 Tauri updater（`plugins.updater.endpoints = ["https://github.com/codeforreal1/compressO/releases/latest/download/latest.json"]`、`bundle.createUpdaterArtifacts: true`）。存在 loopback HTTP 服务但仅用于本地预览：`core/server.rs:30-68 TcpListener::bind("127.0.0.1:0")`，axum `/video` + `/ping`，CORS `Any`。
- **批量与错误处理（弱项）**：批处理**串行** —— `tauri_commands/media.rs:21 for (index, media_item) in media.iter().enumerate()` 内部 `.await`（`:29`/`:108` 的 `tokio::spawn` 只负责发事件），无并发控制。**逐项失败被吞掉**：`media.rs:121-127 Err(e) => { if e == "CANCELLED" { return Err("CANCELLED") } log::error!("Failed to compress video '{}': {}", video_id, e); }` —— 失败 id 干脆不写进 `results: HashMap<String, MediaCompressionResult>`；前端靠**键缺失**推断失败 `StartCompression.tsx:263-303 isSuccessful: !(videoResult == null)`，**无失败原因、无 `FAILED` 枚举**，整批失败只弹一个 toast（`:304-309`）。结果类型是 tag union `MediaCompressionResult::Video(..) | ::Image(..)`（`media.rs:135-145`）——「一个队列两种媒体」的干净模型。

**可复用 / 可借鉴 / 不采纳**：**可复用**：容器 × 编码器**显式声明表**（译成 `enum class VideoCodec(val containers: Set<Container>)`）+「换容器时自动清空不兼容选择」；统一 `ProcessExitStatus.success()` 与「任一失败即整体失败并传播首个非零码」的结果聚合。**可借鉴**：① `compatible_containers` 数据字段 + `.filter(...)` + 容器变更自愈（`VideoCodec.tsx:16-54,87-106,163-165`）—— 把容器×codec 能力做成**数据**；②「滑杆是唯一可见控件 + Low/Medium/High + `%` 读数」，其余控件各自 `Switch` 后置；③ `-progress - -nostats -loglevel error` + `out_time=` 正则 + 前端按已知时长算百分比（含 gif ×2）—— 最干净的进度契约，可对应 Media3 `Transformer.getProgress(ProgressHolder)`；④ `commonConfigForBatchCompression` + `isConfigDirty` + `normalizeBatchMediaConfig()` 的「全局设置 + 逐项覆盖」；⑤ Valtio 快照时间旅行作整 UI 撤销；⑥ `MediaProcessExecutorBuilder` 形状；⑦ **在 cancel 回调里删半成品**；⑧ scratch 目录 + `delete_stale_files(dir, 0)` 作显式「清缓存」；⑨ 每个设置一个组件文件的粒度。**不采纳**：把 CRF-18 重编码称作 `Lossless`、用越界哨兵 `quality: 101` 表示「未使用」、静默忽略滑杆；`Option<String>` 里 `null` 兼表他意的 `presetName` 建模；串行批 + `log::error!` 续跑 + 缺失键即失败；TS 与 Rust 两份重复兼容逻辑；loopback axum 服务；quality 0-100 线性映射 CRF（只有 13 个有效值）；无能力探测与回退。

### 2.9 staxrip

**定位 / 技术栈 / 许可 / 规模**【源码】：VB.NET + WinForms，**实测 333 文件 / 7.19 MB，其中 145 个 `.vb`**（另有 51 `.resx`、27 `.md`），入口 `Source/StaxRip.vbproj`。最大文件 `Source/Forms/MainForm.vb` 278,504 B、`Source/General/Package.vb` 196,806 B、`Source/Encoding/NVEnc.vb` 184,273 B、`Source/General/ProcController.vb` 47,359 B、`Source/General/JobManager.vb` 5,449 B。**⚠ 首轮记录的「18 个代码文件 / 136,801 字节」是按扩展名统计的偏差，已更正。** 许可 MIT（`License.txt:1` + `:3 Copyright (C) 2002-2026 StaxRip Authors`）。README 自述 "executes and controls console apps such as x265, mkvmerge, ffmpeg … uses scripting based frame servers, AviSynth+ and VapourSynth"，并强调 "StaxRip is no One-Click Encoder!"。**它是「编排器」样本**：自身不做编解码，编排 100+ 外部工具（x264/x265/SVT-AV1/VVenC/NVEnc/VCEEnc/QSVEnc/ffmpeg + AviSynth+/VapourSynth 帧服务器 + `hdr10plus_tool`/`dovi_tool`/`mkvmerge`）。**本项目中价值最高的一节**；但**前提与本项目相反**（Android 无外部二进制），只借鉴结构。

- **功能式 UI 架构（声明式设置页 + 反射绑定）**：`staxrip/Source/Forms/MainForm_ShowOptions.vb:11-22 Using form As New SimpleSettingsForm("Project Options", ...)` + `:22 ui.Store = p`（绑定 Project 对象），随后逐项声明：`:26 ui.CreateFlowPage("Image", True)`、`:55 ui.CreateFlowPage("Image | Aspect Ratio", True)`、`:95 Dim cropPage = ui.CreateFlowPage("Image | Crop", True)` —— **路径里的 `|` 自动生成树形层级**。控件工厂 `Source/UI/Controls/SimpleUI.vb:265 AddBool / :282 AddNumeric / :291 AddLabel / :329 AddNum / :340 AddText / :351 AddTextMenu / :362 AddColorPicker / :373 AddButton / :394 AddMenu(Of T) / :405 AddEmptyBlock / :419 AddLine / :434 CreateFlowPage / :452 AddControlPage / :461 CreateDataPage`。每项三条元数据：`:31-32 n.Config = {0, Integer.MaxValue, 10000}`（值域）、`n.Field = NameOf(p.AutoResizeImage)`（**字段名双向绑定**）、`n.Help = "..."`。页面可插拔：`SimpleUI.vb:477/:493 Implements IPage`、`Source/Controls/PreprocessingControl.vb:6 Implements IPage`、`AddControlPage(ctrl, path)`。菜单同样数据驱动：`Source/UI/Menu.vb:43-127 CustomMenuItem.Add(path As String, methodName As String, ...)`（按路径字符串建树）、`:232 EnableMenuItemByActionName(actionName, enabled)`、`:554 GetHelp`。编码器是对象列表 `Source/Encoding/VideoEncoder.vb:411-456 Shared Function GetDefaults() As List(Of VideoEncoder)`（10 个软编码器 `:413-422` + NVEnc/VCEEnc/QSVEnc/ffmpegEnc 按各自 `Params.Codec.Options.Length` 循环展开 `:425-443` + `BatchEncoder` `:445-451` + `NullEncoder` `:453`）。每编码器配置对话框由基类统一生成（`:142 Overrides Function CreateEditControl()` 把 `GetMenu()` 的键值对生成垂直 ToolStrip 按钮；`:106 GetMenu() As MenuList`）。
- **重封装 vs 重编码 = 空编码器**：`Encoding/VideoEncoder.vb:777 Public Class NullEncoder : Inherits VideoEncoder`；`:780-784 Sub New()` 设 `Name = "Copy/Mux"`、`Muxer = New MkvMuxer()`、`QualityMode = True`；`:801-817 Overrides ReadOnly Property OutputPath` 在容器不受支持时从源文件视频流取扩展名，否则**直接返回源文件本身**（`:815`）；`:839-848 Overrides Sub Encode()` 只在 `Not Muxer.IsSupported(sourceFile.Ext)` 且 `Case "mkv"` 时解复用；`:850-854 GetMenu()` 只暴露 `Container Configuration`。**内置工作流模板把这一区分产品化**：`General/General.vb:253-269` 构造 `Dim remux As New Project`，设 `:257 remux.DemuxAudio = DemuxMode.None`、`:258 remux.SubtitleMode = SubtitleMode.Disabled`、`:259 remux.VideoEncoder = New NullEncoder`，音轨换 `New MuxAudioProfile()`（`:260-268`），`:269 SafeSerialization.Serialize(remux, Path.Combine(ret, "Re-mux.srip"))`；同处 `:237-243` 生成 `Automatic Workflow.srip`、`:245-251` 生成 `Manual Workflow.srip`。容器/流校验：`General/Muxer.vb:278-279 Overridable Function IsSupported(fileType As String) As Boolean → Return SupportedInputTypes.Contains(fileType)`。
- **预设数据模型 = 序列化的 Project 对象（`.srip` 文件）**：字段即 `General/Project.vb` 的属性（`:48 Public CompCheckAction As CompCheckAction = CompCheckAction.AdjustFileSize`、`:148 Public TargetSize As Integer = 5000`、`:76 DemuxAudio`、`:92 Hdr10PlusMetadataFile`、`:93 HdrDolbyVisionMetadataFile`）。每编码器自带参数对象（`NumParam`/`OptionParam`/`StringParam`，例 `Encoding/x265Enc.vb:1128 DolbyVisionProfile As New OptionParam`、`:1162 DolbyVisionRpu As New StringParam`、`:1201 .Switch = "--master-display"`、`:1210 .Switch = "--max-cll"`），再经 `Params` 组暴露给 UI。**质量模式与目标体积互斥且显式**：`Forms/MainForm.vb:5088 tbTargetSize.Visible = Not p.VideoEncoder.QualityMode`；`Encoding/VideoEncoder.vb:169-173 IsCompCheckEnabled => Not QualityMode`。体积 UI：`MainForm.vb:4857-4858 Sub SetSize(<DispName("Target File Size")> targetSize As Integer)`、`:6149 Sub UpdateTargetSizeLabel()`、`:4728 tbTargetSize.Text = $"{CInt(Calc.GetSizeInBytes \ PrefixedSize(2).Factor)}"`。另有「压缩率」预设 `VideoEncoder.vb:21 Property AutoCompCheckValue As Integer = 50`（各编码器把 `CompCheckAimedQuality` 映射进来）。
- **目标体积（三项目中最完整，本项目应直接采用）**：主公式 `General/Misc.vb:125-132 GetVideoBitrate()`：
  `Dim bytes = p.TargetSize * PrefixedSize(2).Factor - GetVideoMetadataBytes() - GetAudioBytes() - GetSubtitleBytes() - GetOverheadBytes()` → `Dim ret = bytes * 8 / 1000 / p.TargetSeconds` → `Return Math.Max(1, ret)`。**先扣除元数据/音频/字幕/容器开销，再按秒换算 kbps。**
  - 音频 `Misc.vb:171-173 GetAudioBytes() = (CLng(GetAudioBitrate() / 8) * 1000L * p.TargetSeconds)`；`:175-185 GetAudioBitrate()` 累加各音轨 profile + 外部音频文件。
  - 字幕 `Misc.vb:149-151 GetSubtitleBytes()` = Σ 各启用字幕 `Size \ 3`。
  - **容器开销表 `Misc.vb:153-169 GetOverheadBytes()`**：`avi/divx` → `frames*0.024` + 每条外部音轨 `frames*0.04`；`mp4` → `frames*0.013`；`mkv` → `frames*0.014`；`Return CLng(ret * 1024)`（KB）。
  - **HDR 元数据计入体积** `Misc.vb:138-147 GetVideoMetadataBytes()` = `hdr10Plus.FileSize() + dv.FileSize()`。
  - 反向校验 `Misc.vb:119-123 GetSizeInBytes()`；`134-136 GetVideoBytes() = (p.VideoBitrate \ 8) * 1000L * p.TargetSeconds`。
  - **二次修正 = 暴力搜索目标体积** `General/GlobalClass.vb:1142-1163 Function GetAutoSize(percentage As Integer)`：`For i = 1 To 100000` 令 `p.TargetSize = i`、`p.VideoBitrate = CInt(Calc.GetVideoBitrate)`，`If CInt(Calc.GetPercent) >= percentage Then ret = i : Exit For`。触发 `Encoding/VideoEncoder.vb:175-183 OnAfterCompCheck()`：`AdjustFileSize` → `g.MainForm.tbTargetSize.Text = g.GetAutoSize(AutoCompCheckValue)`；`AdjustImageSize` → `AutoSetImageSize()`（`:289-322`，`While Calc.GetPercent < ...` 每次宽减 16 至下限 720）。
  - 压缩率指标 `Misc.vb:101-107 GetPercent() = (GetBPF() / p.Compressibility) * 100`；`:109-117 GetBPF() = p.VideoBitrate * 1000L / (p.TargetWidth * p.TargetHeight * CLng(framerate))`。
- **容器 × 编码器能力表**：`General/Muxer.vb:22 MustOverride Sub Mux()`、`:24 MustOverride ReadOnly Property OutputExt`、`:56-60 Overridable ReadOnly Property SupportedInputTypes As String() → Return New String() {}`、`:278-279 IsSupported`。ffmpeg 容器白名单 `Muxer.vb:1043-1048`：`{"ASF","AVI","FLV","ISMV","IVF","MKV","MOV","MP4","MPG","MXF","NUT","OGG","TS","WEBM","WMV"}`；AVI 特例 `:1037-1041 AVITypes As String() = {"avi","mp2","mp3","ac3","mpa","wav"}`、`:1064-1068 IsSupported`。流类型白名单 `Misc.vb:1914 FileTypes.VideoAudio = {...}`、`:1919 SubtitleExludingContainers = {...}`。**编码器不声明容器兼容性**，改由 Muxer 侧 `IsSupported` + NullEncoder 的 `OutputPath` 兜底表达。
- **HDR（本报告最完整的 HDR 处理链）**：
  - **HDR10+ 提取** `Forms/MainForm.vb:3511-3572 ExtractHdr10PlusMetadata(proj)`：前置判定 `:3521 If Not mi.GetVideo("HDR_Format_Commercial")?.ContainsAny("HDR10+") Then Continue For`、`:3522 If mi.GetVideo("Format") <> "HEVC" Then Continue For`；执行 `:3530 $"{ffmpeg} -hide_banner -probesize 50M -i ""{src}"" -an -sn -dn -c:v copy -bsf:v hevc_mp4toannexb -f hevc - | {hdr10plus_tool} extract -o ""{jsonPath}"" -"`；**`:3550-3552` 产物 <100 字节即删除（防假成功）**。
  - **DV RPU 提取** `MainForm.vb:3574-3669 ExtractDolbyVisionMetadata(proj)`：`:3583-3589` 先找 `*_EL*` 增强层；`:3595 If isEL OrElse Regex.IsMatch(fileHdrFormat, "Dolby Vision.*Profile|HDR10+ Profile B")`；`:3605 ... -c:v copy -bsf:v hevc_mp4toannexb -f hevc - | dovi_tool extract-rpu - -o ""{rpuPath}""`；**流式失败检测 `:3609-3618`**：在 stdout/stderr 匹配 `"Unexpected RPU NALU"` 或 `"Discarding"` 就 `proc.Kill()` 并抛 `ErrorAbortException`；`:3640` 产物 >100 字节才 `New DolbyVisionMetadataFile(rpuPath)`。
  - **派生/改写链** `Encoding/x265Enc.vb:149-167`：`ReadProfileFromRpu()` → `WriteEditorConfigFile(offset, mode, True)` → `WriteModifiedRpu(True)` → 需要裁剪时 `TrimRpu()` → `Params.DolbyVisionRpu.Value = newPath`（**改 RPU → 写派生文件 → 再喂编码器**）。
  - 文件布局 `General/DolbyVisionMetadataFile.vb:33`：`:58-62 Level5JsonFilePath => $"{Path.DirAndBase()}_L5.json"`、`:64-68 _Config.json`、`:70-74 _Modified.rpu`、`:76-80 _Trimmed.rpu`；`:82 HasPathChanged`、`:88 HasLevel5Changed`、`:94-109 HasToBeTrimmed` 做增量失效。
  - **注入** `x265Enc.vb:280-281 cl += $" --dhdr10-info ""{p.Hdr10PlusMetadataFile}"""`；`:286-300 cl += $" --dolby-vision-rpu ""{p.HdrDolbyVisionMetadataFile.Path}"""`；`:307 cl += $" --max-cll ""{MaxCLL},{MaxFALL}"""`；`:246/:256/:266` 三套 `--master-display`。NVEnc/VCEEnc/QSVEnc 同款（`NVEnc.vb:334-335`、`VCEEnc.vb:330-331`、`QSVEnc.vb:344-345`）。
  - **能力探测 = 参数可见性** `x265Enc.vb:64-89 Overrides ReadOnly Property IsDolbyVisionSet → If Not Params.DolbyVisionProfile.Visible Then Return False`。
  - L5 驱动自动裁边 `GlobalClass.vb:1918-1924`。宏占位符 `Macro.vb:492-493 %hdr10plus_path% / %hdrdv_path%`。外部工具注册 `Package.vb:749-753 hdr10plus_tool.exe`、`:758-762 dovi_tool.exe`。
- **编排器**：任务单元 `General/Proc.vb:6 Public Class Proc`（`:10 AllowedExitCodes As Integer() = {0}`、`:139 File`、`:148 CommandLine`、`:163 Arguments`、`:244 Sub Start()`、`:324 ExitCode`、`:334 If ExitCode <> 0 AndAlso Not AllowedExitCodes.ContainsEx(ExitCode) Then`、`:351-352 ProcessHelp.GetConsoleOutput(Package.Err.Path, "/ntstatus.h /winerror.h " & ExitCode)`（**把 Windows 错误码翻译成人话**）、`:360 Throw New ErrorAbortException(...)`、`:448 Function ProcessData(value As String) As (Data As String, Skip As Boolean)`（行级输出过滤成元组））。调度 `General/ProcController.vb:13`（`:48 Shared Property Procs`、`:49 Shared Property Aborted`、`:357 Sub ProgressHandler(value)`、`:732 Shared Sub Abort()`、`:742 Shared Sub Skip()`、`:749 Suspend()` / `:760 ResumeProcs()`、`:896 AddProc`、`:852 Finished()`；`:948-960` 直接调 Win32 `SuspendThread/OpenThread/ResumeThread/CloseHandle`）。队列 `General/JobManager.vb:6-17 Public Class Job { Name, Path, Active }`、`:23 ActiveJobs`、`:79 AddJob`、`:141 SaveJobs`（文件持久化）——**抽象很薄**。工具注册表 `General/Package.vb:8`：声明式字段（`:11-42 Filename32/Location/Locations/HintDirFunc/RequiredFunc/RequirementsFunc/SetupAction/StatusFunc/Version/DownloadURL/…`）、`:44 Shared Property Items As New SortedDictionary(Of String, Package)`、分层路径解析 `:3236-3269 Overridable ReadOnly Property Path`、`:3336 Shared Function FindEverywhere(fileName, ignorePath)`、`:3212-3234 GetPythonHintDir()`（注册表 → FindEverywhere → 兜底目录）。
- **失败与回退**：工具可用性集中在 `Forms/AppsForm.vb:515-589 ShowActivePackage()`（`Status`/`Version`/`Location`（`:574 If(path = "", "Not found", path)`）/`Description`/`Website`/`Download`；`:523 SetupButton.Visible = CurrentPackage.SetupAction IsNot Nothing AndAlso CurrentPackage.GetStatus <> ""` 可一键安装；`:900-928 miStatus_Click / miStatusRequired_Click` 批量汇总）。路径发现多级回退（`Package.vb:3236-3269`），**找不到只影响该功能可用性，不崩溃**。进程失败即抛 `ErrorAbortException`（`Proc.vb:360`）并附 Windows 错误码译文，用户可中止/跳过（`ProcController.vb:732/742`）。

- **流水线描述 = 一个扁平可序列化 POCO，不是步骤图**：`General/Project.vb:7-8 <Serializable()> Public Class Project Implements ISafeSerialization, INotifyPropertyChanged`，**约 150 个平铺 public 字段**。源侧 `:119-136`（`SourceFile`、`SourceWidth/Height/FrameRate/VideoBitrate/VideoFormat/VideoHdrFormat`），目标侧 `:144-149,288`（`TargetFile`、`TargetWidth/Height`、`TargetSize` 默认 5000、`TargetFrameRate`、`TargetFrames`），`VideoEncoder As VideoEncoder :159`、`AudioTracks As List(Of AudioTrack) :21`、`Script As TargetVideoScript :111`、`Ranges As List(Of Range) :104`、`Hdr10PlusMetadataFile :92`、`HdrDolbyVisionMetadataFile :93`、`VideoBitrate = 5000 :158`、`BitrateIsFixed = True :45`、`SkipVideoEncoding/SkipAudioEncoding :112-114`。**带逐字段 schema 版本与迁移钩子**：`:157 Public Versions As Dictionary(Of String, Integer)`；`:172-174 Function Check(obj, key, version) → SafeSerialization.Check(Me, obj, key, version)`；`Init() :176-224` 是迁移钩子，例 `:200-202 If Check(VideoEncoder, "Video Encoder", 80) Then VideoEncoder = New x265Enc`。→ **神对象，不建议采纳**，但「逐字段版本 + Check 门」的形状可借鉴。
- **步骤是共享基类的多态对象**：`Encoding/VideoEncoder.vb:7-24 <Serializable()> Public MustInherit Class VideoEncoder Inherits Profile Implements IComparable(Of VideoEncoder)`，`MustOverride Sub Encode() :12`、`Codec :14`、`OutputExt :15`、`ShowConfigDialog :24`，钩子 `BeforeEncoding() :123`/`AfterEncoding() :127`、`GetCommandLine(includePaths, includeExecutable) :103`、`GetError() :328`、`Property Muxer As Muxer = New MkvMuxer :22`。具体编码器一文件一个：`x264Enc.vb`、`x265Enc.vb`、`NVEnc.vb`、`QSVEnc.vb`、`VCEEnc.vb`、`SvtAv1*Enc.vb`、`ffmpegEnc.vb`、`NullEncoder`（`VideoEncoder.vb:777`）。
- **两段式流水线：编码到临时文件 → 再封装，且封装永远是流拷贝**【源码】：`VideoEncoder.vb:93-101 OutputPath` 在 muxer 非 `NullMuxer` 时返回 `Path.Combine(p.TempDir, p.TargetFile.Base + "_out." + OutputExt)`；`General/Muxer.vb:1099` **硬编码** `args += mapping + " -c:v copy -c:a copy -strict -2 "`；`:1100` faststart 按容器门控 `If OutputFormat.EqualsAny("MOV","MP4","ISMV")`。`Muxer.vb:12-24 MustInherit Class Muxer Inherits Profile`（`MustOverride Sub Mux() :22`、`OutputExt :24`、`SupportedInputTypes As String() :56`、`Overridable Function IsSupported(fileType As String) As Boolean :278`）；实现 `MP4Muxer :294`、`NullMuxer :497`、`BatchMuxer :540`、`MkvMuxer :625`、`WebMMuxer :1007`、`ffmpegMuxer :1028`。**容器能力 = 谓词 + 每容器白名单数组**：`Muxer.vb:1037-1041 AVITypes As String() = {"avi","mp2","mp3","ac3","mpa","wav"}`、`:1043-1048 SupportedFormats = {"ASF","AVI","FLV","ISMV","IVF","MKV","MOV","MP4","MPG","MXF","NUT","OGG","TS","WEBM","WMV"}`、`:1056-1062` 当 `OutputExt = "avi"` 时返回 `AVITypes`、`:1064-1068 Overrides Function IsSupported(type) … If OutputExt = "avi" Then Return AVITypes.Contains(type) : Return True`。**调用点全是谓词、无重复表**：`Muxer.vb:423`/`:918`、`Audio.vb:39-44`、`GlobalClass.vb:1222 If currentMuxer.IsSupported(p.VideoEncoder.OutputExt)`、`VideoEncoder.vb:805`/`:842`。
- **解复用是声明式命令模板注册表**：`General/Demux.vb:70-107 Shared Function GetDefaults() As List(Of Demuxer)`，例 `:73-81 New CommandLineDemuxer With {.Name = "ffmpeg: Re-mux (M2)TS to MKV", .Active = False, .InputExtensions = {"m2ts","ts"}, .OutputExtensions = {"mkv"}, .InputFormats = {"hevc","avc"}, .Command = "%app_path:ffmpeg%", .Arguments = "-y -hide_banner -probesize 10M -i ""%source_file%"" -map 0 -dn -c copy -ignore_unknown ""%temp_file%.mkv"""}`；按 `InputExtensions` + `InputFormats` 匹配选择，`.Active` 与顺序用户可改。
- **队列项是指向已落盘项目文件的指针（反面）**：`JobManager.vb:5-20 Public Class Job { Name, Path, Active }`；`:29-54 JobPath = Path.Combine(p.TempDir, name & ".srip")`；`:108-173 GetJobs`/`SaveJobs` 用 **`BinaryFormatter`** 存 `Folder.Settings\Jobs.dat`。**执行串行、递归、且执行前先把任务停用**：`GlobalClass.vb:485-540 ProcessJobsRecursive()`，`:496 JobManager.ActivateJob(jobPath, False)` ← **失败即静默丢失、不重试、不阻塞队列**；`:516-519` 若进程内存 >1500MB 则**重启自身**。
- **错误恢复 = 存恢复项目后自杀（反面）**：`GlobalClass.vb:1578-1607 OnException(ex)` 写 `Path.Combine(p.SourceFile.Dir, "recovery.srip")`（`:1592`）、`ShowException`、`MakeBugReport`，`:1605 Process.GetCurrentProcess.Kill()`；下次启动 `MainForm.vb:2880 OpenProject(recoverProjectPath)`。
- **异常三分（可借鉴）**：`General/General.vb:828-852` —— `ErrorAbortException`（带 `Title`，构造时把错误写进项目日志）、`AbortException`（用户取消，**静默**）、`SkipException`（跳过本步）。
- **工具注册表 + 版本判定（后者不可取）**：`Package.vb:8 Public Class Package`；`:11-42` 字段含 `Find As Boolean = True`、`HelpSwitch`、`Location/Locations`、`StatusFunc`、`Version :37`、`VersionAllowNew/VersionAllowOld = True :38-40`、`VersionDate As Date :41`；`:44 Shared Property Items As New SortedDictionary(Of String, Package)`（**约 150 个工具**，`:46-1443`）。发现顺序 `GetPathFromLocation :3236,3304-3332` → `FindEverywhere(fileName, ignorePath) :3336/:3360` → `FindInMuiCacheKey :3370` → `FindInPathEnvVar :3402` → `FindInAppKey :3416`。**版本判定用文件时间戳而非解析版本串**：`:3047-3055 If (VersionDate - File.GetLastWriteTimeUtc(filepath)).TotalDays > 2 Then Return True`（反向 `< -2` 于 `:3057-3065`）→ **不可取**。状态转人话 `:2999-3045 GetStatusLocation/GetStatusVersion/GetStatusDisplay`（无问题返回 `"OK"`，有问题点名菜单 "Tools > Download (Ctrl+D)"）；总闸 `GlobalClass.vb:861-874 VerifyRequirements()`。工具目录注入子进程 PATH `Proc.vb:411-446 SetEnvironmentVariables(process)`（`:431-437` 把每个有 `HelpSwitch` 的包目录前置）；命令里可写宏 `%app_path:ffmpeg%`。
- **进度 = 5 段回退正则阶梯（本项目不该抄）**：`ProcController.vb:636-697 SetProgress(value)` 依次 `:643 "(?:\s|^)\[?\s*(\d+(?:[.,]\d+)?)%\]?\s?"` → `:647 "(\d+(?:[.,]\d+))\/100"` → `:651 "frame(?:(?:=\s*)|\s+)(\d+)(?:\s|\/)"` → `:655 "\s(\d+)(?:\s?\/\s?(\d+))?\sframes"` → `:660 "time=((\d+):(\d+):(\d+))"`（`:665` 按 `_ffmpegDuration` 换算），兜底 `:676-678`。帧数/时长从 ffmpeg banner 抓：`:168-185 :170 Regex.Match(value, "NUMBER_OF_FRAMES(?:-\w+)?\s*:\s+(\d+)", IgnoreCase)`、`:177 "DURATION(?:-\w+)?\s*:\s+((\d+):(\d+):(\d+))"`（仅当包是 ffmpeg/DoViTool/HDR10PlusTool 时启用）。行过滤/节流：`Proc.vb:448-480 ProcessData(value)` 去 ANSI（`:453 Regex.Replace(value, "\x1B\[[0-9;]*[mK]", "")`）；`ProcController.vb:357-374 ProgressHandler` 去重 + 每次 +25ms 间隔。**本项目应改用 Media3 `Transformer.getProgress(ProgressHolder)`，不引入解析层。**
- **退出码契约按步骤声明（可借鉴）**：`Proc.vb:10 Property AllowedExitCodes As Integer() = {0}`；`:324 ExitCode = Process.ExitCode`；`:326-332 If Abort Then Throw New AbortException / If Skip Then Throw New SkipException`；`:334 If ExitCode <> 0 AndAlso Not AllowedExitCodes.ContainsEx(ExitCode)` → 组装错误块。合法非零例 `Demux.vb:165 proc.AllowedExitCodes = {0, 1, 2}`（`mkvextract timestamps_v2`）。**退出码→人话是显式功能**：`Proc.vb:335-360` 拼 `"{Header} returned exit code: {ExitCode} (0x{ExitCode:X})" :342`，若 `s.ErrorMessageExtendedByErr` 则调 `Err.exe "/ntstatus.h /winerror.h " & ExitCode`（`:351-357`）并加前缀 "It's unclear what this exit code means, in case it's a Windows system error then it possibly means:"，最后 `:360 Throw New ErrorAbortException("Error " + Header, sb.ToString(), Project)`。编码后校验输出存在 `VideoEncoder.vb:127-130 If Not g.FileExists(op) Then Throw New ErrorAbortException("Encoder output file is missing", op)`。

**可复用 / 可借鉴 / 不采纳**：**可复用**：声明式设置页 + 字段绑定（`SimpleUI.vb:265-461` + `MainForm_ShowOptions.vb:26-95`，Compose 版 = `data class SettingSpec` + 一个 `when` 渲染器）；**目标体积扣减法公式**（`Misc.vb:125-132`）；容器 × 编码器显式表；**「重封装 = 空编码器」抽象**（`VideoEncoder.vb:777-857`，上游流水线不分叉）；用「工程模板」而非分支表达快速封装 vs 重新编码（`General.vb:253-269`）；**产物过小即判失败**（`MainForm.vb:3550-3552`、`:3640`）；静态 HDR10 元数据注入；**HDR 元数据「不改码流」抽取**（`-c:v copy -bsf:v hevc_mp4toannexb -f hevc - | tool`；Android 直接 `MediaExtractor` 读 SEI 即可，但「只搬码流不解码」的原则要保留）；质量模式与体积模式互斥；诊断集中面板；**容器能力谓词 `IsSupported(fileType)` + 每容器白名单数组**（`Muxer.vb:1037-1068`）；**「编码」与「封装」硬分离，封装永远 `-c:v copy -c:a copy`**（`Muxer.vb:1099`）从临时文件写出；faststart 按容器门控（`Muxer.vb:1100`）；声明式解复用注册表 `{Name, Active, InputExtensions, OutputExtensions, InputFormats, Command, Arguments}` + 宏展开；逐字段 schema 版本 + `Check(obj, key, version)` 迁移；工具状态词汇表（`GetStatusLocation`/`GetStatusVersion`/`GetStatusDisplay` → "OK"）；每步 `AllowedExitCodes = {0}`；**退出码→人话映射**（`Proc.vb:334-360`）；**异常三分** `ErrorAbortException(title, message)` 自写入项目日志 vs `AbortException`（取消）静默；**HDR 提取管线形状**：探源 → 先找已有 sidecar → `ffmpeg -c:v copy -bsf:v hevc_mp4toannexb -f hevc - | <工具>` → 按体积（>100 字节）校验 → 路径挂项目 → 编码器选项注入，且**裁切/裁剪必须回写 RPU**；`-bsf:v hevc_mp4toannexb` 作为「MP4→裸 HEVC」的具体转换。**可借鉴**：预设 = 可序列化配置对象 + 版本化（`.srip` → Android 用 Room 存 JSON 列）；「压缩率目标」`AutoCompCheckValue`（直观，但需一次试编码 ⇒ Android 用短视频试算 + 线性外推）；「首遍试算 → 修正目标码率」闭环（Media3 Transformer 无 2-pass，但闭环思想可用）；`Config = {min, max, step}` 值域元数据 → Compose 校验规则；`.srip` 的「模板 = 无源项目」机制。**不采纳**：约 150 字段的扁平 `Project` 神对象；`BinaryFormatter` 存 `Jobs.dat`/`Profiles.dat`/`Settings.dat`；**用文件最后写入时间 ±2 天判定版本**（`Package.vb:3047-3065`）；执行前先停用任务（`GlobalClass.vb:496`）导致失败静默丢件；`Process.GetCurrentProcess.Kill()` 作错误恢复 + 为绕内存增长重启自身；5 段回退的正则进度阶梯与硬编码 ffmpeg banner；`For i = 1 To 100000` 暴力搜索目标体积（Android 码率对体积线性，直接闭式解）；Win32 `SuspendThread`（平台绑定）；`HintDirFunc` 注册表探测（Android 无外部工具）；要求用户安装约 150 个外部控制台工具。

### 2.10 `FFmpeg` 本体（**限定范围**：只取「容器与码控的能力边界」作为交叉验证）

`video-transcode-repos/FFmpeg` 是 FFmpeg 本体（10,842 文件 / 73.5 MB 代码，**LGPL-2.1+**，`LICENSE.md`「Most files … under the GNU Lesser General Public License version 2.1」）。**本项目已裁决不引入 FFmpeg**（`docs/architecture/phase-14-experiment-ledger.md`：FFmpeg 后端 = No-Go，理由已从「许可」改为「无平台确实做不到的任务集」）。因此**不调研其编解码实现、硬件编码器包装与滤镜体系**，只在需要交叉验证「容器能力如何表达、哪些拷贝会坏」时读取五个点。**下文的结论全部用于给本项目的 `MediaMuxer` / `Muxer.Factory` 边界做对照，不是引入建议。**

- **muxer 能力表是怎么表达的**：注册表是 configure 生成的构建产物 `libavformat/muxer_list.c`（**不在源码树**），`libavformat/allformats.c:597 #include "libavformat/muxer_list.c"`、`:603 const AVOutputFormat *av_muxer_iterate(void **opaque)` 遍历 `muxer_list[]`（**189 个 muxer**）。结构 `libavformat/mux.h:61-65 typedef struct FFOutputFormat { AVOutputFormat p; int priv_data_size; int flags_internal; int (*write_header)(...); … }`，包装公开的 `AVOutputFormat`；能力字段在 `libavformat/avformat.h:537-541`（`extensions` / `audio_codec` / `video_codec` / `subtitle_codec`）。**限制机制有两套**：其一是 `mux.h:52-59 FF_OFMT_FLAG_ONLY_DEFAULT_CODECS (1 << 3)`，注释即定义 —— 「the only permitted audio/video/subtitle codec ids are AVOutputFormat.audio/video/subtitle_codec; if any of the latter is unset (i.e. equal to AV_CODEC_ID_NONE), then no stream of the corresponding type is supported.」；其二（**实际用到的**）是 `codec_tag` 表 —— `movenc.c:9524-9526 ff_mov_muxer.p.codec_tag = { ff_codec_movvideo_tags, ff_codec_movaudio_tags, ff_codec_movsubtitle_tags, 0 }`、`matroskaenc.c:3731-3734` 用 `ff_codec_bmp_tags`/`ff_codec_wav_tags`/`additional_audio_tags`/`additional_subtitle_tags`。
- **真正做白名单的容器用 `query_codec` 谓词**：`libavformat/matroskaenc.c:3744-3751 static int webm_query_codec(enum AVCodecID codec_id, int std_compliance) { for (int i = 0; ff_webm_codec_tags[i].id != AV_CODEC_ID_NONE; i++) if (ff_webm_codec_tags[i].id == codec_id) return 1; return 0; }`，`:3767 .query_codec = webm_query_codec`；白名单 `libavformat/matroska.c:110-124 const CodecTags ff_webm_codec_tags[] = { {"V_VP8",VP8},{"V_VP9",VP9},{"V_AV1",AV1},{"A_VORBIS",VORBIS},{"A_OPUS",OPUS},{"D_WEBVTT/…",WEBVTT}×4,{"",NONE} }`。`matroskaenc.c:3785 .p.video_codec = AV_CODEC_ID_NONE` 是**「该类型完全不支持」的表达方式**。tag 查找**先命中先赢**：`libavformat/utils.c:133-141 unsigned int ff_codec_get_tag(const AVCodecTag *tags, enum AVCodecID id)`（顺序遍历、`AV_CODEC_ID_NONE` 终止）；`movenc.c:2268-2285 mov_find_codec_tag()`。本项目相关容器定义：`movenc.c:9510-9530 ff_mov_muxer`（`.p.extensions="mov"`、`.p.audio_codec=AAC`、`.p.video_codec=CONFIG_LIBX264_ENCODER ? H264 : MPEG4`、`.p.flags=AVFMT_GLOBALHEADER|AVFMT_TS_NEGATIVE|AVFMT_VARIABLE_FPS`）、`:9553-9572 ff_mp4_muxer`（`"mp4"`、`"video/mp4"`）、`matroskaenc.c:3714-3740 ff_matroska_muxer`（`"mkv"`、`.p.subtitle_codec=ASS`、`.p.flags=…|AVFMT_TS_NONSTRICT`）、`:3753-3773 ff_webm_muxer`（`"webm"`、`.p.subtitle_codec=WEBVTT`）、`:3777-3796 ff_matroska_audio_muxer`（`"mka"`、`.p.video_codec = AV_CODEC_ID_NONE`）。
- **`-movflags` 全表与 `faststart` 的真实代价**：定义在 `libavformat/movenc.h:278-302`，用户名字表 `movenc.c:93-118`（24 个 `AV_OPT_TYPE_CONST`）：`rtp_hint`(1<<0) · `fragment`(1<<1) · `empty_moov`(1<<2) · `frag_keyframe`(1<<3) · `separate_moof`(1<<4) · `frag_custom`(1<<5) · `isml`(1<<6) · **`faststart`(1<<7，"Run a second pass to put the index (moov atom) at the beginning of the file")** · `omit_tfhd_offset`(1<<8) · `disable_chpl`(1<<9) · `default_base_moof`(1<<10) · `dash`(1<<11) · `frag_discont`(1<<12) · `delay_moov`(1<<13) · `global_sidx`(1<<14) · `write_colr`(1<<15) · `write_gama`(1<<16) · `use_metadata_tags`(1<<17) · `skip_trailer`(1<<18) · `negative_cts_offsets`(1<<19) · `frag_every_frame`(1<<20) · `skip_sidx`(1<<21) · `cmaf`(1<<22) · `prefer_icc`(1<<23) · `hybrid_fragmented`(1<<24)；另有 int 选项 `min_frag_duration`。**`faststart` 的代价**：`movenc.c:8341-8343 mov_init` 里 `mov->reserved_moov_size = -1`（不预留头部空间）；`:9242-9249 mov_write_trailer` 里走 `shift_data(s)` 再回写 moov；`:9123-9136 shift_data()` → `libavformat/mux_utils.c:71-115` **逐字节搬移整个 mdat**（`buf = av_malloc_array(shift_size, 2);` … `ret = s->io_open(s, &read_pb, s->url, AVIO_FLAG_READ, NULL); if (ret < 0) { av_log(s, AV_LOG_ERROR, "Unable to re-open %s output file for shifting data\n", s->url); }`，注释「the AVIO context of the output can only be used for writing, so we re-open the same output, but for reading」）⇒ **需要可重开、可 seek 的具名输出，并多付一整遍全文件拷贝；管道/不可 seek 的 sink 上无法实现**。互相牵制规则在 `mov_init`：`:8296-8297` `delay_moov` 隐含 `empty_moov`；`:8322-8328` 分片隐含 `fragment|empty_moov|separate_moof`；`:8357-8359` `empty_moov` 无 `delay_moov` 时警告；`:8336-8338` `global_sidx` 与 `skip_sidx` 冲突时忽略后者；`:8361-8362` `cmaf` 清除 `negative_cts_offsets`；`:8311-8318` **`hybrid_fragmented` 与 `faststart` 不可同用**。
- **`hvc1` vs `hev1`（本项目直接相关）**：**权威注释在 `libavformat/isom_tags.c:124-126`** —— `{ AV_CODEC_ID_HEVC, MKTAG('h','e','v','1') }, /* HEVC/H.265 which indicates parameter sets may be in ES */`、`{ AV_CODEC_ID_HEVC, MKTAG('h','v','c','1') }, /* HEVC/H.265 which indicates parameter sets shall not be in ES */`、`{ AV_CODEC_ID_HEVC, MKTAG('d','v','h','e') }, /* HEVC-based Dolby Vision derived from hev1 */`。**`hev1` 在表里在前，而 `ff_codec_get_tag` 先命中先赢 ⇒ `hev1` 是 FFmpeg 的 MP4/MOV 默认 HEVC tag，所以必须显式 `-tag:v hvc1`。** 同类 H.264：`isom_tags.c:129 { AV_CODEC_ID_H264, MKTAG('a','v','c','1') }` 为默认，`avc3` 表示带内参数集。该 tag 改变写出内容：`movenc.c:1713-1726 mov_write_hvcc_tag()` 在 `track->tag == MKTAG('h','v','c','1')` 时以 `1` 调 `ff_isom_write_hvcc(…)`，否则传 `0`（`lhvC` 同形 `:1728-1748`）；并把参数集 NAL 从基本流剥掉 —— `movenc.c:7244-7263 int filter_ps = (trk->tag == MKTAG('h','v','c','1'));` → `libavformat/hevc.c:1204-1233 ff_hevc_annexb2mp4(…)` 在 `filter_ps` 置位时跳过 `HEVC_NAL_VPS/SPS/PPS`（`hevc.c:1223-1233`）。解码侧分支 `libavformat/mov.c:9596 case MKTAG('h','v','c','1'):`；`libavformat/codecstring.c:167` 构造 RFC 6381 codec string 时对 `hvc1` 需要完整参数集列表。**结论：`hev1` 允许带内 VPS/SPS/PPS，`hvc1` 禁止且要求只存在于 `hvcC`；大量 Apple/QuickTime 与 Android 硬解只认 `hvc1`。它安全的前提是 muxer 把参数集搬进 `hvcC` 而不是丢弃。**
- **`-c copy` 的五条限制（全在 `libavformat/mux.c`）**：① 时间戳必须存在 —— `:793-798 "Timestamps are unset in a packet for stream %d"` → `AVERROR(EINVAL)`；② DTS 必须单调，除非容器声明可放宽 —— `:800-810 "Application provided invalid, non monotonically increasing dts to muxer in stream %d"`（非交织路径 `:557-566` 同），**逃生口是 muxer flag `AVFMT_TS_NONSTRICT`：Matroska 设了（`matroskaenc.c:3729-3730`/`:3769-3770`/`:3792`），MP4/MOV 没设（`movenc.c:9523`/`:9567`）⇒ 同一次 DTS 回退的拷贝进 MKV 成功、进 MP4 报错**；③ PTS < DTS 直接失败 —— `:812-816 "pts %"PRId64" < dts %"PRId64" in stream %d"`；④ 目标容器表达不了该 codec 时**在写头部就失败** —— `movenc.c:8604-8611 "Could not find tag for codec %s in stream #%d, codec not currently supported in container"`（`mxfenc.c:3114` 同文案）；⑤ 时长非法只告警 —— `mux.c:605-611 guess_pkt_duration` 里 `if (pkt->duration < 0 && …) { av_log(s, AV_LOG_WARNING, "Packet with invalid duration %"PRId64" in stream %d\n", …); pkt->duration = 0; }`，再按 `st->avg_frame_rate`/`av_get_audio_frame_duration2` 合成。**实操清单：copy 失败或产出损坏的充分条件为 ① 某流无时间戳；② DTS 回退且容器严格（MP4/MOV）；③ PTS<DTS；④ 该 codec 在目标容器 `codec_tag`/`query_codec` 白名单里无 tag；⑤ 负/缺时长被静默归零而索引失准。**
- **CRF / QP / 码率语义**：`-b:v`/`-maxrate`/`-minrate`/`-bufsize` 是**通用 `AVCodecContext` 选项** —— `libavcodec/options_table.h:52 {"b", "set bitrate (in bits/s)", OFFSET(bit_rate), …, {.i64 = AV_CODEC_DEFAULT_BITRATE}}`、`:53 {"ab", …, {.i64 = 128*1000}}`（同一字段的音频别名）、`:150 {"maxrate", "maximum bitrate (in bits/s). Used for VBV together with bufsize."}`、`:151 {"minrate", "minimum bitrate (in bits/s). Most useful in setting up a CBR encode. It is of little use otherwise."}`、`:153 {"bufsize", "set ratecontrol buffer size (in bits)"}`、`:219 rc_init_occupancy`。⇒ `-b:v` + `-maxrate` + `-bufsize` = **带 VBV 上限的 ABR**；`-minrate == -maxrate == -b:v` 即 CBR 惯用法。**`-crf` 不是通用选项，各编码器包装私有定义** —— `libavcodec/libx264.c:1529`（FLOAT，`{.dbl = -1}, -1, FLT_MAX`）、`libx265.c:1044`、`libvpxenc.c:2009`（INT，`-1..63`）、`libaomenc.c:1599`（同）、`libsvtav1.c:826`、`libxeve.c:570`（`{.i64 = 32}, 10, 49`）⇒ **`-crf` 的合法区间与含义逐编码器不同，跨编码器的「质量值」不能直接当 `-crf` 传，必须有一张映射表。** **`-qp` 是调试项不是码控** —— `options_table.h:197 {"qp", "per-block quantization parameter (QP)", 0, AV_OPT_TYPE_CONST, {.i64 = FF_DEBUG_QP}, …, V|D, .unit = "debug"}`（属 `-debug` unit，`D` = 仅解码）；真正的量化控制是通用 `global_quality` `:213`，CLI 上是 `-qscale`/`-q:v`，由 `AV_CODEC_FLAG_QSCALE` 门控（`:63`）。相关旋钮 `:98 {"g", "set the group of picture (GOP) size", {.i64 = 12}}`、`:104 qcomp`、`:108 {"qmin", {.i64 = 2}, -1, 69}`、`:109 {"qmax", {.i64 = 31}, -1, 1024}`、`:111 bf`、`:67/:68 pass1/pass2`、`:73 global_header`、`:228 profile`、`:231 level`。**三种模式**：**(a) 恒定质量** `-crf N`（编码器私有、区间各异），常配 `-b:v 0` 显式关掉码率目标；**(b) 恒定量化** `-qscale`/`-q:v` → 通用 `global_quality` + `AV_CODEC_FLAG_QSCALE`（固定 QP、无自适应码控）；**(c) 恒定/受限码率** `-b:v` 目标，可选 `-minrate`/`-maxrate` + `-bufsize` 构成 VBV，`-pass 1`/`-pass 2` 做两遍。**FFmpeg 没有任何「目标文件大小」选项** —— 目标体积是调用方算出来的**计划**（`bits = size × 8`、`bitrate = bits / duration`），再以 `-b:v` 交给编码器，必要时用两遍编码收敛。

**可借鉴 / 不适用**：**可借鉴**：① **能力即数据** —— 容器以显式 tag/白名单表 + `query_codec(id) -> bool` 谓词声明可接受的 codec（`matroska.c:110-124` + `matroskaenc.c:3744-3751`）；② `FF_OFMT_FLAG_ONLY_DEFAULT_CODECS` 的注释（`mux.h:52-59`）精确给出「容器 X 支持 codec Y」的两种语义，其中 `AV_CODEC_ID_NONE` 表示「该媒体类型完全不支持」；③ **`hvc1` 作为 HEVC 进 MP4 的硬性自动要求**，不给用户开关（与 shutter-encoder `Rewrap.java:126-129` 一致）；④ `AVFMT_TS_NONSTRICT` 背后的设计问题 —— 「哪些容器容忍非单调时间戳」应是一个逐容器布尔值，具体事实是 **MP4/MOV 严格、MKV 宽松**；⑤ 三种码控模式及其选项拼写，作为质量预设映射到 MediaCodec `KEY_BITRATE_MODE`（`BITRATE_MODE_CQ` ≈ CRF/QP、`BITRATE_MODE_VBR`、`BITRATE_MODE_CBR`）的词汇表；⑥ **copy 的预检错误分类** —— 无时间戳 / DTS 回退 / PTS<DTS / 容器无此 codec 的 tag / 负时长，每一条都可以在启动 remux **之前**检查，而不是等到产出损坏文件；⑦ `mux_utils.c:71-115` 解释了 faststart 为何要付一遍全文件拷贝并要求可 seek 可重开的输出。**不适用**：① FFmpeg 本体（已 No-Go）；② `codec_tag` 机制 —— MP4 sample entry 的 fourcc 由 `MediaMuxer` 写出、调用方不可设，**`-tag:v hvc1` 在 Android 没有直接对应物**（现实替代是封装后改写容器或接受平台 tag）；③ `-movflags faststart` 作为选项 —— `MediaMuxer` 无第二遍、无法重开读，只能「写完再改写」或接受 moov 在尾；④ `AVFMT_TS_NONSTRICT` 的逃生口 —— `MediaMuxer` 无逐容器放宽开关，本项目必须**自己在交给 muxer 之前归一化时间戳**。

---

## 3. 改造方案

> 本节把 §1/§2 的可复用与可借鉴项，逐条落到本项目的**具体模块或文件**，并给出优先级、分阶段顺序、风险与工作量。**改造点的取舍以 `docs/architecture/Organizing-Page-Function-Design.md` 的既有裁决（D0–D13）为约束**：与之冲突的参考做法一律不采纳，只采纳「补齐既有裁决」的部分。
>
> 编号规则：`T` 转码/压缩域、`D` 去重域、`R` 回收站域、`C` 切片/AB 导出域、`J` 任务中心与工程基础设施、`X` 跨域。优先级：**P0 = 不修就是错的**（功能不成立或数据会坏）；**P1 = 补齐既有产品裁决**；**P2 = 体验与工程加固**。**破坏性**列标注是否需要同时改既有测试。

### 3.0 改造点总览

| 编号 | 改造点 | 落点 | 优先级 | 破坏性 | 主要依据 |
| --- | --- | --- | --- | --- | --- |
| T1 | 预设码率/质量真正写入编码器 | `data/processing/transcode/Media3TranscodeEngine.kt` | **P0** | 否 | Media3 `VideoEncoderSettings`；staxrip `VideoEncoder.vb:169-173` |
| T2 | `TranscodePreset` 折叠为 `RateControl` 三态 | `domain/transcode/TranscodeContracts.kt` | **P0** | 是 | VidCoder `VCVideoEncodeRateType`；FastFlix `setting_panel.py:632` |
| T3 | 目标体积模式（扣减法） | `TranscodeContracts.kt` + `Media3TranscodeEngine.kt` | P1 | 是 | staxrip `Misc.vb:125-132`；VidCoder `CalculateBitrate` |
| T4 | 尺寸求解 `solveGeometry` + 修 `&&`→`\|\|` | `TranscodeContracts.kt:153-223` | **P0** | 是 | HandBrake `hb.c:1231-1360` |
| T5 | 轨道策略 `TrackSelectionPolicy`，禁止静默丢轨 | `TranscodeContracts.kt:215-217` | **P0** | 是 | VidCoder `ProcessingService.cs:3324-3328/3499-3500` |
| T6 | HDR 不再无条件拒绝 | `Media3TranscodeEngine.kt:51-53` + `AndroidMediaCapabilityProbe.kt:114` | P1 | 是 | Media3 `Composition.HdrMode`；staxrip `MainForm.vb:3511-3669` |
| T7 | 能力探测强制超时 + 失败降级不报错 | `AndroidMediaCapabilityProbe.kt` | P1 | 否 | shutter `LibraryUtils.java:1083-1092`；FastFlix `application.py:189-199` |
| T8 | 产物过小/不存在即判失败 | `Media3TranscodeEngine.kt` + verifier | P1 | 否 | staxrip `MainForm.vb:3550-3552`；VidCoder `ProcessingService.cs:2415/2423` |
| T9 | 失败码结构化分段 | `TranscodeContracts.kt` | P2 | 是 | VidCoder `VCEncodeResult.cs` |
| T10 | 参数预览面板（替代 FFmpeg 命令行展示） | 新增 UI | P2 | 否 | FastFlix `command_panel.py:17/39` |
| T11 | 声明式设置页 + 字段绑定 | 新增 UI | P2 | 否 | staxrip `SimpleUI.vb:265-461` |
| T12 | 质量模式与体积模式 UI 互斥 | 新增 UI | P1 | 否 | staxrip `MainForm.vb:5088` |
| D1 | `quickSignature` 加中间 1 MiB | `data/duplicates/`（指纹生成） | **P0** | 是 | VideoCull `duplicates.js:181-193` |
| D2 | 指纹缓存键含算法/采样/抽取器版本 | `domain/duplicates/` + Room 列 | **P0** | 是 | VideoCull `duplicate-utils.js:121-135`；0x90d `FileEntry.cs:83-90` |
| D3 | 「这不是重复」持久化忽略对 | 新增表 + 扫描排除 | P1 | 是 | ddoss `ignorelist.py`；VideoCull `ignoredDuplicatePairs` |
| D4 | 两阶段预览/执行 + 身份校验 | 去重处置流程 | P1 | 是 | ddoss `--dry-run` 共用路径 + `load_replay_json` |
| D5 | 稳定组 id + 组信息反规范化到 media | Room schema | P1 | 是 | VideoCull `duplicates.js:1001-1003/1167-1197` |
| D6 | 展示分数 = 阈值语义（最弱对向下取整） | 相似匹配（**当前关闭**） | P2 | — | VideoCull `duplicates.js:1142-1146` |
| D7 | 链式误合并三层防护 | 相似匹配（**当前关闭**） | P2 | — | VideoCull `duplicates.js:886-962/1072-1084` |
| D8 | 扫描分层下沉到 SQL，不整库入内存 | `DefaultDuplicateScanner.scan()` | **P0** | 是 | 既有 G20 缺陷 |
| R1 | 回收站后端按所有权分派（系统 / 应用副本） | 回收站域 | **P0** | 是 | `ADR-RECYCLE-003/004` |
| R2 | 决策与文件操作彻底分离 + 全局删除集合 | 回收站 UI + 域 | P1 | 否 | VideoCull `delete-and-safety.mdx:12/40` |
| R3 | 确认框写明降级路径与总条数/总大小 | 回收站 UI | P1 | 否 | VideoCull `utils.ts:12-26` |
| R4 | 回收站副本完整性验证 + 失败不删唯一副本 | 回收站事务 | **P0** | 是 | 资料「不能把复制成功等同于移入成功」 |
| R5 | 删除请求限定已加载目录内路径 | 回收站域 | P1 | 否 | VideoCull `duplicates.js:52-56` |
| R6 | 批量操作合并为一步撤销 + 恢复界面位置 | 回收站 UI | P2 | 否 | VideoCull `store.ts:1195-1201/1259` |
| R7 | 事后对账状态（已处置 / 疑似外部处置） | 回收站 + 去重结果 | P2 | 否 | ddoss `PairResolutionStatus.swift:4-11` |
| C1 | 导出前显示「请求起止 vs 实际起止」 | 切片导出 UI | **P0** | 否 | lossless-cut `ExportConfirm.tsx:245`（**其缺口**） |
| C2 | 至少一帧时长 + 拼接减一帧 | `PlatformClipEngine.kt` | P1 | 是 | lossless-cut `smartcut.ts:73`、`useFfmpegOperations.ts:716` |
| C3 | 关键帧对齐显式化（不静默吸附） | 切片 UI + 引擎 | P1 | 否 | lossless-cut `ffmpeg.ts:112-129` |
| C4 | 区间层 + 补集层双语义可视化 | 切片/AB UI | P1 | 否 | lossless-cut `Timeline.tsx:421-449` |
| C5 | 每段独立显示 时长/毫秒/帧数/估算体积 | 切片 UI | P2 | 否 | lossless-cut `SegmentList.tsx:274-283` |
| C6 | AB 区间 → 导出切片入口 | `PlayerViewModel` + `ProcessingViewModel` | **P0** | 是 | 既有 G9；REX `ClipExportSheet.kt` |
| C7 | 幂等跳过 + 覆盖开关 | `ClipProcessing.kt` | P2 | 否 | lossless-cut `useFfmpegOperations.ts:112-127` |
| J1 | 任务队列持久化：暂停/恢复/断点续做/失败重试 | `InAppProcessingScheduler` + Room | **P0** | 是 | lossless-cut（**反面**）；HandBrake `QueueService.cs:521-524` |
| J2 | 三态暂停机（全部 worker quiescent 才算暂停） | 调度器 | P1 | 否 | VideoCull `processing-pause.js:11-19` |
| J3 | 按资源串行 + 三级优先级 | 调度器 | P1 | 否 | VideoCull `keyed-operation-queue.js` |
| J4 | 进度节流 + Elapsed 计时 + 取消唤醒 | 调度器 + UI | P1 | 否 | VideoCull（节流）；lossless-cut `Working.tsx`（计时） |
| J5 | 失败原因稳定枚举 + 退出语义 | 处理任务域 | P1 | 是 | dublette `skip.rs:64-72`；compressO `media_process.rs:663-672` |
| J6 | 数据库原子保存 + 损坏隔离 | Room/持久化 | P2 | 否 | 0x90d `DatabaseUtils.cs:44-56/59-123` |
| J7 | 取消后由回调删除半成品（含队列级与孤儿清理） | `AndroidProcessingArtifactStore` + 两个 Executor | P1 | 否 | compressO `ffmpeg.rs:796-800` + `media_process.rs:17-98`；shutter-encoder `Shutter.java:2940-2962` |
| X1 | 容器 × 编码器显式声明表（反查 `Muxer.Factory`） | `PlatformClipEngine.kt:207-213` + 能力探测 | **P0** | 是 | compressO `VideoCodec.tsx:23-54`；既有 G11 |
| X2 | 独立验证器统一为所有输出路径的唯一成功来源 | verifier | **P0** | 是 | 既有裁决；VidCoder `ProcessingService.cs:2415/2423` |
| X3 | 结构化参数/任务摘要（可回读） | 处理任务域 | P2 | 否 | ddoss `write_json`/`load_replay_json` |
| X4 | remux 前置校验：把「产出损坏文件」变成「拒绝并给理由」 | `PlatformClipEngine.fastCut()` 之前 | **P0** | 是 | FFmpeg `mux.c:793-816`/`movenc.c:8604-8611`；§2.10 |

### 3.1 转码 / 压缩域（T）

**T1 · 预设码率/质量真正写入编码器（P0，非破坏性）**
现状缺陷：`Media3TranscodeEngine.kt:79-84` 构造 `Transformer` 时**没有 `setEncoderFactory`、没有传任何码率**，实际码率由 `DefaultEncoderFactory.getSuggestedBitrate()` 按设备能力推导 ⇒ `compatible_mp4`（8 Mbps）与 `balanced_mp4`（5 Mbps）除文件名外**完全等价**。
改造：构造 `DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(preset.targetVideoBitrate).setBitrateMode(...).build()).build()` 并 `Transformer.Builder(...).setEncoderFactory(factory)`。
注意：`VideoEncoderSettings.BitrateMode` 取值来自平台 `MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_*`，**不是 x264 的 CRF 语义**——`BitrateMode` 必须由能力探测得出，不能硬编码（见 T7）。参考 staxrip 把「质量模式」表达成编码器自声明属性（`Encoding/VideoEncoder.vb:169-173 IsCompCheckEnabled => Not QualityMode`），而非在调用点分支。
验证：改前/改后单测——同一 `SourceMediaInfo` + 三个预设，断言传给编码器的 `KEY_BIT_RATE` 互不相同且等于预设值；真机上对同一源视频跑 `compatible_mp4` 与 `balanced_mp4`，**断言输出体积差异 > 20%**（这是修好与否的判别式）。

**T2 · `TranscodePreset` 折叠为 `RateControl` 三态（P0，破坏性）**
现状：`TranscodeContracts.kt:75 data class TranscodePreset(...)` 只有单一码率模式；`:95-109 TranscodePresets` 三个硬编码常量。
改造：引入 `sealed interface RateControl { Quality(level, scale) / Bitrate(bitsPerSecond) / TargetSize(bytes) }`，`TranscodePreset` 持有 `rateControl` 而非裸码率；`QualityScale` 必须显式区分 `BITRATE_FACTOR` 与 `QP`。落盘与进编码器时**只解析一个分支**（采纳 VidCoder 三模式互斥 + FastFlix `setting_panel.py:632 get_mode_settings()` 返回单一 `Tuple[str, Union[float,int,str]]`）。
验证：`TranscodePresets` 三档仍对外只暴露「质量档 + 最大分辨率」（符合 `docs/05` Q112/Q539），断言不出现任意数值输入；`RateControl` 序列化往返测试。

**T3 · 目标体积模式（P1，破坏性）**
采用 staxrip 的**扣减法**（`General/Misc.vb:125-132`）：`videoBytes = 目标字节 − 音频字节 − 容器开销 − 元数据字节`，再 `videoBitrate = videoBytes * 8 / 时长秒`。
- 音频字节 = `audioBitrate * 时长秒 / 8`（`Misc.vb:171-173`）；
- 容器开销用**每帧常量表**（`Misc.vb:153-169`：mp4 `0.013 KB/帧`、mkv `0.014 KB/帧`、avi `0.024 KB/帧` + 每条外部音轨 `0.04 KB/帧`）——Android 只出 MP4，取 `0.013 KB/帧`；
- **不做暴力搜索**（staxrip `GlobalClass.vb:1142-1163` 的 `For i = 1 To 100000` 在 Android 上无意义，码率对体积线性，闭式解即可）；
- 采纳 VidCoder `CalculateBitrate` 的边界处理：`availableBytes < 0` 返回 0 并**明确报错而非静默降质**（符合 `docs/05` Q500）。
验证：断言「目标 100 MB 的 10 分钟视频」算出的码率与 `(100MB − 音频 − 开销) × 8 / 600s` 一致；断言目标体积小于音频+开销时返回明确拒绝码。

**T4 · 尺寸求解 `solveGeometry` + 修 `&&`→`||`（P0，破坏性）**
现状 bug：`TranscodeContracts.kt:153-223` 的 `Rejected("ENCODER_SIZE_UNSUPPORTED")` 判定写作 `if (width > maxWidth && height > maxHeight)`，**应为 `||`**——横屏源配竖屏上限时会漏判。
改造：抽出纯函数 `solveGeometry(src, preset, encoderCapability)`，顺序 = **旋转 → 裁剪 → `modulus`（默认 2）→ 不允许放大时把上限压回源尺寸 → 偶数对齐**（采纳 HandBrake `libhb/hb.c:1231 hb_set_anamorphic_size2` 的子集；`:1248 int mod = (geo->modulus > 0) ? EVEN(geo->modulus) : 2`；`:1354-1360 if (!upscale) maxWidth = MULTIPLE_MOD_DOWN(cropped_width + pad_width, mod)`）。
核心不变量：`result.longEdge <= min(srcLongEdge, preset.maximumLongEdge, encoderMaxLongEdge)` 且两边均为 `modulus` 的倍数。
验证：改前测试暴露 `&&` bug（横屏源 + 竖屏上限应被拒绝却通过）；改后参数化用例覆盖 横/竖/方 × 放大/不放大 × 奇数尺寸。

**T5 · 轨道策略 `TrackSelectionPolicy`，禁止静默丢轨（P0，破坏性）**
现状缺陷：`TranscodeContracts.kt:215-217` 的 `retainedAudioTrackIds` **只保留第一条音轨**，字幕全丢 ⇒ 违反 `docs/05` Q498「不静默丢轨」。
改造：引入 `data class TrackSelectionPolicy(audioMode, audioIndices, audioLanguageCodes, audioLanguageAll, subtitleMode, subtitleLanguageCodes, subtitleBurnIn)` + `enum AudioSelectionMode { KEEP_SELECTED, NONE, FIRST, BY_INDEX, LANGUAGE, ALL }`。
**硬性安全网**（采纳 VidCoder `VidCoder/VidCoder/Services/ProcessingService.cs:3324-3328`）：`audioMode != NONE && audioMode != BY_INDEX` 时结果集**永不为空**。
**必须真正产出既有枚举**：`TranscodeChangeCode.EXTRA_AUDIO_TRACKS_REMOVED`（`TranscodeContracts.kt:111`，`requiresConfirmation = true`）与 `SUBTITLES_NOT_EMBEDDED` **当前无人产出**——字幕在容器/编码器不支持嵌入时降级为「不嵌入 + 产出 change」而非静默丢弃（采纳 VidCoder `:3499-3500` 的降级思路，但用 `TranscodeChange` 而非 burn-in，因为 Media3 Transformer 不做烧字幕）。
验证：改前测试应暴露「双音轨源只保留第一条且无 change」；改后断言 `changes` 必含 `EXTRA_AUDIO_TRACKS_REMOVED` 且 `requiresConfirmation == true`。

**T6 · HDR 不再无条件拒绝（P1，破坏性）**
现状缺陷：`Media3TranscodeEngine.kt:51-53` 遇到 `HDR_TO_SDR` 直接 `return Failed("HDR_TONE_MAPPING_UNAVAILABLE")`；`AndroidMediaCapabilityProbe.kt:114 supportsHdr = false` 硬编码。
改造：按 Media3 1.10.1 的 `Composition.HdrMode` 常量显式选择：`HDR_MODE_KEEP_HDR`（同格式保留）/ `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC` / `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`；能力探测改为读取 `MediaFormat.KEY_COLOR_TRANSFER` / `KEY_COLOR_STANDARD` / `KEY_HDR_STATIC_INFO` 判断源与编码器两侧的 HDR 支持。
HDR10 静态元数据注入的字段常量可直接取自 shutter `functions/settings/AdvancedFeatures.java:1060`（master-display / max-cll）；「只搬码流不解码」的元数据抽取原则取自 staxrip `Forms/MainForm.vb:3530/:3605`（Android 用 `MediaExtractor` 读 SEI，无需管道）。
**注意**：Dolby Vision RPU 处理（staxrip `:3574-3669` + `x265Enc.vb:149-167` 的派生链）在 Android 上**没有对应能力**，`HdrFormat.DOLBY_VISION` 应保持「明确拒绝并解释」而非尝试。
**另一条必须写进设计的不变量**：**任何裁切/缩放都会让已存在的动态元数据失配** —— staxrip 为此专门在改 RPU（`Encoding/x265Enc.vb:149-167 ReadProfileFromRpu → WriteEditorConfigFile → WriteModifiedRpu → TrimRpu`）后才允许编码，且 `General/DolbyVisionMetadataFile.vb:82/:88/:94-109` 用 `HasPathChanged`/`HasLevel5Changed`/`HasToBeTrimmed` 做**增量失效**。本项目**不做动态元数据**（HDR10+ / DV 均未立项），但规则成立：**一旦「HDR 保留」与「分辨率缩放」同时出现在一个 `TranscodePlan` 里，必须在 UI 上说明动态元数据不可保留**（本项目目前连静态 `master-display`/`max-cll` 都未注入，见 T6 上文）。
验证：用真实 HDR10 样本（非 HDR）跑改前/改后；改后断言 `HDR_MODE_KEEP_HDR` 路径能产出文件且 verifier 通过，`supportsHdr` 由实测决定而非硬编码 false。

**T7 · 能力探测强制超时 + 失败降级不报错（P1，非破坏性）**
采纳 shutter `library/LibraryUtils.java:1083-1092` 的原则（注释原文「Never wait forever: a hung GPU driver (e.g. Vulkan) must be treated as unavailable」）：任何试编探测必须有超时，超时即视为不可用。
采纳 FastFlix `fastflix/application.py:189-199 _encoder_available(requires)` + `fastflix/models/config.py:511-540 check_hw_encoders`（每个 try/except 失败置空列表）：探测抛异常 → 该编码器不进列表 + **诊断码写入 `DeviceMediaCapabilities.diagnosticCodes`**（该字段已存在 `TranscodeContracts.kt:67`）。
**禁止**用 `mimeType.contains("hevc")` 之类字符串猜能力（HandBrake `win/CS/HandBrake.Interop/Interop/Interfaces/Model/Encoders/HBVideoEncoder.cs:166-179` 是反面教材）。
验证：单测注入会抛异常 / 会挂起的 fake prober，断言不崩溃、返回空能力 + 诊断码。

**T8 · 产物过小/不存在即判失败（P1，非破坏性）**
采纳 staxrip `Forms/MainForm.vb:3550-3552`、`:3640`（产物 <100 字节即删除判失败）与 VidCoder `ProcessingService.cs:2415`（「HandBrake reported no error but the expected output file at ... was not found.」）、`:2423`（「...but the output file was empty.」）。
本项目 `MediaExtractorOutputVerifier` 已有该能力，**需确认在「引擎报成功」与「verifier 报成功」之间没有短路**——采纳既有裁决「成功状态只能由独立 verifier 产生」（`docs/09` 任务 11.7）。
验证：注入「引擎返回 Success 但输出为 0 字节」的 fake，断言最终状态为失败。

**T9 · 失败码结构化分段（P2，破坏性）**
采纳 VidCoder `VCEncodeResultCode`（`VidCoderCommon/Model/VCEncodeResult.cs`）的分段：外部段与自有段分离，自有段从 100 起。
本项目现有 `Rejected("VIDEO_ENCODER_UNAVAILABLE")` 等**字符串码**应改为结构化 `TranscodeFailureCode`（枚举 + 稳定 `tag()`，采纳 dublette `skip.rs:64-72` 的做法并配单测锁死 tag）。

**T10 / T11 / T12 · UI（P1–P2，非破坏性）**
- **T12（P1）**：质量模式与体积模式 UI 互斥（staxrip `Forms/MainForm.vb:5088 tbTargetSize.Visible = Not p.VideoEncoder.QualityMode`），实时回显预计大小（`MainForm.vb:4728`）。
- **T10（P2）**：本项目无 FFmpeg，把 FastFlix 的「命令预览」（`fastflix/widgets/panels/command_panel.py:17 _command_to_display_string`）改造为**结构化参数预览**：编码器名 / 实际分辨率 / 速率控制模式与档位 / 音轨保留列表（含被丢弃项及原因）/ 字幕处理方式 / 预计输出体积与所需空间。
- **T11（P2）**：声明式设置页 + 字段绑定（staxrip `Source/UI/Controls/SimpleUI.vb:265-461` + `Forms/MainForm_ShowOptions.vb:26-95`）：Compose 版 = `data class SettingSpec(页路径, 控件类型, 值域, 绑定字段, 帮助文案)` + 一个 `when` 渲染器，路径里的 `|` 生成树形层级；`Config = {min, max, step}` 直接变成 Compose 校验规则。**注意本项目只有 2 个功能入口（快速封装 / 重新编码），不要引入 staxrip 那种 100+ 工具的编排复杂度**（见 X1 的取舍）。

### 3.2 去重域（D）

**D1 · `quickSignature` 加中间 1 MiB（P0，破坏性）**
现状：本项目只用头尾 64 KB。采纳 VideoCull `electron/duplicates.js:181-193` 的 `quickSignature = sha256(size + 首 1 MiB + 中间 1 MiB)`（≤2 MiB 直接整文件），**几乎不增成本却显著降低「同长度不同内容」误判**。
验证：构造「头尾相同、中段不同、大小相同」的两个文件，改前误判为候选（进入完整哈希）、改后在第一层即排除。

**D2 · 指纹缓存键含算法/采样/抽取器版本（P0，破坏性）**
采纳 VideoCull `duplicate-utils.js:121-135` 的 `getDuplicateFingerprintKey = ['gray32-v2', sampleCount, bounds.start, bounds.end, maxDuration].join('|')`，其注释记录了真实事故：bundled FFmpeg 升级后**相同文件的新旧 pHash 混用导致相似度掉到 62–94%**。
本项目对应：Room 侧缓存失效键为**三元组 `(sizeBytes, modifiedEpochMillis, hashAlgorithmVersion)`**（既有裁决，见 `docs/architecture/phase-12-duplicate-algorithm-card.md` 与 `docs/09` 任务 12.2），并**追加「抽取器版本」**（一旦换 `MediaMetadataRetriever` 实现或系统升级，旧指纹必须整体失效）。
同时采纳 0x90d `VDF.Core\FileEntry.cs:83-90` + `ScanEngine.cs:756-780` 的更强规则：**size 变 → 全部重算；size 不变但时间戳变 → 必须由内容指纹证明字节未变才复用缓存**（注释原文「keep the cached analysis when the content fingerprint PROVES the bytes unchanged」）。
验证：改前测试「同哈希不同算法版本」应被误用；改后断言版本升级后旧指纹失效并重算。

**D3 · 「这不是重复」持久化忽略对（P1，破坏性）**
ddoss `ignorelist.py`（路径**字典序排序**使 `contains(B,A) == contains(A,B)`，resolved 路径，**原子写 tempfile + os.replace**，损坏则从空开始）与 VideoCull `ignoredDuplicatePairs`（正则 `/^[0-9a-f]{16}\|[0-9a-f]{16}$/i`）是同一设计。
本项目已有裁决：新增 `duplicate_ignores(contentHash, sizeBytes, memberCount, ignoredAtEpochMillis)`（修既有 G26：旧 `ignore(groupId)=deleteGroup(groupId)` 会被下次扫描重建）。
验证：忽略后重跑扫描，断言该对不再出现；断言「忽略后组内其他成员变化」时行为符合 `memberCount` 语义。

**D4 · 两阶段预览/执行 + 身份校验（P1，破坏性）**
采纳 ddoss 的「`--dry-run` 与执行**共用同一条代码路径**，只有 `dry_run` 布尔不同」+ JSON `dry_run_summary`（`files_to_delete[{path,size,size_human}]` + `total_files` + `total_bytes` + `total_bytes_human` + 可选 `strategy`），**并且采纳「JSON 是双向的」**（`reporter.py:290-350 load_replay_json` 能读回重建）——让「整理预览」导出可回读的 JSON，实现先预览、后执行、可复核。
**明确不采纳** dublette 的「dry-run 是布尔分支且执行时重跑全流程」（`dublette/src/lib.rs:74-90`）：中间必须有**一次身份校验**——执行前逐条复核 `size + mtime + quickFingerprint`，不匹配就跳过并报告。
验证：构造「预览后文件被外部修改」的用例，断言执行时跳过并报告而非按旧计划删除。

**D5 · 稳定组 id + 组信息反规范化到 media（P1，破坏性）**
采纳 VideoCull `duplicates.js:1001-1003` 的 `id = 'dup-' + sha1(排序后成员 id).slice(0,16)`（跨扫描稳定，UI 选择/滚动位置可挂靠）与 `:1167-1197 deriveVideos` 的反规范化（把组信息写到每个 video 上，同一份数据同时服务「按组看」与「按文件看」）。
**不采纳** ddoss 的顺序整数 `group_id`（`grouper.py:93-97`，重跑即变）。
**注意**：本项目已裁决「`MediaItem` 是内容等价类，重复组是派生查询而不是实体」（`ADR-DEDUP-002`）——因此**不新建 group 实体表**，只把「稳定组标识」作为**派生查询的稳定键**（对成员 id 排序后哈希）用于 UI 挂靠，组信息反规范化则落在 `media_locations` 的冗余列上。这与 VideoCull 的物理表结构不同，是**基于本项目的调整**。

**D6 / D7 · 相似匹配相关（P2，**当前关闭，不实施**）**
`ADR-DEDUP-003` 已冻结六项重开前置条件。若未来重开：
- **D7**：必须抄 VideoCull 三层防护 —— `electron/duplicates.js:1072-1084` 代表锚定合并（`directSimilarity(representativeId, other) >= threshold` 才允许并入）+ `:886-918 pruneWeakDaisyChainMembers`（`requiredConnections = Math.ceil((active.length - 1) / 2)`）+ `:946-962 splitDaisyChainIds`（剪掉的成员递归再分组、丢弃单例）。**不要**抄 ddoss `grouper.py:55-110` 的纯并查集或 dublette `scan.rs:63-110` 的纯 DFS 泛洪（后者 `scan.rs:269-285` 的单测 `grouping_transitive()` 甚至**断言**传递闭包成立）。
- **D6**：组分数取**最弱一对向下取整到 0.1**（`duplicates.js:1142-1146`），保证「UI 显示的百分比 = 扫描阈值语义」；`combineSampleScores`（`:240-245`）的约束注释必须写进代码。
- 特征层次序（采纳 §1.3 结论）：dHash 12 帧 + **命中率**判据（**不是**逐帧算术平均）→ pHash 仅对高相似候选 → 首尾固定窗口 all-pairs 只作候选生成器。
- 抽帧参数（采纳 VideoCull `processor.js:90-112 calculateTimestamps`）：跳片头 3 s / 片尾 3%、N 帧各取**区间中心**、`duration < 10s` 只 1 帧；**帧数重新定档**（Android 每帧有真实成本，建议先做 3 帧并实测，而不是默认 6）；重试偏移序列 `[t, ±0.25, ±0.75]`；**「单帧超时即放弃整个视频」必须抄**（注释「a decoder that hung once on a file usually hangs again at the next offset」）。

**D8 · 扫描分层下沉到 SQL，不整库入内存（P0，破坏性）**
现状缺陷（既有 G20）：`DefaultDuplicateScanner.scan()` 用 `loadAllMedia()` 把整库载入内存。
改造：L0–L4 分层（`GROUP BY sizeBytes HAVING COUNT(*)>=2` → 快速指纹 → 流式 SHA-256 → `GROUP BY contentHash, sizeBytes` → 提交前重算）**必须由 SQL 完成**（既有裁决，见 `docs/architecture/phase-12-duplicate-algorithm-card.md` 与 `docs/09` 任务 12.2 的异常路径「扫描过程不得把整库载入内存」）。
参考 ddoss `cache_db.py:500 _canonical_pair(path_a,path_b,mtime_a,mtime_b)` 做顺序无关规范化、每线程一个连接（`threading.local()`）、`WAL + synchronous=NORMAL` + `busy_timeout=5000`（Room 已默认 WAL，需确认 `busy_timeout`）。
验证：10k 条目的合成库，断言峰值内存不随库规模线性增长（用 `Runtime.totalMemory()` 采样或直接断言不出现 `loadAllMedia`）。

**D9 · 归并事务（P0，破坏性）**
既有裁决：删除计划约束从「覆盖整组」**放宽为子集关系**（`keep` 非空、`trash` 非空、`trash ⊆ group \ keep`）；**归并与删除在一个 Room 事务内**（既有 G12）；必须重指向的七类引用（标签、收藏、播放进度、播放列表成员、集合成员、切片项目、处理任务输入）；**归并事务失败时必须整体回滚**，不得留下「引用已改指向但位置未回收」的中间态。
参考：VideoCull 的做法是成功删除后清 cache 行 + 缩略图文件，失败可重试（`cache.js`）；ddoss 的 `sidecars` 随主文件走 + `action_log` 记 `sidecar_of` 提示「删除一个视频时它的关联状态如何级联」必须显式设计。

### 3.3 回收站域（R）

**R1 · 后端按所有权分派（P0，破坏性）**
裁决见 `ADR-RECYCLE-003`：**R1 系统回收站为主、R2 应用专属副本为受控降级、R3 同卷 `.Trash` rename 删除、R4 阻止并解释兜底**。
- R1 条目：`MediaStore.createDeleteRequest()` 系统授权（**注意：应用私有目录文件不能套用该系统确认**）；
- R2 条目：应用自己的确认 + `File.delete()`，**不得虚称已调用系统媒体删除授权**（`ADR-RECYCLE-004`）；
- 两类期限语义必须原样写进 UI：R1「由系统管理，通常约 30 天，应用不能延长或缩短」；R2「应用保留 %1$d 天」+ 卸载即丢的披露。**「剩余期限只有数据来源能可靠提供时才展示」**。
- 30 天拆两个指标：**恢复资格严格**（`now >= expiresAt` 立即禁止恢复）+ **物理删除尽快**（到期入清理队列、后台重试、应用每次启动/进前台再检查），**不承诺物理删除精确到秒**。
现状缺陷：当前实现是 `.Trash` 隐藏目录 rename 且**只支持 `file://`**（即 R3），与裁决不符。

**R2 · 决策与文件操作彻底分离 + 全局删除集合（P1，非破坏性）**
采纳 VideoCull（`docs/features/delete-and-safety.mdx:12` 原文「Keep, Delete, Skip, and Reset are reversible status changes. **Files only leave disk when you run the final delete action and confirm the batch.**」；`:40` 原文「The sidebar delete count is global to the loaded session, not the current filter.」）。
本项目对应：删除状态只改 Room 标记，确认框必须显式警告**筛选不影响批次**。

**R3 · 确认框写明降级路径与总条数/总大小（P1，非破坏性）**
采纳 VideoCull `electron/deletion.ts`（**24 行**：先 `moveToTrash`，收集 `failedPaths`，非空时再问是否永久删，并按路径合并结果保证每个路径一条结果）与 `utils.ts:12-26 formatDeleteConfirmation`（把降级路径写进确认框）。
确认文案形如：「将 N 个视频（1.2 GB）移入回收站？若回收站不可用，YingLi 会在永久删除前再次询问。」

**R4 · 副本完整性验证 + 失败不删唯一副本（P0，破坏性）**
资料裁决（`移入回收站的事务流程.png` 两个「否」分支都是**阻止并解释原因**，不是降级继续）：顺序 = 占用检查 → 权限与目标存储检查 → 创建持久化操作记录 → 复制或迁移到受控存储 → **目标文件完整性验证** → 持久化回收站记录与 30 天期限 → 必要时授权清理源文件。
**「不能把『复制成功』直接等同于『移入成功』」**；恢复需同时满足「视频可访问 + 媒体索引已更新 + 记录状态正确」，**失败保留回收站副本，不得先删唯一副本**。
**状态机 6 态**（`回收站状态与操作定义.png`）：正常媒体 / 移入处理中 / 回收站中 / 恢复处理中 / 永久删除处理中 / 已删除，**所有「处理中」态在取消或失败时退回上一个稳定态**。
验证：`回收站最小验证模块.md` 的 10 条 P0 项 + `回收站实现验收条件.md` 的 6 组勾选项。

**R5 · 删除请求限定已加载目录内路径（P1，非破坏性）**
采纳 VideoCull `duplicates.js:52-56`：删除请求只接受当前会话已加载目录内的视频，目录外直接拒绝。本项目对应「只能操作已通过 SAF/MediaStore 授权范围内的条目」（`docs/05`：授权范围变化时重新同步）。

**R6 · 批量操作合并为一步撤销 + 恢复界面位置（P2，非破坏性）**
采纳 VideoCull `store.ts:1195-1201`（批量合并成一条 `UndoEntry`）与 `:1259`（`undo()` 既恢复状态**也恢复 Review 位置**）；撤销栈在删除完成后清空。

**R7 · 事后对账状态（P2，非破坏性）**
采纳 ddoss `PairResolutionStatus.swift:4-11` 的三态 `.active` / `.resolved(HistoryAction)` / `.probablySolved(missing:[String])` 与对应 UI 横幅（「Kept X, trashed Y / Saved 12 MB / 3m ago」vs「This pair was likely resolved outside the app」）——**外部改动被显式识别而不是静默**。
参考 ddoss `actionlog.py` 追加式 JSONL 审计（每条 `timestamp/action/path/score/strategy/kept/bytes_freed`，写一行 flush 一次，注释「for crash safety」）与 `undoscript.py:155-206` 生成撤销脚本（`deleted` 打 `IRRECOVERABLE`）。

### 3.4 切片 / AB 导出域（C）

**C1 · 导出前显示「请求起止 vs 实际起止」（P0，非破坏性）**
**这是相对参考实现的直接优势**：lossless-cut 在这里是缺口——只有导出对话框一句警告（`src/renderer/src/components/ExportConfirm.tsx:245`「某段输出可能比预期长得多，因为你的文件在该起点附近没有关键帧」），UI 上**没有请求/实际对比**。
本项目 `PlatformClipEngine.fastCut`（`app/src/main/java/seeyuer/yingli/player/data/processing/clips/PlatformClipEngine.kt:67-139`）用 `seekTo(startMillis*1000, SEEK_TO_PREVIOUS_SYNC)` 取 `baseTimeMicros = extractor.sampleTime`，**已经拿到了实际起点**，且 `ClipEngineResult.Success(actualStartMillis, actualEndMillis)` 是现成回报通道 ⇒ **能在 UI 上精确显示「实际起点比请求起点早 N ms / N 帧」**。
验证：改前测试暴露「引擎回报了 actualStart 但 UI 不显示」；改后断言导出对话框含该差值。

**C2 · 至少一帧时长 + 拼接减一帧（P1，破坏性）**
采纳 lossless-cut `src/renderer/src/common/util.ts:143 getFrameDuration = (fps) => 1/(fps ?? 30)` 与 `cutDuration = Math.max(cutDuration, frameDuration)`（**保证至少一帧**）；smart cut 两段拼接时 `encodeCutToSafe = max(desiredCutFrom + frameDuration, losslessCutFrom - frameDuration)`（`useFfmpegOperations.ts:716`，**减一帧防拼接处重复帧**）。
本项目对应 `ClipSegment` 的不变量 `endMillis > startMillis`（`domain/clips/ClipContracts.kt`）——需补「至少一帧」的显式校验。

**C3 · 关键帧对齐显式化（P1，非破坏性）**
lossless-cut 把「关键帧对齐」做成**用户可见的三选一**（`src/renderer/src/ffmpeg.ts:112-129` 的 `'nearest' | 'before' | 'after'`）并**从不假装无损切割是精确的**；`findKeyframeAtExactTime`（`:107`）用 `|Δ| < 0.000001` 判「已在关键帧上」作为**「无需任何操作」的短路条件**；窗口两级退化 ±10s → ±60s（`:131-142`、`smartcut.ts:20-38`）。
本项目目前是 `SEEK_TO_PREVIOUS_SYNC` **静默吸附** ⇒ 应把该事实在 UI 上显式化，并提供「精确切割（重编码）」作为用户可选项（既有产品裁决 Q220/Q537 已覆盖语义，缺的是 UI 呈现）。

**C4 · 区间层 + 补集层双语义可视化（P1，非破坏性）**
采纳 lossless-cut `src/renderer/src/components/Timeline.tsx:421-449` 的 `inverseCutSegments`（被剪掉区域，半透明）与 `cutSegments`（保留段，高亮）**互为补集**——正是 AB 循环区间需要的双语义可视化。
配套（同文件）：拖拽需**先选中该段并按住修饰键**（`:289-345`，命中判定 `threshold = (0.01/2)*duration/zoom`），**普通点击/拖动 = 定位播放头**（防误改区间）；关键帧刻度只在「不挤」时画（`:167-171 areKeyframesTooClose = keyFramesInZoomWindow.length > zoom * 200`）。

**C5 · 每段独立显示 时长/毫秒/帧数/估算体积（P2，非破坏性）**
采纳 lossless-cut `src/renderer/src/components/SegmentList.tsx:274-283` 的三种表述并列（`Duration <timecode>` + `<ms> ms, <N> frames` + `~<prettyBytes>`）与底部 `Segments total`（`:497-498`，只统计选中段）。
估算体积：lossless-cut 用 `段时长/总时长 × 文件大小`（`useSegments.tsx:738-743`）；**本项目更优**——已有源文件码率，用 `码率 × 段时长` 更准；并采纳 ab-av1 的**保守预测**（`sample_encode.rs:437-448` 取 `min(按时长外推, 按文件百分比外推)`，注释 `:440-441`「file-percent 容易高估，取 min 抑制高估」）。

**C6 · AB 区间 → 导出切片入口（P0，破坏性）**
现状缺陷（既有 G9）：AB 循环与导出切片**零连接**——播放页没有「导出当前 A–B」动作；`feature/processing/ProcessingViewModel.kt:134 createProject(media)` **永远只造一个 `ClipSegment(id, 0, minOf(duration, DEFAULT_SEGMENT_MILLIS), "Clip 1")`**（即无法从任意区间播种、名字恒为 "Clip 1"）。
改造要点（既有 §10 结论）：**接缝是一行构造，不是一层抽象**——三个所需值全部在手边（`PlayerViewModel` 已有 `libraryRepository`，缺 `clipExportQueue` + `idGenerator`）。
三条约束：
- **C1 约束**：AB 是会话临时状态（`docs/18:170`「媒体切换清除 AB；AB 为当前会话临时状态，不写全局偏好」），`ClipProject` 是 Room 持久实体 ⇒ **必须在提交那一刻把 `(A,B)` 固化成 `ClipSegment` 快照**，AB 类型不得进入项目对象。
- **C2 约束**：AB 点经 `snapAbMillisToFrame` 帧吸附（`domain/playback/PlaybackSessionContracts.kt:185`），`fastCut` 走 `SEEK_TO_PREVIOUS_SYNC` ⇒ 实际起点必然 ≤ A，已由 Q220/Q537 裁决，`ClipEngineResult.Success(actualStartMillis, actualEndMillis)` 是现成回报通道。
- **C3 约束**：成功状态只能由独立验证器产生 ⇒ **必须经 `ClipExportQueue.enqueue`**，不能走「网关直接写文件」的路。
交互范式参照 `refer/REX-Player-master/app/src/main/kotlin/xyz/mpv/rex/ui/player/controls/components/sheets/ClipExportSheet.kt`：标题 + 区间徽标（`$startFormatted → $endFormatted` + `durationSec` 胶囊）+ **两张并列选项卡**（① Fast/Lossless Copy，图标 `Icons.Default.Bolt`，`isPrimary = true`；② Exact Frame/Transcode，图标 `Icons.Default.CenterFocusStrong`），**不预设默认**。
验证：改前断言「播放页无导出入口、`createProject` 起点恒为 0」；改后断言「从 A–B 导出产生的 `ClipSegment.startMillis == A`（帧吸附后）」。

**C7 · 幂等跳过 + 覆盖开关（P2，非破坏性）**
采纳 lossless-cut `src/renderer/src/hooks/useFfmpegOperations.ts:112-127, :623` 的 `shouldSkipExistingFile`（输出已存在则跳过 ⇒ **断点续做**），配「覆盖」开关；命名冲突策略既有 `ClipConflictStrategy { RENAME, SKIP }`（`domain/clips/ClipContracts.kt`）已覆盖 RENAME，需补 SKIP 的实际执行。

### 3.5 任务中心与工程基础设施（J）

**J1 · 任务队列持久化：暂停/恢复/断点续做/失败重试（P0，破坏性）**
**反面教材**：lossless-cut **没有任务队列**——`useLoading.ts` 的 `workingRef` 作全局互斥锁，所有入口首行 `if (workingRef.current) return;`，批量是**手动逐文件**（左侧文件列表 + `batchFileJump` 上下翻）；唯一真批处理循环 `convertFormatBatch()`（`useHtml5ify.tsx:174-216`）是 `for (const path of filePaths)` **串行、失败收进 `failedFiles` 不中断**，结束 toast 列失败文件。
**正确做法**：Room 持久化真队列（可暂停/恢复/断点续做/失败重试）+ 采纳那三条（串行、失败不中断、结束汇总失败列表）。
载入时状态复位：采纳 HandBrake `win/CS/HandBrakeWPF/Services/Queue/QueueService.cs:491 RestoreQueue`（`:521-524` 把 `InProgress`/`Paused` 一律复位）与 FastFlix `fastflix/widgets/queue_panel.py:326 queue_startup_check`（移除 `complete` 项、其余 `status.clear()`）。
并发用「按编码器家族槽位池 + 总池」：VidCoder `VidCoder/VidCoder/Model/HardwarePool.cs:36 CanAcquireSlot() => jobs.Count < SlotCount`、`:47` 超限抛 `InvalidOperationException`；`HardwareResourceService.cs:51-55` 构造 `QSV/NVEnc/VCE(3)/MF(1)/TotalPoolName`。本项目硬件编码器是 MediaCodec，对应「按编码器实例数限制并发」。
重试插到「正在编码」之后不抢占：VidCoder `ProcessingService.cs:1587 RetryJobIfNeeded`。

**J2 · 三态暂停机（P1，非破坏性）**
采纳 VideoCull `electron/processing-pause.js:11-19`：`running/pausing/paused` 三态，**只有 `pauseRequested && activeOperations===0 && 所有 worker 都 quiescent` 才算 paused**（主线程用 `checkpoint()` await，`:61-67`）。
**不采纳**其 `SharedArrayBuffer` + `Atomics.wait/notify` 真阻塞（`duplicate-worker.js:12-20`）——Android 主线程不能这样阻塞，改用协程。
另采纳 ddoss `pipeline.py:182-184/234-240` 的一条：**`cancel()` 必须同时设标志「并且」set event 以唤醒暂停中的等待者**（`PipelineController` 用单个 `threading.Event` 统一 pause/resume，CLEARED = 阻塞等待者）。

**J3 · 按资源串行 + 三级优先级（P1，非破坏性）**
采纳 VideoCull `electron/keyed-operation-queue.js`（67 行）：按 key 串行 + 优先级 `background(0) < foreground(1) < interactive(2)`，不同 key 并行——避免同一文件的缓存写竞争。
本项目对应：同一 `MediaLocationId` 的处理任务必须串行（去重扫描、压缩、切片导出、回收站移入共用一把 key 锁），这正是既有裁决 `MediaOperationGuard` 要解决的问题。

**J4 · 进度节流 + Elapsed 计时 + 取消唤醒（P1，非破坏性）**
- **节流**：VideoCull `duplicate-worker.js` 每 1024 对检查一次暂停（`(compared & 1023) === 0`），**进度每 ≥250000 对才上报一次**（避免 IPC 洪水）；先做一遍**廉价计数 pass** 得到总数供进度条用，再跑真实 pass（`:110-114/147-152`）。ddoss 的 `ProgressEmitter`（`progress.py:64-272`）按 stage 节流 100 ms（`_THROTTLE_INTERVAL = 0.1`），最后一个事件强制发出，速率用 EMA α=0.3 平滑。
- **Elapsed 计时**：lossless-cut `src/renderer/src/components/Working.tsx` 用 **100ms 刷新的 Elapsed 计时**（注释明说：某些操作长时间无进度输出，要让用户知道没死机，issue #2746）。
- **心跳**：0x90d `VDP.Core\ScanProgress.cs:25-93` 用不可变 `record ScanProgressSnapshot` + `Interlocked.CompareExchange` 节流 + `Heartbeat()` 无进展时重发快照防「看起来卡死」；ETA = `elapsed*remaining/(processed+1)` 负值夹 0；**驱动器进度按字节而非文件数**。
- **`threaded` 写盘**：ddoss `progress.py:76-79` 把 stderr 写放到守护线程，注释原文「a full stderr pipe buffer (GUI subprocess) can freeze the entire pipeline」。

**J5 · 失败原因稳定枚举 + 退出语义（P1，破坏性）**
采纳 dublette `src/skip.rs` 的 `SkipReason { DecodeFailed, TooShort, UnsupportedContainer, Unreadable }` + 稳定 snake_case `tag()` + 单测 `tags_are_stable_snake_case`（`:64-72`）——**机器可读的失败原因被当作 API 对待**。
采纳 compressO `media_process.rs:663-672` 的 `struct ProcessExitStatus { exit_code } + success()` 与并发时「任一失败即整体失败并传播首个非零码」（`:543-544/596-609`）。
采纳 staxrip `General/Proc.vb:448 Function ProcessData(value As String) As (Data As String, Skip As Boolean)`（行级输出过滤成元组）与 `:351-352` 把平台错误码翻译成人话的思路（Android 对应「把 `MediaCodec` 错误码映射为可读原因」）。

**J6 · 数据库原子保存 + 损坏隔离（P2，非破坏性）**
采纳 0x90d `VDF.Core\Utils\DatabaseUtils.cs:44-56`（写临时文件再替换 = 原子保存）、`:59-123`（坏 temp → 删除重试；坏正式文件 → 改名 `_DAMAGED.db` 隔离，**绝不静默换成空库**）、`:125-140`（一次性自愈标记 `ScannedFiles.fpheal1` 侧车文件，每个库自动重试恰好一次）。
**不采纳**其「单文件二进制数据库 + 全库 `HashSet` 常驻内存」（`:41-43`），只保留**模式**。

**J7 · 取消后必须由回调删除半成品（P1，非破坏性）**
采纳 compressO 的做法：`core/media_process.rs:11 CancelCallback = Arc<dyn Fn() + Send + Sync>` + `:17-98 MediaProcessExecutorBuilder { commands, cancel_ids, cancel_callback, stdout_callback, stderr_callback, piped }`，**取消回调注册在 spawn 时**，进程被杀后由回调删除输出：`core/ffmpeg.rs:796-800 std::fs::remove_file(&output_file_clone).ok()`。窗口销毁时全杀（`media_process.rs:159-168`）。
采纳 shutter-encoder 的**两模式取消**（`ui/main/Shutter.java:2940-2962`）：正常情况向 stdin 写 `q` 优雅退出（`FFMPEG.writer.write('q'); flush(); close();`），分段/多进程场景才 `FFMPEG.process.destroyForcibly()`；临时目录在 `finally` 删（`functions/Rewrap.java:338-346`）。
本项目对应：既有裁决是「先持久化 `CANCEL_REQUESTED` 再调底层取消，只有底层确认停止后才标 `CANCELLED`」（`Organizing-Page-Function-Design.md:158`）。**需补的是「取消确认后清理应用私有临时目录里的半成品」这一步**——`TranscodeProcessingExecutor` / `ClipProcessingExecutor` 目前只在 `invokeOnCancellation` 里删输出（`Media3TranscodeEngine.kt` 已做），但**队列级取消（暂停后取消整批、进程重启后清理孤儿产物）没有统一入口**。落地：`AndroidProcessingArtifactStore` 增加 `sweepOrphans()`，在「应用启动」与「任务终态」两个时点调用；参照 compressO `src-tauri/src/sys/fs.rs:99-121 delete_stale_files(path, duration_in_millis)` 的「按年龄清理」而非「按名单清理」。

### 3.6 跨域（X）

**X1 · 容器 × 编码器显式声明表（P0，破坏性）**
现状缺陷（既有 G11）：`PlatformClipEngine.kt:207-213` 的 `FAST_MP4_MIME_TYPES = setOf("video/avc", "video/hevc", "video/mp4v-es", "audio/mp4a-latm", "audio/mpeg")` **不含** `video/av01`、`video/x-vnd.on2.vp9`、`audio/opus`、`audio/flac`、`audio/vorbis`、`audio/ac3` ⇒ WebM(VP9/Opus) 源在快速模式必然 `FAST_CONTAINER_UNSUPPORTED`。
改造（既有裁决）：反查 `Muxer.Factory.getSupportedSampleMimeTypes()`（即 `Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES` / `SUPPORTED_AUDIO_SAMPLE_MIME_TYPES`）或 `MediaCodecInfo.CodecCapabilities.getSupportedSampleMimeTypes()`，**与 Phase 11 共用同一份能力来源**。
表单形态采纳 compressO `src/routes/(root)/ui/output-settings/video-settings/video/VideoCodec.tsx:23-54 VIDEO_CODECS: {value,name,description,compatible_containers: VideoExtension[]}[]`，译成 Kotlin `enum class VideoCodec(val containers: Set<Container>)`；并照抄 `:85-114`「**换容器时自动清空不兼容选择**」。
**对照硬事实**：AOSP `MediaMuxer` 的容器×codec 矩阵（`MediaMuxer.java` 类注释）——**VP8/VP9 无法封装进 MP4；Opus 无法封装进 MP4**（WebM 支持 VP9/VP8/Vorbis/Opus，OGG 支持 Opus）。⇒ WebM(VP9/Opus) → MP4 在不重编码前提下**平台做不到**，白名单反查必须同时受此约束。
**同时采纳 staxrip 的「重封装 = 空编码器」抽象**（`Encoding/VideoEncoder.vb:777-857 NullEncoder`）：把 copy 实现成编码器的退化实现，上游流水线不分叉；并用「工程模板」而非分支表达快速封装 vs 重新编码（`General/General.vb:253-269` 生成 `Re-mux.srip` / `Automatic Workflow.srip` / `Manual Workflow.srip`）。
**明确不采纳** shutter-encoder 的「功能名 = 全局可变字符串 + 到处 `switch`」（100+ 处）——本项目只有 2 个功能，用 `sealed class` 即可。

**X2 · 独立验证器统一为所有输出路径的唯一成功来源（P0，破坏性）**
既有裁决「成功状态只能由独立 verifier 产生」（`docs/09` 任务 11.7；`docs/architecture/phase-10-clips-contract.md`「所有输出都必须再次 probe」）。
需要覆盖的三条输出路径：压缩/转码（`MediaExtractorOutputVerifier`，已有）、切片快速/精确（`ClipProcessing.kt`，需确认）、回收站副本（需新增）。
采纳 VidCoder 的两条补充判定（`ProcessingService.cs:2415/2423`）：**产物不存在** 与 **产物为空** 都必须判失败（不能只依赖引擎的返回码）。
采纳 staxrip `Forms/MainForm.vb:3550-3552` 的「产物 <100 字节即判失败」阈值思路。

**X3 · 结构化参数/任务摘要（可回读）（P2，非破坏性）**
采纳 ddoss `reporter.py:412-461 write_json`（每条含 `file_a/file_b/score/breakdown/detail([raw,weight])/...` + 外层 envelope 含完整有效配置 `args_dict` + `dry_run_summary`）与 `:290-350 load_replay_json`（能读回重建成 `ScoredPair`，**不重扫而重新执行/复核**）。
本项目对应：处理任务的 `outputPolicy` 已有 Base64 编码的预设快照（`data/processing/transcode/TranscodeProcessing.kt` 的 `POLICY_PREFIX = "TRANSCODE"`、`SEPARATOR = "|"`），**需补「可回读校验」**（读回后校验预设仍存在、参数仍合法，否则明确报错而不是按默认值跑）。

**X4 · remux 前置校验：把「产出损坏文件」变成「拒绝并给理由」（P0，破坏性）**
这是 §2.10 交叉验证给出的**最有价值的一条**。FFmpeg 的 `-c copy` 有五条会**产出损坏文件或直接失败**的充分条件（全部在 `libavformat/mux.c`）：
| # | 条件 | 源码依据 | 本项目对应 |
| --- | --- | --- | --- |
| 1 | 某流无时间戳 | `mux.c:793-798` `"Timestamps are unset in a packet for stream %d"` | `MediaExtractor.getSampleTime()` 返回负值 / `readSampleData` 为 0 |
| 2 | DTS 回退且容器严格 | `mux.c:800-810`；**MP4/MOV 未设 `AVFMT_TS_NONSTRICT`，MKV 设了** | `MediaMuxer.writeSampleData` 要求同轨时间戳单调 ⇒ MP4 会抛异常 |
| 3 | PTS < DTS | `mux.c:812-816` | 同上 |
| 4 | 目标容器无此 codec 的 tag | `movenc.c:8604-8611` `"Could not find tag for codec %s in stream #%d, codec not currently supported in container"` | `MediaMuxer.addTrack` 会抛 `IllegalArgumentException` |
| 5 | 负/缺时长被静默归零 | `mux.c:605-611`（**只告警，不失败**） | 时长索引失准，UI 显示错误长度 |

**改造**：在 `PlatformClipEngine.fastCut()` 真正 `writeSampleData` **之前**加一个 `validateRemuxability(source, segment): RemuxCheck` 纯函数，逐轨检查上述 1/2/3/4，返回 `Allowed` 或 `Rejected(code)`；**拒绝码必须结构化**（`NO_TIMESTAMP` / `NON_MONOTONIC_DTS` / `PTS_BEFORE_DTS` / `CONTAINER_CODEC_UNSUPPORTED`），复用 J5 的枚举机制，**不新增字符串**。第 5 条单独处理：时长异常时**不拒绝**，而是在结果里标注「时长不可靠」，由 UI 决定是否提示。
**理由**：现状是「先跑完 remux，再由 verifier 发现坏了」——浪费一次完整拷贝（大文件可能几分钟）且错误信息是平台异常文本。前置校验把成本降到 O(轨道数)。
**同时采纳**（shutter-encoder / §2.10 一致）：**HEVC 进 MP4 应自动打 `hvc1` 等价处理**（`functions/Rewrap.java:126-129`）。⚠ **注意平台差异**：`MediaMuxer` 的 sample entry fourcc 由平台写出、调用方不可设，`-tag:v hvc1` **在 Android 没有直接对应物**；本项目能做的是「探测源 HEVC 的参数集是否在带内，若在带内则**明确拒绝快速模式**并建议精确模式」，而不是假装能改 tag。

### 3.7 分阶段实施顺序

**约束**：阶段顺序由「依赖关系」决定，不由「收益大小」决定。**阶段 0 必须先于阶段 1**，因为它决定阶段 1 的代码怎么写。

| 阶段 | 内容 | 可独立验证的里程碑 | 依赖 |
| --- | --- | --- | --- |
| **0** | **真机能力实测**（G7）：在 Xiaomi M2012K11AC 上实测 `MediaCodecList` 报告的编码器、`VideoEncoderSettings.BitrateMode` 可用取值、HDR 编码器支持、试编超时行为 | 产出一份真机能力快照（JSON），断言 `supportsHdr` 由实测决定 | 需先解决 MIUI `INSTALL_FAILED_USER_RESTRICTED` |
| **1** | **T1 + T4 + X1 + X4**（码率接线 + `&&`→`\|\|` + 白名单反查 + remux 前置校验）。四者在同一批改动内，因为它们都改「预设 / 轨道 → 编码器或 muxer 参数」这一段，且 X1 与 X4 改的是同一个文件（`PlatformClipEngine.kt`） | 同一源视频跑三档预设，**输出体积差异 > 20%**；横屏源+竖屏上限被正确拒绝；四类 remux 拒绝条件（无时间戳 / DTS 回退 / PTS<DTS / 容器无 tag）**各有一条单测**，且 WebM(VP9/Opus) 源在快速模式得到**结构化拒绝码**而不是平台异常文本 | 阶段 0 的 `BitrateMode` 取值 |
| **2** | **T2 + T5 + D8**（`RateControl` 三态 + 轨道策略 + 扫描分层下沉 SQL）。三者都是「破坏性域模型收敛」，合并为一次 schema/契约重构 | 契约单测全绿；`EXTRA_AUDIO_TRACKS_REMOVED` 真正产出；10k 库峰值内存不线性增长 | 阶段 1 |
| **3** | **T3 + T6 + T8**（目标体积 + HDR 模式 + 产物校验） | 目标 100 MB 的视频输出落在 ±10% 内；HDR10 样本走 `HDR_MODE_KEEP_HDR` 成功；0 字节产物被判失败 | 阶段 2 |
| **4** | **R1 + R4 + X2**（回收站后端分派 + 事务 + 统一验证器）。回收站是数据安全路径，**必须与统一验证器一起落** | `回收站最小验证模块.md` 10 条 P0 全过；`回收站实现验收条件.md` 6 组全勾 | 阶段 3 |
| **5** | **J1 + J2 + J3 + J5 + J7**（持久队列 + 暂停 + 资源串行 + 失败枚举 + 半成品清理）。任务中心是四个功能的共用出口，**必须先于功能 UI** | 杀进程后重启，任务状态正确复位；同一文件的两个任务串行；暂停后 CPU 归零；取消后应用私有临时目录**无残留产物**（含进程被杀后重启的孤儿清理） | 阶段 4 |
| **6** | **C6 + C1 + C4**（AB 导出入口 + 实际起止显示 + 双语义时间轴） | 从 A–B 导出的 `ClipSegment.startMillis == A`；UI 显示「实际起点早 N ms」 | 阶段 5 |
| **7** | **D1–D5 + D9**（去重工程化：中间块 + 缓存键 + 忽略对 + 两阶段 + 稳定组 id + 归并事务） | 「头尾同中段异」在第一层排除；预览后外部修改被跳过；归并失败整体回滚 | 阶段 5 |
| **8** | **T9–T12 + R2/R3/R5/R6/R7 + C2/C3/C5/C7 + D6/D7 + J4/J6 + X3**（体验与工程加固；D6/D7 仅在相似匹配重开时） | 各项单测 + UI 走查 | 阶段 6/7 |

**与 `Organizing-Page-Function-Design.md` §14 的关系**：该文档的阶段划分以「功能域」为主线（压缩/转码 → 去重 → 回收站），本表以「依赖」为主线，两者的**首个可交付里程碑一致**（都是「码率接线 + 真机实测」）。实施时以本表的**依赖顺序**为准，功能域的完整性按该文档 §14 的验收条件收口。

### 3.8 风险与工作量预估

**风险（按严重度降序）**

| 风险 | 后果 | 缓解 |
| --- | --- | --- |
| **真机不可用**（MIUI `INSTALL_FAILED_USER_RESTRICTED`） | 阶段 0 无法完成 ⇒ 阶段 1 的 `BitrateMode` 只能猜 | 先解决安装授权（关闭 MIUI 优化 / 用 `adb install -r -t` / 换测试机）；实在不行则阶段 1 先按「`BITRATE_MODE_VBR` + `KEY_BIT_RATE`」保守实现，并把真机验证列为阶段 1 的**未闭合项** |
| **破坏性重构波及面大** | 阶段 2/5 同时改域模型与 schema，回归面广 | 每个阶段配「改前测试」先行（AGENTS.md 要求）；阶段 2 与阶段 5 之间留一个**只跑测试不改代码**的验证点 |
| **`Muxer.Factory.getSupportedSampleMimeTypes()` 的运行时结果与 AOSP 矩阵不一致** | X1 的白名单反查可能给出平台实际做不到的组合 | 双重约束：反查结果 **∩** AOSP 矩阵（VP9/Opus 不进 MP4）；对每个组合做一次真实 remux 探测并缓存 |
| **HDR 路径实测与预期不符** | T6 可能最终仍需「明确拒绝」 | 阶段 0 就把 HDR 编码器支持实测掉；若实测不支持，T6 降级为「在 UI 明确解释为什么不支持」而不是失败 |
| **回收站物理删除时机不可控** | 用户以为 30 天后一定删掉 | 已裁决：**不承诺物理删除精确到秒**，UI 文案必须原样披露；到期入清理队列 + 后台重试 + 每次启动/进前台再检查 |
| **参考实现的许可传染** | **唯一不兼容的是 `lossless-cut`（GPL-2.0-only，全仓无 "any later version"）** —— 其代码不得并入，只能借鉴设计思想 | 逐仓库许可已实测（见 §0.1 许可核对表）：本项目为 **AGPL-3.0**，故 MIT 组（ddoss / MediaDedupe / hclivess / videohash / ab-av1 / FastFlix / filmcompress / staxrip）、AGPL 组（dublette / VideoCull / videoduplicatefinder / **compressO**）、GPL-3.0-or-later（shutter-encoder）、GPL-2.0-or-later（HandBrake / VidCoder，可经 GPLv3 升级路径）**均无额外 copyleft 障碍**。**但无论许可是否兼容，本文一律只移植算法/数据模型/流程，不复制实现代码。** |
| **`lossless-cut` 的 GPL-2.0-only 被误记为「GPL-2.0 可用」** | 若照抄其 `src/renderer/src/hooks/useFfmpegOperations.ts` 的命令构造代码，会把不可再许可的代码带进 AGPL 项目 | §0.1 已把许可实测结论写死；引用 `lossless-cut` 时**只写设计结论、不写代码片段** |
| **remux 产出损坏文件却报成功** | 用户拿到打不开的文件（无时间戳 / DTS 回退 / PTS<DTS / 容器无 tag），且要等一次完整拷贝才发现 | **X4 前置校验**：在 `writeSampleData` 之前逐轨检查四类条件，拒绝时给出结构化拒绝码；§2.10 的 FFmpeg `mux.c:793-816` 是这些条件的权威依据 |
| **性能承诺过度** | 首次扫描/编码的耗时被低估 | 采纳 ab-av1 的「保守预测」与「**不预先承诺首次扫描几分钟完成**」（资料原文）；所有性能数字必须实测后才写进文档 |

**工作量预估**（人日，含改前/改后测试；**不含真机环境搭建**）

| 阶段 | 改造点 | 预估 | 说明 |
| --- | --- | --- | --- |
| 0 | 真机实测 | 1–2 | 主要成本在解决安装授权 |
| 1 | T1 + T4 + X1 + X4 | 6–8 | T1 是**最小改动最大收益**；X1 需要反查 + 探测缓存；X4 需要四类拒绝条件的构造测试源 |
| 2 | T2 + T5 + D8 | 8–12 | 三处破坏性域模型收敛，测试改写量大 |
| 3 | T3 + T6 + T8 | 6–9 | T6 的实测不确定性最高 |
| 4 | R1 + R4 + X2 | 10–14 | 回收站事务 + 系统授权 + 统一验证器 |
| 5 | J1 + J2 + J3 + J5 + J7 | 9–13 | 持久队列 + 暂停/取消 + 资源锁 + 孤儿产物清理 |
| 6 | C6 + C1 + C4 | 5–7 | C6 接缝小，C4 时间轴交互是主要成本 |
| 7 | D1–D5 + D9 | 8–12 | 归并事务与引用重指向是难点 |
| 8 | 体验与工程加固 | 10–15 | 条目多但单个小 |
| — | **合计** | **63–94** | 不含未列出的 UI 走查与文档回填 |

**最大的一条工程判断**：**T1（码率接线）、X1（白名单反查）与 X4（remux 前置校验）是「投入产出比最高」的三项**——T1 是 1 处构造调用（`Media3TranscodeEngine.kt:79-84`），X1 是 1 张表的来源替换（`PlatformClipEngine.kt:207-213`），X4 是 remux 循环前的一次 O(轨道数) 检查，三者合计不到 3 人日，却直接决定「三档预设是否有意义」「WebM 源能否快速导出」「导出的文件能不能打开」这三个**功能成立性问题**。相比之下，D6/D7（相似匹配）虽然技术含量最高，但已被 `ADR-DEDUP-003` 明确冻结，**不应占用任何近期工作量**。

### 3.9 明确不采纳清单（汇总）

**因本项目硬约束而不采纳**：FFmpeg 命令行/进程/sidecar（已裁决 No-Go）；ONNX/DINOv2/CLIP 等深度学习；音频指纹（Chromaprint 等）；DCT pHash / SSIM / tfidf（除非相似匹配重开且经标注集标定）；`ProcessPoolExecutor` + pickling；`send2trash`；`SharedArrayBuffer` + `Atomics.wait` 阻塞式暂停；`video://` / `thumb://` 自定义协议；Win32 `SuspendThread`；注册表/`HintDirFunc` 式工具探测；Rust/向量数据库。

**因参考实现本身是反面教材而不采纳**：
- compressO 的 **「Lossless Compression」名不副实**（`StartCompression.tsx:106-111` + `ffmpeg.rs:275-279` → 实际固定 `-crf 18`，并未做流复制）——**命名与实现必须一致**；
- compressO 的 **quality 0-100 线性映射到 CRF 24-36**（`ffmpeg.rs:268-270`）——CRF 是感知量纲，线性映射两端失真；
- shutter-encoder 的**功能名 = 全局可变字符串 + 到处 `switch`**（`VideoEncoders.java:1848` 等 100+ 处）与**用 `setVisible` 手动编排面板**；
- shutter-encoder 的 **DVD 4.7 GB 硬编码 `4000000`**（`VideoEncoders.java:1883`）；
- shutter-encoder 的**真跑一次编码做能力探测**（Android 有 `MediaCodecList` 元数据）；
- filmcompress 的**无能力探测 + 无 CPU 回退**（选错编码器整个进程失败）与 **`intel` 死选项**（CLI 声明了但代码无分支）；
- staxrip 的 **`For i = 1 To 100000` 暴力搜索目标体积**（`GlobalClass.vb:1142-1163`）；
- lossless-cut 的**没有任务队列**（全局 `workingRef` 互斥锁 + 手动逐文件批量）；
- dublette 的**纯连通分量分组**（`scan.rs:63-110`，且单测断言传递闭包）、**无条件 O(n²) 全对比较**（`dedupe.rs:298-342`）、**dry-run 执行时重跑全流程**（`lib.rs:74-90`）、**`fs::remove_file` 永久删除**；
- ddoss 的**纯并查集分组无链式防护**（`grouper.py:55-110`）与 **`ProcessPoolExecutor` + pickling**；
- 0x90d 的**单文件二进制数据库 + 全库 `HashSet` 常驻内存**（`DatabaseUtils.cs:41-43`）、**ONNX/DINOv2**、**音频指纹**、**`DynamicExpresso` 动态求值**、**16×16 旧灰度格式兼容逻辑**（AGENTS.md 明确不考虑向后兼容）；
- videohash 的 **1 fps 全片抽帧**（2 小时视频 ≈ 149 Mpx / 450 MB 常驻）与 **`-s 144x144` 非等比拉伸**；
- compressO 的**串行批处理 + `log::error!` 续跑**（`tauri_commands/media.rs:21/121-127`：失败 id 不写进结果 map，前端靠**键缺失**推断失败，无失败原因、无 `FAILED` 枚举）；
- compressO 的**越界哨兵值建模**（`StartCompression.tsx:106-111` 用 `quality: 101` 表示「未使用质量」，`presetName: Option<String>` 的 `null` 兼表他意）；
- compressO 的 **TS 与 Rust 两份重复的容器兼容逻辑**（`VideoCodec.tsx:23-54` 与 `ffmpeg.rs:218-226` 字符串 `contains`）；
- staxrip 的**约 150 字段扁平 `Project` 神对象**、**`BinaryFormatter` 存 `Jobs.dat`/`Profiles.dat`/`Settings.dat`**、**用文件最后写入时间 ±2 天判定工具版本**（`Package.vb:3047-3065`）、**执行前先停用任务导致失败静默丢件**（`GlobalClass.vb:496`）、**`Process.GetCurrentProcess().Kill()` 作错误恢复**（`GlobalClass.vb:1605`）；
- shutter-encoder 的 **5 段回退正则进度阶梯 + 硬编码 ffmpeg banner 解析**（`ProcController.vb:636-697`、`:168-185`）与**队列项是裸命令行字符串**（`RenderQueue.java:677`）。

**因许可而不采纳**：`lossless-cut`（**GPL-2.0-only**，全仓无 "any later version"）——**其代码不得并入本项目（AGPL-3.0），只能借鉴设计思想**；引用其结论时不得附带代码片段。其余 16 个仓库许可均已实测且无额外障碍（见 §0.1）。

**因与既有产品裁决冲突而不采纳**：任何形式的「静默丢轨」「静默降质」「静默永久删除」「把系统回收站期限描述为应用可精确控制的 30 天」「管理未授权文件」「把删除记录当作文件已永久删除的充分证据」。
