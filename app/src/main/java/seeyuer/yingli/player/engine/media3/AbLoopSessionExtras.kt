package seeyuer.yingli.player.engine.media3

import android.os.Bundle
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopSessionCommands

/**
 * 会话 → 客户端的 AB 状态在 **Bundle 与线格式之间的唯一一处映射**。
 *
 * 为什么要有这一层、而不是两端各自 `putString` / `getString`：状态跨会话边界走的是
 * MediaSession session extras（一个没有类型约束的 `Bundle`），两端各自翻译就一定会漂移。
 * 踩过的现场：服务端把"只设了 A"如实发了出来（extras 里只有 `EXTRA_POINT_A`），
 * 客户端却按"必须同时有 A 和 B"重建状态，A 因此被丢掉 —— 胶囊继续显示"A 未设置"、
 * B 与清除继续禁用，而命令**确实成功**，所以连拒绝提示都没有。线格式的语义
 *（哪些组合是合法状态）收在 [AbLoopSessionCommands.encodeState] / `decodeState` 一处，
 * 这里只负责 Bundle 的搬运。
 *
 * 放在 `engine/media3` 而不是领域层：领域层不允许出现平台类型（见 `ArchitectureRulesTest`），
 * 而 `Bundle` 是平台类型；服务的命令回执（`AbLoopSessionResult.kt`）也放在同一层，方向一致。
 */
internal fun AbLoopSession.toSessionExtras(): Bundle = Bundle().apply {
    AbLoopSessionCommands.encodeState(this@toSessionExtras).forEach { (key, value) -> putString(key, value) }
}

/**
 * 读取会话发布的 AB 状态。键值缺失（例如从未设过点）一律走
 * [AbLoopSessionCommands.decodeState] 的兜底，调用方不需要、也不允许自己判定合法性。
 */
internal fun Bundle.readAbLoopSession(): AbLoopSession = AbLoopSessionCommands.decodeState(
    AbLoopSessionCommands.STATE_KEYS
        .mapNotNull { key -> getString(key)?.let { value -> key to value } }
        .toMap(),
)
