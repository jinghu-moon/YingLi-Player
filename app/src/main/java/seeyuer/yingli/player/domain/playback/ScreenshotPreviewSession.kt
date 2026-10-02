package seeyuer.yingli.player.domain.playback

/**
 * 预览卡倒计时（读条）在本次截图会话里的推送状态。
 *
 * 为什么倒计时余量要在这儿再记一份、state 里那份不够用：**只有这里记得住"展开期间还剩多久"**。
 * 展开时倒计时定格，一旦它在这段时间里走到 0，[ScreenshotUiState] 就已经回到 `Idle`，
 * 而卡片（大图预览）还在屏幕上——此时关闭预览必须能按原定语义"3 秒到点，消失并提示保存路径"，
 * 但 Idle 里已经没有任何余量可读。UI 状态是"这一刻画什么"，会话状态是"这次截图还剩多少时间"，
 * 两者职责不同，所以分开存。
 *
 * 纯函数 + 不可变数据：ViewModel 把计时器事件喂进来，单测可以逐毫秒地推演。
 */
data class ScreenshotPreviewSession(
    val displayName: String,
    val uri: String,
    val location: String,
    val remainingMillis: Long,
    val expanded: Boolean,
    val deleted: Boolean = false,
    /** 读条已到点，但大图预览还开着（卡片不消失）。 */
    val expiredWithPreviewOpen: Boolean = false,
    /**
     * 保存路径已经提示过了。
     *
     * "只提示一次"必须是**一条显式记录**，而不是靠"从余量/展开态反推"：反推出来的判据
     * 一旦漏掉某个组合（例如已到点、余量为 0、又还在展开），同一条提示就会重复弹出来。
     * 这里把它记成一个事实，[tick] 以它为准。
     */
    val locationReported: Boolean = false,
    /**
     * 大图预览打开期间**真实流逝**的时间。
     *
     * 为什么必须有这一位：展开时读条"定格"指的是**不走进度**，而不是"时间停止"。
     * 用户看着大图预览，3 秒一样在走；如果把这段时间直接丢掉，那么"展开 → 一直看图"
     * 就会出现读条永远停在原地、永远不提示保存路径的假象。这里把它累计起来，
     * 收起时一次性结清（[collapse]），到点则该提示就提示。
     */
    val expandedElapsedMillis: Long = 0,
) {
    init {
        require(displayName.isNotBlank())
        require(remainingMillis in 0..ScreenshotUiState.PREVIEW_DURATION_MILLIS)
        require(expandedElapsedMillis >= 0)
    }

    /** 卡片形态的读条是否还在推进：未删除、未展开、还有余量。 */
    val isCounting: Boolean
        get() = !deleted && !expanded && remainingMillis > 0

    /**
     * 展开形态是否还没到点：读条虽定格，但"看大图的这段时间"仍在往余量里扣。
     * 到点后置为 false，避免同一个提示被反复推出去。
     */
    val isCountingWhileExpanded: Boolean
        get() = !deleted && expanded && !expiredWithPreviewOpen && remainingMillis > 0

    /**
     * 读条**此刻该显示**的余量。
     *
     * 展开时 [remainingMillis] 是"点开大图那一刻的读数"（读条定格就定格在这个值上），
     * 而"看大图的这段时间"记在 [expandedElapsedMillis] 里。两者相减才是真实的剩余，
     * 也是收起大图后读条要继续的那个数（见 [collapse]）。
     *
     * 如果只减 [remainingMillis]，收起时就会把已经看过的那段时间又算一遍，
     * 表现为"收起后读条反而比展开前还长"——这正是它必须单独存在的原因。
     */
    val displayRemainingMillis: Long
        get() = (remainingMillis - if (expanded) expandedElapsedMillis else 0L).coerceAtLeast(0)

    /** 读条进度（0..1）：UI 直接画这个比例，不再自己减。 */
    val progress: Float
        get() = displayRemainingMillis.toFloat() / ScreenshotUiState.PREVIEW_DURATION_MILLIS.toFloat()

    companion object {
        /** 用一次成功捕获开启会话：满读条、未展开、未删除。 */
        fun start(
            displayName: String,
            uri: String,
            location: String,
        ): ScreenshotPreviewSession = ScreenshotPreviewSession(
            displayName = displayName,
            uri = uri,
            location = location,
            remainingMillis = ScreenshotUiState.PREVIEW_DURATION_MILLIS,
            expanded = false,
        )

        /**
         * 用一份已有的 UI 状态重建会话。
         *
         * 用在删除失败之后：文件还在、卡片还在，倒计时必须接着原来的余量走，
         * 而不是重新给满 3 秒（那等于把"3 秒读条"这条设计语义改掉了）。
         */
        fun fromPreview(preview: ScreenshotUiState.Preview): ScreenshotPreviewSession = ScreenshotPreviewSession(
            displayName = preview.displayName,
            uri = preview.uri,
            location = preview.location,
            remainingMillis = preview.remainingMillis,
            expanded = false,
        )
    }
}

/** 计时器推进的结果：新会话 + 需要执行的副作用（都是"恰好一次"的语义）。 */
data class ScreenshotPreviewTick(
    val session: ScreenshotPreviewSession,
    /** 本次 tick 后读条的真实余量：调用方据此更新 UI，**每一 tick 都必须同步**。 */
    val publishRemainingMillis: Long? = null,
    /** 倒计时到底且卡片未展开：卡片消失，调用方关闭预览（并提示保存路径）。 */
    val expired: Boolean = false,
    /** 倒计时在**展开中**到底：卡片不消失（定格态没有"自动消失"），但要提示一次保存路径。 */
    val notifyLocationOnly: Boolean = false,
)

/**
 * 预览卡倒计时的计时步长（毫秒）。
 *
 * 放在领域层而不是 ViewModel 里：它是"读条每格 50ms、3 秒共 60 格"这条可读性契约的一部分
 * （[ScreenshotPreviewSession.tick] 的入参单位），单测要按同一个刻度推演时间。
 * 取 50ms：120Hz 屏上 100ms 一格的读条已经能看出跳格，而 16ms 一步对这条一秒走 1/3 的
 * 匀速动画没有任何观感收益，只是白白唤醒主线程。
 */
const val SCREENSHOT_PREVIEW_TICK_MILLIS = 50L

/**
 * 推进 [elapsedMillis] 毫秒。
 *
 * 两种形态分开处理，但**时间都要记账**：
 * - 卡片形态（未展开）：读条按 [elapsedMillis] 递减，归零即"卡片消失"（`expired`）；
 * - 大图预览形态（展开）：读条定格（`remainingMillis` 不动），但走掉的时间记进
 *   [ScreenshotPreviewSession.expandedElapsedMillis]，由 [collapse] 一次性结清。
 *
 * 副作用只可能发生一次：`expired` / `notifyLocationOnly` 之后会话进入"不再计数"或
 * "已到期"的终态，继续 tick 什么都不会发生。这是"提示保存路径只弹一次"的根，
 * 而不是靠调用方去重。
 */
fun ScreenshotPreviewSession.tick(elapsedMillis: Long): ScreenshotPreviewTick {
    if (elapsedMillis <= 0 || deleted) return ScreenshotPreviewTick(this)
    if (expanded) return tickWhileExpanded(elapsedMillis)
    if (remainingMillis <= 0) return ScreenshotPreviewTick(this)
    val remaining = (remainingMillis - elapsedMillis).coerceAtLeast(0)
    val next = copy(remainingMillis = remaining)
    return if (remaining > 0) {
        ScreenshotPreviewTick(next, publishRemainingMillis = remaining)
    } else {
        ScreenshotPreviewTick(next.copy(locationReported = true), publishRemainingMillis = 0L, expired = true)
    }
}

/**
 * 展开形态的推进：读条定格，只累计"看着大图的这段时间"。
 *
 * 累计量一旦够把剩下的读条走完，就切到"已到期"终态并让调用方提示一次保存路径；
 * 卡片（大图）**不消失**——定格态没有自动消失这回事，收场由用户关闭大图触发（见 [collapse]）。
 *
 * [locationReported] 一旦置起就再也不会返回 `notifyLocationOnly = true`：
 * 提示次数由此断言，而不是由调用方去重。
 */
private fun ScreenshotPreviewSession.tickWhileExpanded(elapsedMillis: Long): ScreenshotPreviewTick {
    if (locationReported) return ScreenshotPreviewTick(this)
    val accumulated = expandedElapsedMillis + elapsedMillis
    if (accumulated < remainingMillis) {
        val next = copy(expandedElapsedMillis = accumulated)
        // 读条本身定格，但**显示余量**要继续跟着走：它就是"看大图还剩多少时间"，
        // 也是收起大图后读条从哪儿继续的唯一依据（见 displayRemainingMillis）。
        return ScreenshotPreviewTick(next, publishRemainingMillis = next.displayRemainingMillis)
    }
    return ScreenshotPreviewTick(
        copy(
            remainingMillis = 0,
            expandedElapsedMillis = 0,
            expiredWithPreviewOpen = true,
            locationReported = true,
        ),
        publishRemainingMillis = 0L,
        notifyLocationOnly = true,
    )
}

/** 展开为大图：读条开始定格。 */
fun ScreenshotPreviewSession.expand(): ScreenshotPreviewSession = copy(expanded = true)

/**
 * 关闭大图预览。
 *
 * 返回 `null` 表示这次关闭同时触发了「倒计时已到点」的正常流程：读条在展开期间已经走完
 * （或收起时刚好走完最后一格），关闭大图就等于原来的"卡片自动消失"，调用方要提示保存路径；
 * 此时卡片也不再存在。
 *
 * 否则返回的会话已经把展开期间流逝的时间**结清**：读条从"展开那一刻的余量 - 看图的时长"
 * 接着走，而不是傻等在那里——否则用户在大图里看 30 秒再关掉，读条还会从头再走 3 秒。
 */
fun ScreenshotPreviewSession.collapse(): ScreenshotPreviewSession? {
    if (deleted) return null
    if (expiredWithPreviewOpen || remainingMillis <= 0) return null
    val remaining = (remainingMillis - expandedElapsedMillis).coerceAtLeast(0)
    if (remaining <= 0) return null
    return copy(expanded = false, remainingMillis = remaining, expandedElapsedMillis = 0)
}

/** 标记为已删除：此后任何 tick / 收起都不会再有提示（删除过的图片不再提示路径）。 */
fun ScreenshotPreviewSession.markDeleted(): ScreenshotPreviewSession = copy(deleted = true, expanded = false)
