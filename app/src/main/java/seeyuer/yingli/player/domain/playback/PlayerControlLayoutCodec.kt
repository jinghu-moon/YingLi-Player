package seeyuer.yingli.player.domain.playback

/**
 * 播放控件布局的持久化编解码 + 版本迁移。
 *
 * **为什么版本号写在 value 里，而不是写在 key 名里（layout_v2 / layout_v3）：**
 * key 名只能表达"这份数据是被哪一代实现写下来的"，而键是写入侧的事实：
 * 用户只要在设置页动过一次布局，当时的实现就会写满"当前最新键"，
 * 于是"这份布局写入时是什么格式版本"与"它是否已经补齐过该版本新增的按钮"再也无法区分——
 * 旧实现读到 v3 键就直接返回，导致之后新增的按钮对这类设备永远不可见。
 * 版本进入 value 后，读取侧同时拿到"写入时的格式版本"和"布局内容本身"，
 * 才能真正做到**按版本一次性回填**。
 *
 * **新增一个控件时，必须同时做两件事：**
 * 1. 把它的 id 追加到 [BACKFILLED_CONTROLS]；
 * 2. 把 [CURRENT_LAYOUT_VERSION] 加一。
 *
 * 只做第 1 步，老用户看不到新按钮：版本号没变，他们的数据会被判为"已回填"而原样返回。
 * 只做第 2 步，新按钮没有登记，也不会被补进"工具托盘"。
 */
internal object PlayerControlLayoutCodec {
    /**
     * 当前布局格式版本。每次向 [BACKFILLED_CONTROLS] 追加控件时加一；
     * 读取到 `version < CURRENT_LAYOUT_VERSION` 的数据才会执行回填。
     */
    const val CURRENT_LAYOUT_VERSION = 4

    /** 4 版本新增并登记的低频按钮（按此顺序补进"工具托盘"）。 */
    val BACKFILLED_CONTROLS = listOf(
        PlayerControlId.MIRROR_HORIZONTAL,
        PlayerControlId.MIRROR_VERTICAL,
        PlayerControlId.BACKGROUND_PLAYBACK,
    )

    /** 没有版本前缀的老值（v2 / v3 键写下的内容，或更早的无键格式）一律按版本 0 处理。 */
    private const val LEGACY_LAYOUT_VERSION = 0

    private const val VERSION_SEPARATOR = ';'
    private const val SLOT_SEPARATOR = '='
    private const val CONTROL_SEPARATOR = ','

    fun encode(layout: PlayerControlLayout): String {
        val body = PlayerControlSurface.entries.joinToString(VERSION_SEPARATOR.toString()) { surface ->
            val controls = layout.controls(surface).joinToString(CONTROL_SEPARATOR.toString())
            "$surface$SLOT_SEPARATOR$controls"
        }
        return "$CURRENT_LAYOUT_VERSION$VERSION_SEPARATOR$body"
    }

    fun decode(raw: String): PlayerControlLayout {
        val separatorIndex = raw.indexOf(VERSION_SEPARATOR)
        // 版本前缀必须是纯数字；老值的第一段是 "SURFACE=ID,ID"，解析失败即视为 legacy。
        val version = if (separatorIndex >= 0) raw.substring(0, separatorIndex).toIntOrNull() else null
        val body = if (version == null) raw else raw.substring(separatorIndex + 1)
        // 结构非法（未知枚举名、超出容量、同方向重复……）时回落到默认布局，而不是让读取抛异常。
        val stored = runCatching { parse(body) }.getOrElse { PlayerControlLayout() }
        if ((version ?: LEGACY_LAYOUT_VERSION) >= CURRENT_LAYOUT_VERSION) {
            // 已是当前（或更新）版本：原样返回。
            // 这样"用户主动移除过的回填按钮"不会被每次读取时反复塞回去，移除才是永久的。
            return stored
        }
        // 老数据：只把用户布局里还没有的回填按钮补进"工具托盘"，其余槽位与顺序保持不变。
        return stored.ensureControls(PlayerControlSurface.TOOLS, BACKFILLED_CONTROLS)
    }

    private fun parse(body: String): PlayerControlLayout {
        val slots = body.split(VERSION_SEPARATOR).associate { section ->
            val parts = section.split(SLOT_SEPARATOR, limit = 2)
            val surface = PlayerControlSurface.valueOf(parts[0])
            val ids = parts.getOrNull(1).orEmpty().split(CONTROL_SEPARATOR).filter(String::isNotBlank).map(PlayerControlId::valueOf)
            surface to ids
        }
        return PlayerControlLayout(PlayerControlSurface.entries.associateWith { slots[it].orEmpty() })
    }
}
