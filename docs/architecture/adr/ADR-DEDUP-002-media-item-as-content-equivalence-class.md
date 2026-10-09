# ADR-DEDUP-002：`MediaItem` 是内容等价类，重复组是派生查询而不是实体

- **状态**：已采纳（Accepted）
- **日期**：2026-10-09
- **决策者**：项目作者（采纳 `docs/architecture/Organizing-Page-Function-Design.md` §19 的建议）
- **相关**：`docs/06-feature-roadmap.md:199`、`docs/architecture/phase-12-duplicate-algorithm-card.md`、`docs/architecture/Organizing-Page-Function-Design.md` §4.7 / §7.1 / §7.6
- **取代**：无
- **编号说明**：编号沿用参考资料 ADR 系列，与本项目自有 ADR 序号无关

---

## 1. 背景

### 1.1 现状

`docs/06-feature-roadmap.md:199` 早已声明目标模型：**`MediaItem` = 内容等价类；`MediaLocation` = 物理文件位置；用户状态挂 `MediaItem`；文件操作针对 `MediaLocation`。**

但该模型**尚未实现**：`MediaIdentityResolver.kt` 的归并逻辑第 3 步无条件返回 `NewIdentity`，`DefaultMediaScanner` 里的 contentHash 归并分支是**死代码**。当前实际形态是**一个文件一个 `MediaItem`**（temporary one-file-per-item）。

同时 Phase 12 落地了一套与之冲突的实体：`duplicate_fingerprints` / `duplicate_groups` / `duplicate_group_members` 三张表 + `DuplicateGroup*` 契约 + `RoomDuplicateRepository` + `DefaultDuplicateScanner`。

### 1.2 第一性原理

- **字节完全相同是等价关系**（自反、对称、传递）。等价关系**可以由一条 `GROUP BY` 完整表达**，不需要 group 实体、不需要 member 实体、不需要 group id。
- **只有非等价关系**（感知相似度）才需要 pair 边。
- 因此「重复组」是**查询结果**，不是需要持久化的事实。持久化它等于制造**第二个真相来源**——而第二个真相来源必然会与第一个不一致（新文件加入后组就过期了）。

### 1.3 由事实推出的操作空间

由 F33/F35：
- 哈希相等的所有 location 属于同一等价类；
- 归并会**迁移用户状态**（标签、收藏、播放进度、播放列表成员、集合成员、切片项目、处理任务输入），这是一次**用户可见的数据变更**；
- 归并后媒体库会「少一个条目」，用户需要看到这是自己的操作结果。

因此**归并必须发生在「去重处置」时，由用户确认**，而不是在编目扫描时静默发生。

---

## 2. 决策

1. **`MediaItem` 是内容等价类，`MediaLocation` 是物理文件位置。**
2. **不建 group 实体**：删除 `duplicate_fingerprints`、`duplicate_groups`、`duplicate_group_members` 三张表。
3. **哈希落在 `media_locations` 上**：复用已存在的 `fastFingerprint` / `contentHash` 列，新增 `hashAlgorithmVersion` 列与 `(contentHash, hashAlgorithmVersion)` 索引，支撑 L3 的 `GROUP BY`。
4. **重复组由查询产生**，不持久化。
5. **归并在去重处置时发生，且与删除在一个 Room 事务内完成**（D1-b「一步完成」）。
6. **「忽略此组」需要持久化**，且不能用 `deleteGroup` 实现——必须新增 `duplicate_ignores(contentHash, sizeBytes, memberCount, ignoredAtEpochMillis)`（修 G26）。
7. **归并的不变量（必须用测试锁住）**：
   - 每个 `media_locations` 行恰好属于一个 `media_items` 行（`media_item_locations.locationId` 唯一索引）。
   - 不存在没有 location 的 `media_items` 行（归并后必须删除 loser）。
   - 同一 `contentHash` 且 `hashAlgorithmVersion` 相同的所有 location，若都已哈希，则**必须**属于同一个 `media_items` 行——**这是「归并已完成」的判据**。
   - 不变量 3 在扫描后可能被打破（新加入的重复文件），此时 UI 显示为「有 N 组待处理重复」。**「不变量被打破」= 「有重复待处理」，这是同一个事实的两种说法。**

---

## 3. 后果

### 3.1 正面

- **少 3 张表、少 3 个实体、少一套契约**（奥卡姆剃刀）。
- 消除「组过期」这一类 bug：查询结果永远与当前哈希状态一致。
- 与 `docs/06:199` 的既有声明一致，不需要修改路线图的产品模型。
- 用户状态挂在 `MediaItem` 上，因此**归并引用只需改指向，不需要逐表迁移业务逻辑**。

### 3.2 负面 / 代价

- 需要一次**破坏性数据库迁移**（v9 → v10 或更高）：直接 `DROP TABLE` 三张重复表 + 重建 `trash_entries`。
- `trash_entries` 主键从 `mediaItemId` 改为 `locationId`（修 G16），**所有 11 处 `LEFT JOIN` 必须同步改**。
- 需要写**引用迁移事务**，覆盖标签、收藏、播放进度、播放列表成员、集合成员、切片项目、处理任务输入。
- 归并必须处理冲突：当 loser 与 keeper **同时**拥有同一标签/进度时，必须定义合并语义（并集 / 取较新 / 取较完整），否则归并会静默丢数据。

### 3.3 中立

- `DuplicateDeletionValidator` 的**复核语义**（`FILE_CHANGED` / `EVIDENCE_VERSION_CHANGED`）是正确的，予以保留；只是输入从 `MediaFingerprint` 换成 `MediaLocation`。
- 删除计划约束从「必须覆盖整组」**放宽为子集关系**：`keep` 非空、`trash` 非空、`trash ⊆ group \ keep`。等价类模型下允许部分移入更自然。

---

## 4. 备选方案与否决理由

| 方案 | 否决理由 |
| --- | --- |
| 保留 `duplicate_groups` 实体，扫描时重建 | 第二个真相来源；新文件加入后组过期；需要失效逻辑与重建时机判断 |
| 保留 group 但只在处置时创建 | 仍然是持久化的派生数据；且与 `MediaItem` = 等价类的模型重复表达同一事实 |
| 在编目扫描时立即归并 | 扫描期哈希代价不可控（用户没要求扫描时就哈希全部文件）；归并会静默迁移用户状态，属于未确认的数据变更 |
| 用 `dao.deleteGroup(groupId)` 实现「忽略此组」 | 会在下次扫描时重建同一组，用户反复看到（G26） |
| 为「相似视频」预生成组 | 相似**不是**等价关系，不能预生成组；必须用 pair 边，且约束 `locationAId < locationBId`（见 ADR-DEDUP-003） |

---

## 5. 验证方式

1. **不变量测试**：三条不变量各自一个测试，包括「新加入重复文件后不变量 3 被打破」。
2. **归并事务回滚测试**：注入失败，断言不得留下「引用已改指向但位置未回收」的中间态。
3. **缓存失效测试**：缓存三元组 `(sizeBytes, modifiedEpochMillis, hashAlgorithmVersion)` 任一变化都必须重算；**算法版本升级后旧指纹必须失效**。
4. **引用迁移测试**：七类引用逐一验证重指向；冲突场景验证合并语义。
5. **`duplicate_ignores` 测试**：忽略后再次扫描不得重新出现；成员数变化后的语义必须有明确定义。
6. **内存测试**：扫描过程**不得把整库载入内存**（当前 `DefaultDuplicateScanner.scan()` 用 `loadAllMedia()`，是 G20 缺陷）。
7. **门禁**：`.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`。

---

## 6. 关联

- `docs/architecture/Organizing-Page-Function-Design.md` §4.7（内容等价类）、§7.1（分层扫描）、§7.3（保留与合并引用）、§7.6（破坏性改动清单）、§14.4（阶段 3）
- `docs/architecture/phase-12-duplicate-algorithm-card.md`（已按 L0–L4 重写）
- `docs/09-tdd-phased-development-checklist.md` 任务 12.2 / 12.6（已更新）
- `docs/06-feature-roadmap.md:199`（模型声明 + 尚未实现的标注）
- ADR-DEDUP-003（相似视频重开前置条件）
