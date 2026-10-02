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
 * **新增一个控件时，必须同时做两件事（缺一不可）：**
 * 1. 在 [BACKFILLED_CONTROLS_BY_VERSION] 里为它所属的**新版本号**登记这些 id；
 * 2. 把 [CURRENT_LAYOUT_VERSION] 提到那个版本号。
 *
 * 回填只认"引入版本落在 `(dataVersion, currentVersion]` 区间内"的代，
 * 所以漏做第 2 步只会让这一代控件补不进来（新按钮不出现，开发者一眼就能发现），
 * 不会把用户移除过的按钮复活；只做第 2 步：那个版本号上没有任何登记，回填自然补不出东西。
 *
 * **为什么是"按版本分代"，而不是"一个累积列表"：**
 * 累积列表只有"数据版本 < 当前版本"这一个判据，于是版本号每提升一次，
 * 所有老用户都会被重新补上历史上每一代的新控件——包括他们此前主动移除过的那些，
 * "永久移除"便只在同一个版本内成立。分代登记后判据变成"这一代比数据版本新、且不超过当前版本"：
 * 已经写进用户数据的旧代控件，不会因为后续版本提升而复活，移除才是永久的。
 */
internal object PlayerControlLayoutCodec {
    /**
     * 当前布局格式版本。[encode] 把它写进数据，[migrate] 用它判定"比本实现更新的数据不要动"，
     * 同时用它给回填划定上界（只回填不超过它的代次）；
     * 每次在 [BACKFILLED_CONTROLS_BY_VERSION] 里登记一代新控件，就要把它提到那一代。
     */
    const val CURRENT_LAYOUT_VERSION = 4

    /**
     * 分代回填登记表：key = 引入这些控件的布局版本，value = 该版本新增的控件（按此顺序补进"工具托盘"）。
     *
     * 只回填"引入版本比数据版本新、且不超过 [CURRENT_LAYOUT_VERSION]"的那几代，
     * 所以一个控件登记在哪一代，就决定了哪一代之前的用户会收到它。
     * 登记了比当前版本还新的代不会造成任何回填（[migrate] 把它挡在上界之外）：
     * 忘记提版本号时表现为"新按钮不出现"，而不是"用户的移除被复活"。
     */
    val BACKFILLED_CONTROLS_BY_VERSION: Map<Int, List<PlayerControlId>> = mapOf(
        4 to listOf(
            PlayerControlId.MIRROR_HORIZONTAL,
            PlayerControlId.MIRROR_VERTICAL,
            PlayerControlId.BACKGROUND_PLAYBACK,
        ),
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
        return migrate(stored, version ?: LEGACY_LAYOUT_VERSION, CURRENT_LAYOUT_VERSION)
    }

    /**
     * 把 [backfill] 里所有"引入版本落在 `(dataVersion, currentVersion]` 区间内"的代，
     * 按引入版本升序逐代补进"工具托盘"。
     *
     * 上界（`it.key <= currentVersion`）是结构性保证：登记了比当前版本还新的代一律不参与回填。
     * 于是"登记了控件却忘了提 [CURRENT_LAYOUT_VERSION]"的后果只是这一代补不进来（功能缺失，立刻可见），
     * 而不会把它补进数据、[encode] 却仍写旧版本号，导致用户移除后每次读取又被复活（静默错误）。
     *
     * - [dataVersion] 等于当前版本：没有哪一代落在区间内 → 原样返回，用户移除过的控件不会复活（移除是永久的）；
     * - [dataVersion] 比当前版本还新（例如从更高版本备份恢复）：不认识的数据一律不动；
     * - legacy（版本 0）：登记表里所有不超过当前版本的代都比它新，按升序全部回填。
     *
     * [backfill] 可注入：测试用虚构登记表（例如 `mapOf(4 to listOf(A), 5 to listOf(B))`）
     * 就能验证"跨代只补新代"，无需真的提升 [CURRENT_LAYOUT_VERSION]。
     * 容量不足或与同方向槽位冲突时补不进去（[PlayerControlLayout.ensureControls] 的语义），只跳过、不抛异常。
     */
    internal fun migrate(
        layout: PlayerControlLayout,
        dataVersion: Int,
        currentVersion: Int,
        backfill: Map<Int, List<PlayerControlId>> = BACKFILLED_CONTROLS_BY_VERSION,
    ): PlayerControlLayout {
        if (dataVersion > currentVersion) return layout
        return backfill.entries
            .filter { it.key > dataVersion && it.key <= currentVersion }
            .sortedBy { it.key }
            .fold(layout) { migrated, generation ->
                migrated.ensureControls(PlayerControlSurface.TOOLS, generation.value)
            }
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
