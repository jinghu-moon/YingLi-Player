# 架构决策记录（ADR）

本目录收录**已裁决**的架构决策。每条 ADR 记录：背景（含被推翻的旧假设）→ 决策 → 后果 → 备选方案与否决理由 → 验证方式 → 关联文档。

**编号说明**：编号沿用参考资料（`refer/Video-deduplication-and-recycle-bin-design-resources/`）的 ADR 系列，以便与资料对照；编号缺口（如没有 ADR-DEDUP-001）是正常的，不代表缺失。

| 编号 | 标题 | 状态 | 关联待决项 |
| --- | --- | --- | --- |
| [ADR-DEDUP-002](ADR-DEDUP-002-media-item-as-content-equivalence-class.md) | `MediaItem` 是内容等价类，重复组是派生查询而不是实体 | 已采纳 | D1 |
| [ADR-DEDUP-003](ADR-DEDUP-003-similar-video-reopen-preconditions.md) | 相似视频（SIMILAR）维持关闭，冻结六项重开前置条件 | 已采纳 | D6 |
| [ADR-RECYCLE-003](ADR-RECYCLE-003-storage-backend.md) | 回收站存储后端：R1 系统回收站为主、R2 应用副本为受控降级 | 已采纳 | D2-a |
| [ADR-RECYCLE-004](ADR-RECYCLE-004-delete-confirmation-by-ownership.md) | 永久删除按「文件所有权」分派确认方式 | 已采纳 | — |
| [ADR-TRANSCODE-002](ADR-TRANSCODE-002-dual-engine-boundary.md) | 编码层用 Transformer、封装层用手写 extractor + muxer 的双引擎边界 | 已采纳 | — |

**总纲**：`docs/architecture/Organizing-Page-Function-Design.md`（21 节）。ADR 是其中被裁决部分的**不可变记录**；该文档继续演进，ADR 不随其修改而改写——若决策变更，新增一条 ADR 取代旧条。

**执行约定**（来自 `AGENTS.md`）：项目处于开发期、未发布，因此**鼓励破坏性重构，不考虑向后兼容**；但每个改动必须配「改前 / 改后」测试，证明新旧功能都正常。决策优先级：正确性 → 根因解决 → 性能 → 架构质量 → 简洁性 → 可维护性。
