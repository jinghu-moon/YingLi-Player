package seeyuer.yingli.player.domain.playback

enum class PlayerControlSurface(val capacity: Int) {
    LANDSCAPE_TOP_RIGHT(4),
    LANDSCAPE_BOTTOM_LEFT(4),
    LANDSCAPE_BOTTOM_RIGHT(4),
    PORTRAIT_BOTTOM(7),

    /** 竖屏底栏上方的 "更多" 托盘：低频按钮集中在这里，可用设置页的槽位编辑器自由搬运。 */
    TOOLS(8),

    ;

    val landscape: Boolean
        get() = this == LANDSCAPE_TOP_RIGHT || this == LANDSCAPE_BOTTOM_LEFT || this == LANDSCAPE_BOTTOM_RIGHT
}

enum class PlayerControlId(val fixed: Boolean = false) {
    PREVIOUS,
    NEXT,
    SPEED,
    ORDER,
    SCALE,
    AUDIO,
    SUBTITLE,
    SCREENSHOT,
    AB_LOOP,
    /** 画面镜像翻转：左右 / 上下各一个，与截图、AB 循环同属低频工具。 */
    MIRROR_HORIZONTAL,
    MIRROR_VERTICAL,
    PLAYLIST,
    INFO,
    PIP,
    ORIENTATION,
    LOCK,
    SETTINGS,
    MORE,
    FULLSCREEN(true),
}

data class PlayerControlLayout(
    val slots: Map<PlayerControlSurface, List<PlayerControlId>> = defaultSlots(),
) {
    init {
        require(slots.keys.containsAll(PlayerControlSurface.entries))
        PlayerControlSurface.entries.forEach { surface ->
            val controls = slots.getValue(surface)
            require(controls.distinct().size == controls.size) { "A control can only appear once per slot" }
            require(controls.size <= surface.capacity) { "Slot $surface is full" }
        }
        PlayerControlSurface.entries.groupBy { it.landscape }.values.forEach { surfaces ->
            val controls = surfaces.flatMap { slots.getValue(it) }
            require(controls.distinct().size == controls.size) { "A control can only appear once per orientation" }
        }
    }

    fun controls(surface: PlayerControlSurface): List<PlayerControlId> = slots.getValue(surface)

    fun contains(id: PlayerControlId): Boolean = slots.values.any { id in it }

    /**
     * 确保 [ids] 都出现在 [surface] 槽位里（缺失的按给定顺序追加，已在任何槽位的不重复添加）。
     *
     * 用途：**持久化布局的一次性迁移**。新增按钮后，老用户存下来的布局里没有它们，
     * 用这个方法把新按钮补进默认槽位，同时完全不改动用户对其余按钮的排布。
     */
    fun ensureControls(surface: PlayerControlSurface, ids: List<PlayerControlId>): PlayerControlLayout =
        ids.fold(this) { layout, id -> if (layout.contains(id)) layout else layout.add(surface, id) }


    fun canAdd(surface: PlayerControlSurface, id: PlayerControlId): Boolean =
        id !in controls(surface) &&
            PlayerControlSurface.entries.none { it.landscape == surface.landscape && id in controls(it) } &&
            controls(surface).size < surface.capacity

    fun add(surface: PlayerControlSurface, id: PlayerControlId): PlayerControlLayout {
        if (!canAdd(surface, id)) return this
        return copy(slots = slots + (surface to (controls(surface) + id)))
    }

    fun remove(id: PlayerControlId): PlayerControlLayout {
        if (id.fixed) return this
        return copy(slots = slots.mapValues { (_, controls) -> controls.filterNot { it == id } })
    }

    fun remove(surface: PlayerControlSurface, id: PlayerControlId): PlayerControlLayout {
        if (id.fixed) return this
        return copy(slots = slots + (surface to controls(surface).filterNot { it == id }))
    }

    fun move(surface: PlayerControlSurface, from: Int, to: Int): PlayerControlLayout {
        val items = controls(surface).toMutableList()
        if (from !in items.indices || to !in items.indices) return this
        val item = items.removeAt(from)
        items.add(to, item)
        return copy(slots = slots + (surface to items))
    }

    fun move(
        fromSurface: PlayerControlSurface,
        from: Int,
        toSurface: PlayerControlSurface,
        to: Int = controls(toSurface).size,
    ): PlayerControlLayout {
        val sourceItems = controls(fromSurface).toMutableList()
        if (from !in sourceItems.indices || fromSurface != toSurface && controls(toSurface).size >= toSurface.capacity) return this
        val item = sourceItems.removeAt(from)
        val targetItems = if (fromSurface == toSurface) sourceItems else controls(toSurface).toMutableList()
        val targetIndex = to.coerceIn(0, targetItems.size)
        targetItems.add(targetIndex, item)
        return copy(slots = slots + (fromSurface to sourceItems) + (toSurface to targetItems))
    }

    companion object {
        fun defaultSlots() = mapOf(
            PlayerControlSurface.LANDSCAPE_TOP_RIGHT to listOf(PlayerControlId.PLAYLIST, PlayerControlId.AUDIO, PlayerControlId.SUBTITLE, PlayerControlId.SETTINGS),
            PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT to listOf(PlayerControlId.ORDER, PlayerControlId.SPEED),
            PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT to listOf(PlayerControlId.PIP, PlayerControlId.FULLSCREEN, PlayerControlId.LOCK),
            PlayerControlSurface.TOOLS to listOf(
                PlayerControlId.SCREENSHOT,
                PlayerControlId.AB_LOOP,
                PlayerControlId.MIRROR_HORIZONTAL,
                PlayerControlId.MIRROR_VERTICAL,
                PlayerControlId.INFO,
            ),
            PlayerControlSurface.PORTRAIT_BOTTOM to listOf(
                PlayerControlId.SPEED,
                PlayerControlId.SCALE,
                PlayerControlId.ORIENTATION,
                PlayerControlId.PIP,
                PlayerControlId.FULLSCREEN,
                PlayerControlId.LOCK,
                PlayerControlId.MORE,
            ),
        )
    }
}

interface PlayerControlLayoutRepository {
    val layout: kotlinx.coroutines.flow.Flow<PlayerControlLayout>
    suspend fun set(layout: PlayerControlLayout)
}
