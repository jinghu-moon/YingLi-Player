# Phase 12 去重算法卡

> **本文状态**：已按 `docs/architecture/Organizing-Page-Function-Design.md` §7（去重设计）与 §14.4（阶段 3）的裁决重写，**并已于 2026-10-09（阶段 3）全部落地**——落地细节与验证见该文档 §14.4.1。下文的**【目标】**一律表示「已实现」，保留原措辞是为了留下「当初按什么标准判定」的记录。
> 与旧版的三处结构性差异：① 扫描改为 **L0–L4 分层**，不再把整库载入内存；② 判定结果直接落在 `media_locations` 的已有列上，**删除 `duplicate_fingerprints` / `duplicate_groups` / `duplicate_group_members` 三张表**；③ 删除计划的「覆盖整组」约束**放宽为子集关系**（理由见下）。
> 标记约定：**【现状】**= 写本文时的代码行为（现已改造，**当作历史缺陷记录**）；**【目标】**= 已裁决并已实现。

## 判定链（L0–L4）

完全重复扫描分五层，**每层都用 SQL 收敛，不在应用内存里持有整库**：

| 层 | 动作 | 实现 |
| --- | --- | --- |
| **L0** | 按大小分桶，丢掉唯一大小的文件 | `SELECT sizeBytes, COUNT(*) FROM media_locations GROUP BY sizeBytes HAVING COUNT(*) >= 2` |
| **L1** | 对 L0 幸存者算快速指纹（大小 + 前 64 KiB + 后 64 KiB 的 SHA-256） | 写入 `media_locations.fastFingerprint` |
| **L2** | 对快速指纹相同的组算完整 SHA-256（256 KiB 缓冲流式读取） | 写入 `media_locations.contentHash` |
| **L3** | 按内容哈希分组，每行即一个 Exact 组 | `SELECT contentHash, sizeBytes, COUNT(*) ... GROUP BY contentHash, sizeBytes HAVING COUNT(*) >= 2` |
| **L4** | 提交删除前重算复核 | 逐候选重算 size + 完整 SHA-256 |

所有耗时读取均可取消，取消不会被转换成普通空结果。

**【历史缺陷 — 已修复】** 旧实现 `DefaultDuplicateScanner.scan()` 用 `loadAllMedia()` 把**整个媒体库累加进一个内存列表**再分组，与「不把整库载入内存」的既有要求矛盾（G20）。现由 SQL 完成 L0/L3 的收敛，L1/L2 用键游标（`id > :afterId`）分页，**刻意不用 OFFSET**——筛选条件会被自己的写入改变，OFFSET 会漏行。

## 判定条件

**只有大小与完整 SHA-256 都相同**的两个或更多位置才能形成 Exact 组。

### 缓存有效性（新增 `hashAlgorithmVersion`）

指纹缓存的有效性由**三元组**共同决定：`(sizeBytes, modifiedEpochMillis, hashAlgorithmVersion)`。任一变化即视为失效，必须重算。

**【历史缺陷 — 已修复】** 改造前 `media_locations.fastFingerprint` / `contentHash` 曾是**死列**（从未被写入，见 G13）；旧 `DefaultDuplicateScanner` 的有效性判断只有 `algorithmVersion == 1 && sourceModifiedEpochMillis == item.modifiedEpochMillis`，**缺 `sizeBytes` 与算法版本的独立列**。

**【已实现】** `media_locations` 增加 `hashAlgorithmVersion` 列（`MIGRATION_9_10`）与 `(contentHash, hashAlgorithmVersion)` 索引。两个写入者共同维持「三元组任一项变化 ⇒ 哈希必为 null」：去重扫描写哈希前重读元数据、变化就丢弃这一次的结果；编目扫描（`DefaultMediaScanner`）发现 size/mtime 变化时把哈希置空。**文件大小与修改时间不能作为绝对的内容身份凭证**，它们只是缓存失效的触发条件。

## 存储模型

**【目标】** 判定结果落在 `media_locations` 的已有两列（`fastFingerprint`、`contentHash`）加新增的 `hashAlgorithmVersion` 上，**不新建「重复」实体**。`MediaLocation` 表示物理文件位置，去重针对位置实体（`docs/06-feature-roadmap.md:199`）。

**【已实现 — 破坏性】** 删除 `duplicate_fingerprints`、`duplicate_groups`、`duplicate_group_members` 三张表及其 DAO。旧模型与「`MediaItem` 表示内容实体」的既定模型重叠且互相矛盾（G24）。

**【已实现的唯一新增表】**

| 表 | 字段 | 语义 |
| --- | --- | --- |
| `duplicate_ignores` | `contentHash`, `sizeBytes`, `memberCount`, `ignoredAtEpochMillis` | 当前组成员数**等于** `memberCount` 时隐藏该组；成员数变化时该组重新出现 |

**【历史缺陷 — 已修复】** 改造前 `ignore(groupId)` 实现为 `dao.deleteGroup(groupId)`，**下次扫描会重建同一组**，用户会反复看到自己已经忽略的组（G26）。上表修此缺陷。

## 删除计划与引用合并

**【已实现】** 计划的约束从「必须覆盖整组」**放宽为子集关系**：

- `keep` 集合非空；
- `trash` 集合非空；
- `trash ⊆ group \ keep`。

**【理由】** 旧约束要求计划覆盖整组，与「用户可以只处理组内一部分」的实际使用冲突；`DefaultDuplicateDeletionExecutor` 现有的 `DuplicateDeletionValidator.validate` 返回 `INCOMPLETE_SELECTION` 就是这个过严约束的产物。**放宽后仍然禁止空计划与全删计划**，安全性由「`keep` 非空」保证。

**【已实现】** 归并与删除**一步完成，且整个归并在一个 Room 事务内**。逐表规则见设计文档 §7.4（`media_item_locations` 改指向、`media_tags` 并集、`favorites` 逻辑或、`playback_history` 的 `playCount` 求和 / `lastPlayedAt` 取最大 / `lastPosition` 取较新者、`recently_organized` 取最大、`playlist_items` 并集并按 `addedAtEpochMillis` 重排 `position`、`collection_items` 并集、`clip_projects.sourceMediaId` 重指向、`processing_project_inputs.mediaItemId` 重指向 + 去重 + 重排 position；loser 无 link 行后删除）。

**两处落地时的具体化（与本节原措辞不同，以实现为准）**：

1. **`clip_projects` 还要改 `sourceLocationId`，且必须改指「用户保留的那个位置」**（由删除执行器一并传进 `DuplicateMerge.survivorLocationId`），而不是「survivor 名下最近见到的位置」——后者很可能**正要被移入回收站**，切片项目会指向一个不可见的位置，直到下次导出才发现。
2. **标签并集不能用 `UPDATE OR IGNORE … SET mediaItemId = survivor`**。唯一约束冲突时 `OR IGNORE` 只是跳过那一行，loser 的行**原样留下**：`media_tags` 靠 `media_items` 的级联会被删掉，但 `media_tag_refs` 只对 `tag_definitions` 有外键，于是留下指向已删除条目的孤儿行（2026-10-09 的 `RoomDuplicateMergeRepositoryTest` 撞上过）。统一改成「先 `INSERT OR IGNORE … SELECT` 复制、再显式删 loser」。

**【历史缺陷 — 已修复】** 改造前 `DefaultDuplicateDeletionExecutor` 只调 `mutationRepository.trash(targets)`，**没有任何引用合并**：被删位置的标签、收藏、播放进度、播放列表成员、集合成员、切片项目与处理任务输入**全部随位置一起消失**（G12 的直接后果）。

执行前重新读取大小与完整 SHA-256，并比较算法版本与修改时间；任一变化都以 `FILE_CHANGED` 拒绝。**执行统一调用媒体库回收站，不直接永久删除**（`docs/09-tdd-phased-development-checklist.md:951-957`）。

## 保留建议的依据

**【事实】** 对 Exact 组，「更高分辨率」「更大体积」「更长时长」**没有任何区分力**——字节相同蕴含这些属性全同。可用的区分维度只有：`missingScanCount` / `lastSeenEpochMillis` → `modifiedEpochMillis` → 来源优先级（`SAF_TREE > MEDIA_STORE > ALL_FILES`）→ 非隐藏目录 → 文件名不含副本标记。

**【目标】** 默认**不自动选择**删除项；`docs/09:951-957` 的「默认不勾选删除」「无自动批量删除入口」保持不变。

## Similar v1

**【已实现 — 保持关闭（D6）】** 生产扫描固定返回 `SIMILAR_EXPERIMENT_DISABLED`，UI 不提供相似视频删除入口（`DuplicateGroup` 已无 `mode` 字段，SIMILAR 模式下候选列表为空）。项目目前没有经过隐私审核的代表性标注集，也没有 precision/recall、10k 库复杂度和人工复核成本基线。

**【历史缺陷 — 已删除该实现】** `AndroidDuplicateFingerprintGenerator.perceptualHashes` 曾用 `MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST_SYNC)` 在 3 个位置（0.2 / 0.5 / 0.8）取帧，算 8×8 **averageHash**。该文件已随阶段 3 一起删除（收敛为单一的 `MediaContentHasher`），**感知哈希当前在代码中不存在**。这与资料主张的多帧 pHash 不同，且**单帧/少帧感知哈希不作为最终判据**；将来重开 SIMILAR 时按 §14.5 的六项前置条件从零实现，不是恢复这段代码。

## 性能与增量

- **不预先承诺「首次扫描几分钟完成」**；当前并发由串行扫描约束。
- 增量触发：`ContentObserver` 只作感知层，**不是正确性来源**；正确性由启动对账与三元组缓存校验保证。
- 真机性能矩阵仍缺：GB 级文件、10k 媒体库、厂商 ContentProvider 差异。
- SQLite B-tree 足够作第一版；先用 `EXPLAIN QUERY PLAN` 验证，**不因十万视频默认引入向量数据库**。

## 与压缩 / 转码的关系

- 压缩/转码产出是**新位置**（改变内容必须重写字节），作为新文件参与扫描。
- 源文件内容变化 → 其指纹三元组失效 → 依赖它的匹配结论必须作废。
- 算法版本升级 → 按新版本重算，**不覆盖旧版本结果，直到新任务成功**。
- **去重扫描是只读的**，与压缩/转码/切片**不互斥**；互斥只发生在对同一位置的**处置**动作之间（D5）。

## 已知限制

- SHA-256 降低误判概率，但不宣称密码学上绝对无碰撞。
- 内容提供者无法报告稳定大小、或读取中发生变化时，不生成 Exact 结论。
- 删除是回收站语义，不代表闪存物理擦除。
- 相似视频判定未开启；片段级去重（`docs/06-feature-roadmap.md:480-489` M12）未实现。
