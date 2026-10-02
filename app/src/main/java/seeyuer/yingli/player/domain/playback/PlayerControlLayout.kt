package seeyuer.yingli.player.domain.playback

enum class PlayerControlSurface(val capacity: Int) {
    LANDSCAPE_TOP_RIGHT(4),
    LANDSCAPE_BOTTOM_LEFT(4),
    LANDSCAPE_BOTTOM_RIGHT(4),
    PORTRAIT_BOTTOM(7),

    ;

    val landscape: Boolean
        get() = this != PORTRAIT_BOTTOM
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
    PLAYLIST,
    INFO,
    PIP,
    ORIENTATION,
    LOCK,
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
            PlayerControlSurface.LANDSCAPE_TOP_RIGHT to listOf(PlayerControlId.PLAYLIST, PlayerControlId.AUDIO, PlayerControlId.SUBTITLE),
            PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT to listOf(PlayerControlId.ORDER, PlayerControlId.SPEED, PlayerControlId.SCREENSHOT, PlayerControlId.AB_LOOP),
            PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT to listOf(PlayerControlId.PIP, PlayerControlId.FULLSCREEN, PlayerControlId.LOCK),
            PlayerControlSurface.PORTRAIT_BOTTOM to listOf(
                PlayerControlId.SPEED,
                PlayerControlId.ORDER,
                PlayerControlId.SCALE,
                PlayerControlId.ORIENTATION,
                PlayerControlId.PIP,
                PlayerControlId.FULLSCREEN,
                PlayerControlId.LOCK,
            ),
        )
    }
}

interface PlayerControlLayoutRepository {
    val layout: kotlinx.coroutines.flow.Flow<PlayerControlLayout>
    suspend fun set(layout: PlayerControlLayout)
}
