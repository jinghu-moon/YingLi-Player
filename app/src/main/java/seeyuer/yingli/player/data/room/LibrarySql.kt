package seeyuer.yingli.player.data.room

/**
 * 回收站视图谓词的单一份定义。
 *
 * 设计稿 §8.8 明确要求：回收站以 `locationId` 为键之后，「item 是否可见」必须改为
 * 「该 item 是否还有**未被回收的位置**」，且**必须**建立可复用的 SQL 片段，
 * 否则九处各写一遍必然漂移（`docs/architecture/Organizing-Page-Function-Design.md:1185`）。
 *
 * 用法：把它放进任一以 `media_items` 为作用域表的查询的 `WHERE` 里，例如
 * `WHERE media_items.id IS NOT NULL AND $VISIBLE_ITEM`。
 *
 * 谓词引用外层表的 `media_items.id`；子查询内的别名（`mil` / `te`）只在本片段内可见。
 */
internal object LibrarySql {
    /**
     * 「该 item 至少有一个未被回收的位置」。
     *
     * 旧写法是 `LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id`
     * 加 `WHERE trash_entries.mediaItemId IS NULL`——那是**item 级**判定：
     * 一个 item 有多个内容相同的位置时，回收其中一个就会把整条条目从媒体库里隐藏掉。
     * 新写法是**位置级**判定，与 `trash_entries` 的新主键一致。
     */
    const val VISIBLE_ITEM: String =
        "EXISTS (" +
            "SELECT 1 FROM media_item_locations mil " +
            "LEFT JOIN trash_entries te ON te.locationId = mil.locationId " +
            "WHERE mil.mediaItemId = media_items.id AND te.locationId IS NULL)"

    /**
     * 「该位置未被回收」的单行判定，用于必须以 `media_locations` 为作用域的查询
     * （例如去重候选的 L0–L3）。
     */
    const val VISIBLE_LOCATION: String =
        "NOT EXISTS (SELECT 1 FROM trash_entries te WHERE te.locationId = media_locations.id)"
}
