package seeyuer.yingli.player.feature.player

/**
 * 播放页系统返回键的归宿，按规格 §8 的优先级解析：
 *
 * ```text
 * 关闭面板/工具 -> 解锁 -> 退出全屏 -> 退出 PlayerRoute
 * ```
 *
 * 只有 [LEAVE_PLAYER] 时不拦截返回，交给路由栈处理。
 */
internal enum class PlayerBackAction {
    CLOSE_TOOL,
    UNLOCK,
    EXIT_FULLSCREEN,
    LEAVE_PLAYER,
}

internal fun resolvePlayerBack(
    hasTransientTool: Boolean,
    locked: Boolean,
    isFullscreen: Boolean,
): PlayerBackAction = when {
    hasTransientTool -> PlayerBackAction.CLOSE_TOOL
    locked -> PlayerBackAction.UNLOCK
    isFullscreen -> PlayerBackAction.EXIT_FULLSCREEN
    else -> PlayerBackAction.LEAVE_PLAYER
}
