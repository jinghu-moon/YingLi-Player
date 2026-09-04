# Phase 6 整理、历史与首页契约

## 关系模型

```text
MediaItem --< Favorite
MediaItem --< MediaTagRef >-- TagDefinition
MediaItem --< PlaylistItem >-- Playlist
MediaItem --< CollectionItem >-- Collection(MANUAL)
Collection(SMART) ----------- FilterExpression
MediaItem -- PlaybackHistory
MediaItem -- RecentlyOrganized
```

收藏、标签、播放列表和集合只保存媒体 ID，不复制或移动媒体文件。媒体关系表刻意允许悬空引用，媒体重新定位后关系保持；独立清理流程可删除确定失效的引用。标签、播放列表和集合名称使用唯一索引，批量关系写入使用 Room 事务。

Room v2 同时加入 Phase 5/6 新表，v1 媒体主表不改列。迁移只创建新表和索引，保留原媒体、位置、标签和播放进度。

## 标签约束

- 名称去除首尾空格，标签最多 40 字符，列表/集合最多 80 字符。
- 颜色为中性加七个精选圆点色；容器始终使用中性表面，红绿圆点不承担错误/成功语义。
- `CompositeTag` 只保存两个以上标签 ID 和 ALL/ANY 规则，类型结构禁止再次包含组合标签。

## 历史与继续观看

- 单次会话播放达到 10 秒才增加一次播放次数；Service 对同一媒体会话只写一次计数。
- 无痕播放不写历史。数据库失败只记录脱敏诊断，不阻塞播放。
- 继续观看要求：位置至少 10 秒、未完成、距结尾超过 5 秒。
- 历史按最后播放时间降序；最近整理独立按整理时间合并同一媒体。

## 首页模块

首页标题固定为“影里”，按以下顺序按需显示：本地搜索、继续观看、最近添加、常用文件夹。搜索只匹配本地名称、文件夹别名和标签，不提供建议或网络内容。空模块不渲染伪数据，单个模块为空不影响其他模块。
