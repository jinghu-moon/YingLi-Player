package seeyuer.yingli.player.domain.playback

/** AB 设点/清除的结果：拒绝原因直接复用会话命令的拒绝码，UI 反馈不必再翻译一层。 */
sealed interface AbLoopCommandOutcome {
    data object Applied : AbLoopCommandOutcome
    data class Rejected(val rejection: PlaybackCommandRejection) : AbLoopCommandOutcome
}

/**
 * 会话侧的 AB 命令口：由 `PlaybackSessionRuntime` 实现，供服务侧的命令入口
 *（`YingLiPlaybackService` 的 MediaSession 自定义命令）调用。
 *
 * 为什么命令要经过 MediaSession 自定义命令而不是在 UI 侧直接写状态：AB 的业务规则必须和
 * 引擎（同一个 session）在同一侧判定与生效。UI 侧写状态就等于又造了一份权威状态，
 * 而"三份 AB 状态"正是本次重构要收敛掉的根因。
 */
interface AbLoopSessionControl {
    suspend fun setPoint(point: AbPoint): AbLoopCommandOutcome
    suspend fun clear()
}

/** MediaSession 自定义命令的动作名与 extras 键（客户端与服务端共用，避免字符串两边各写一遍）。 */
object AbLoopSessionCommands {
    const val SET_POINT: String = "seeyuer.yingli.player.AB_SET_POINT"
    const val CLEAR: String = "seeyuer.yingli.player.AB_CLEAR"
    const val ARG_POINT: String = "point"

    /**
     * 设点命令的**回执**键：会话把拒绝码原样放在命令结果的 extras 里带回客户端。
     *
     * 为什么单独开一个键，而不是把拒绝码塞进下面那条 session extras 状态通道：extras 是
     * "区间 + 计数"这类**状态**的通道（持续存在、每次覆盖），而拒绝是**一次性命令回执**；
     * 混进状态通道会让"设点被拒"和"当前区间"互相覆盖，客户端也无法区分
     * "这次设点被拒"与"上一次的拒绝还没消费掉"。回执因此走 `SessionResult`（见
     * `engine/media3/AbLoopSessionResult.kt`），键只用于把拒绝码原样带给调用方。
     */
    const val ARG_REJECTION: String = "rejection"

    /**
     * 会话 → 客户端的 AB 状态回流（MediaSession session extras）。
     * 值一律用字符串：Bundle 的类型不匹配是静默失败，用字符串至少能明确地解析失败。
     */
    const val EXTRA_POINT_A: String = "yingli.abPointA"
    const val EXTRA_POINT_B: String = "yingli.abPointB"
    const val EXTRA_LOOP_COUNT: String = "yingli.abLoopCount"

    /**
     * 状态通道的**全部**键。Bundle 侧按它建 / 读，JVM 用例按它断言线格式里到底有几个键
     *（"少发了一个键"和"多发了一个键"都是两端漂移的症状，必须能被断言到）。
     */
    val STATE_KEYS: List<String> = listOf(EXTRA_POINT_A, EXTRA_POINT_B, EXTRA_LOOP_COUNT)

    /**
     * 会话状态 → 线格式。
     *
     * 为什么编解码必须是**唯一一对**、而且要和 Bundle 无关：状态要跨会话边界走一遍
     * "编码 → 解码"，两端各写一套就一定会漂移。这里的漂移正是"点 A 有涟漪、状态不变"
     * 的根因 —— 服务端如实把"只设了 A"发了出来，客户端却按"必须同时有 A 和 B"重建，
     * 于是 A 被丢掉，胶囊继续显示"A 未设置"、B 与清除继续禁用；而命令本身**确实成功**，
     * 所以也没有任何拒绝提示可显示。
     *
     * 与 Bundle 无关是为了让往返能在 JVM 上直接测（真机用例只能证明"这一条路径通"，
     * 证明不了"每一种状态都通"）。
     */
    fun encodeState(session: AbLoopSession): Map<String, String> = buildMap {
        session.state.pointA?.let { put(EXTRA_POINT_A, it.toString()) }
        session.state.pointB?.let { put(EXTRA_POINT_B, it.toString()) }
        put(EXTRA_LOOP_COUNT, session.loopCount.toString())
    }

    /**
     * 线格式 → 会话状态。
     *
     * **只设了 A 必须原样恢复**：它是合法状态（用户还没设 B），[AbLoopState] 本来就允许
     * `pointB == null`，胶囊也按 `pointA != null` 启用 B 与清除。
     *
     * 只丢弃**会话根本不可能持有的载荷**：只有 B（没有 A 就没有区间）、A ≥ B（违反
     * `pointA < pointB` 的不变量）、以及负数位置（[AbLoopState] 会直接抛）。这些一律退回
     * "没有区间"：宁可少显示一个区间，也不要为了显示它构造一个非法状态。
     */
    fun decodeState(values: Map<String, String>): AbLoopSession {
        val pointA = values[EXTRA_POINT_A]?.toLongOrNull()?.takeIf { it >= 0 }
        val pointB = values[EXTRA_POINT_B]?.toLongOrNull()?.takeIf { it >= 0 }
        val state = when {
            pointA == null -> AbLoopState()
            pointB == null -> AbLoopState(pointA = pointA)
            pointA < pointB -> AbLoopState(pointA = pointA, pointB = pointB)
            else -> AbLoopState()
        }
        return AbLoopSession(
            state = state,
            // 计数读不出来就当 0（绝不凭空猜一个数）；**没有区间时一律归零** —— 计数描述的是
            // "这个区间循环了几次"，区间没了它就没有意义（与 runtime 撤掉区间时归零同一条规则）。
            loopCount = if (state.pointA == null) {
                0
            } else {
                values[EXTRA_LOOP_COUNT]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            },
        )
    }

    /**
     * 拒绝码的线格式：用**枚举名**而不是序号，两端共用这一对编解码。
     * 各写一套映射是"同一个拒绝码在两边含义漂移"的起点。
     */
    fun encodeRejection(rejection: PlaybackCommandRejection): String = rejection.name

    /** 解码未知码返回 null，由调用方按其掌握的上下文（命令结果码）决定回退，而不是猜一个拒绝原因。 */
    fun decodeRejection(name: String?): PlaybackCommandRejection? =
        name?.let { code -> PlaybackCommandRejection.entries.firstOrNull { it.name == code } }
}

/**
 * 控制器侧的 AB 通道：把 UI 的设点/清除转成**会话命令**送给会话，并暴露会话侧回流的
 * AB 状态投影（区间 + 计数）。
 *
 * 为什么客户端不能自己算：设点校验、互换、相等边界、帧吸附、启用策略、计数条件全部属于会话；
 * 客户端自己做一份就是"第二份权威状态"，正是本次重构要删掉的东西。
 * 命令与状态的传输走 MediaSession 自定义命令 + session extras，不额外引入一条旁路。
 */
interface AbLoopPlaybackControl : AbLoopSessionStore {
    /**
     * 请求会话设点，并**带回会话的判定结果**。
     *
     * 为什么是 suspend 而不是 fire-and-forget：命令跨会话边界，判定在会话侧的命令锁里按当时的
     * timeline 做，客户端拿不到"被拒"这件事就只能静默 —— 那正是"设点被拒无提示"这个缺口的根因。
     * 结果只有一条路径（`SessionResult` 的回执），不额外开旁路。
     */
    suspend fun requestSetAbPoint(point: AbPoint): AbLoopCommandOutcome

    /** 请求会话清除 AB 区间（并归零计数）。清除没有失败分支：空区间再清一次仍是空区间。 */
    fun requestClearAbLoop()
}
