# ADR-DEDUP-003：相似视频（SIMILAR）维持关闭，并冻结六项重开前置条件

- **状态**：已采纳（Accepted）
- **日期**：2026-10-09
- **决策者**：项目作者（采纳 `docs/architecture/Organizing-Page-Function-Design.md` §19 的建议）
- **相关**：`docs/architecture/phase-12-duplicate-algorithm-card.md:13`、`docs/06-feature-roadmap.md:456`、`docs/architecture/Organizing-Page-Function-Design.md` §7.5
- **取代**：无
- **编号说明**：编号沿用参考资料 ADR 系列，与本项目自有 ADR 序号无关

---

## 1. 背景

### 1.1 现状

`DefaultDuplicateScanner.kt` 对 SIMILAR 模式直接返回 `Rejected("SIMILAR_EXPERIMENT_DISABLED")`；UI 已有 `duplicates_similar` 分段控件与 `duplicates_similar_disabled` 提示。

即：**入口已在界面上，能力被显式拒绝。**

### 1.2 参考实现的证据（2026-10-09 对 `refer/video-deduplication-repos/` 的逐仓库调研）

对 7 个去重参考仓库的源码调研结论一致指向同一个事实：

| 仓库 | 相似度特征 | 阈值依据 | precision/recall |
| --- | --- | --- | --- |
| `videohash` | 1 fps 全片拼贴 + 小波哈希，64 位 | 硬编码汉明 ≤10/64 | **无** |
| `MediaDedupe` | pHash（DCT 8×8 中位数）5 帧 | `PHASH_THRESHOLD = 8`，仅图片侧有语义分级注释 | **无** |
| `hclivess/video-duplicate-finder` | dHash 9×8 × 12 帧 | 三档产品预设 98%/90%/80% | **无** |
| `videoduplicatefinder`（0x90d） | 32×32 灰度逐像素差 + pHash quorum ≥0.6 | `Percent = 96f` | **无** |

**七个仓库无一提供 precision/recall 曲线。** videohash 的 10 位是硬编码，hclivess 的 90% 是产品预设，0x90d 的 96% 同样没有标注集支撑。它们提供的是**算法候选**，不是**可采信阈值**。

0x90d 是其中工程最完备的（缓存键含内容指纹、daisy-chain 多数剪枝、崩溃面包屑、分块回收站、质量准则排序器），但它的 AI 部分片段检测注释里自述标定依据是「真匹配的重编码片段 cos 在 0.89–0.95，与同场景噪声逐帧无法区分」——**这恰恰说明阈值必须逐数据集标定**。

### 1.3 第一性原理

- 相似**不是等价关系**（不自反、不对称、不传递）。因此相似结果**不能预生成组**，只能是 pair 边 + 置信度。
- 没有标注集时，「阈值」是一个**无法验证的数字**。写死它等于把误判风险直接交给用户。
- 误判的后果是**删除不该删的视频**。这个后果的量级（不可逆的数据损失）决定了**不能用未验证的阈值**。

---

## 2. 决策

**SIMILAR 维持关闭。** 重开必须逐条满足以下**六项前置条件，缺一不可**：

1. **建立标注集**：至少覆盖资料文件 7 列出的负样本——同大小不同内容、相同首帧/封面不同内容、相同片头主体不同、相同配乐不同画面、同场景不同录制时刻、可变帧率、旋转元数据、HDR、不常见编码、损坏文件。**标注集必须记录来源与授权方式。**
2. **用标注集画出 precision/recall 曲线**，而不是凭经验写死阈值。
3. **冻结版本化的特征提取器接口**（`algorithmVersion` + 采样配置 `configHash`），并把版本写进 `docs/architecture/` 的算法卡。
4. **冻结「严格 / 均衡 / 宽松」三档预设**（`docs/06:456` 要求首版不暴露任意阈值）。
5. **记录 10k 库的内存 / 耗时 / 候选缩减率基线。**
6. **相似候选必须与完全重复分入口、分结果证据、分删除路径**（相似不是等价关系，不能预生成组）。

**数据结构预留（不在本轮实现）**：未来引入 `similarity_evidence(locationAId, locationBId, ...)`，**约束 `locationAId < locationBId`**（映射资料文件 7 的 `media_key_a < media_key_b` 到本项目的 `locationId`）。**当前不做。**

**若未来重开，算法分层次序（来自调研，非当前实现）**：
- 第一层：dHash 12 帧 + **命中率**判据（**不是逐帧算术平均**——逐帧平均对时序敏感，hclivess 的做法是缺陷）。
- 第二层：pHash 仅对高相似候选。
- 片头/片尾：首尾固定窗口 all-pairs **只作候选生成器**，绝不作删除依据。
- 阈值初始锚点可参考 `MediaDedupe` 的四档语义（`0 = pixel-identical`、`1–5 = 同图不同分辨率或极小 JPEG 压缩`、`6–8 = 同图更强压缩或小裁剪`、`> 10 = 主题相似，刻意排除`），**但必须用本项目自己的标注集重新标定**。
- 采样配置必须参与缓存键（hclivess 的 `frames` 是缓存键的一部分；MediaDedupe 的 `intensive_n` 变化强制重算——这是正确先例）。

---

## 3. 后果

### 3.1 正面

- 用户不会被未验证的阈值引导去删除不该删的视频。
- 省掉一整条算法链、标注集工作与人工复核成本。
- 「关闭」是**显式的、可解释的**，UI 有文案说明原因，而不是功能缺失。
- 六项前置条件把「什么时候可以重开」变成**可判定的**，避免反复讨论。

### 3.2 负面 / 代价

- 用户无法用感知相似度找到「同一段视频的不同压缩版本」。**这是产品上真实的功能缺口。**
- 入口已经在 UI 上（`duplicates_similar` 分段控件），关闭状态下用户会看到「不可用」，需要有明确文案说明这是**有意为之**而不是 bug。

### 3.3 中立

- 完全重复（EXACT）链路不受影响，且已强于全部参考仓库：L0 大小分桶 → L1 快速指纹（头尾 64 KiB）→ L2 流式 SHA-256 → L3 `GROUP BY contentHash, sizeBytes` → L4 提交前重算。

---

## 4. 备选方案与否决理由

| 方案 | 否决理由 |
| --- | --- |
| 按参考仓库的阈值直接开启 | 七个仓库无一提供 precision/recall；阈值不可验证；误判后果是删除不该删的视频 |
| 用 videohash 的汉明 ≤10/64 | 硬编码值；videohash 自述不能做子片段判定、旋转 >10° 失效、无缓存、1 fps 全片抽帧在 Android 上内存必崩 |
| 用 0x90d 的 `Percent = 96f` | 无标注集支撑；其 AI 部分片段注释自述「与同场景噪声逐帧无法区分」，说明阈值必须逐数据集标定 |
| 引入 ONNX/DINOv2 或音频指纹提升准确率 | 与项目裁决冲突（禁 JNI、禁深度学习、禁音频指纹）；且不解决**阈值无依据**这个根本问题 |
| 先开启再逐步调阈值 | 用户已在生产中用它删过文件，误判不可逆 |

---

## 5. 验证方式

**当前阶段（关闭状态）**：
1. `DefaultDuplicateScanner` 对 SIMILAR 必须返回 `Rejected("SIMILAR_EXPERIMENT_DISABLED")`，且**不得**回退到 EXACT 结果。
2. UI 的 `duplicates_similar_disabled` 文案必须解释这是有意为之。
3. 断言 SIMILAR 路径**不产生任何删除入口**。

**未来重开时（六项前置条件逐条验证）**：
1. 标注集覆盖度检查（逐类负样本）。
2. precision/recall 曲线产物必须入库（`docs/architecture/`）。
3. `algorithmVersion` + `configHash` 写入算法卡，且缓存失效测试通过。
4. 三档预设存在且不暴露任意阈值。
5. 10k 库基线报告存在。
6. 相似结果与完全重复的入口、证据、删除路径三者分离的测试。

---

## 6. 关联

- `docs/architecture/Organizing-Page-Function-Design.md` §7.5（维持关闭与六项前置条件）、§17.1 D6（待决项）、§14.4（阶段 3）
- `docs/architecture/phase-12-duplicate-algorithm-card.md:13`
- `docs/06-feature-roadmap.md:456`（首版不暴露任意阈值）、`:480-489`（M12 需求验证后的扩展）
- ADR-DEDUP-002（`MediaItem` = 内容等价类；相似不是等价关系，故需 pair 边）
- `refer/video-deduplication-repos/`（七个仓库的调研证据）
