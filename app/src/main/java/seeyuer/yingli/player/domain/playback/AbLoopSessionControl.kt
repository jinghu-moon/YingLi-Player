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
     * 会话 → 客户端的 AB 状态回流（MediaSession session extras）。
     * 值一律用字符串：Bundle 的类型不匹配是静默失败，用字符串至少能明确地解析失败。
     */
    const val EXTRA_POINT_A: String = "yingli.abPointA"
    const val EXTRA_POINT_B: String = "yingli.abPointB"
    const val EXTRA_LOOP_COUNT: String = "yingli.abLoopCount"
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
    /** 请求会话设点；命令是异步的（跨会话边界），拒绝原因通过 [AbLoopSessionStore.abLoop] 不体现。 */
    fun requestSetAbPoint(point: AbPoint)

    /** 请求会话清除 AB 区间（并归零计数）。 */
    fun requestClearAbLoop()
}
