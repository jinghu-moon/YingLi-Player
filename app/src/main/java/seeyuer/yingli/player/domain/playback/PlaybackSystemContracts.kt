package seeyuer.yingli.player.domain.playback

enum class ScreenshotFailure {
    EMPTY_FRAME,
    PERMISSION,
    STORAGE_FULL,
    READ_ONLY,
    UNKNOWN,
}

sealed interface ScreenshotResult {
    /**
     * 捕获成功。[uri] 是删除时要交回 [ScreenshotFileGateway] 的句柄；
     * [location] 是**给用户看的**保存位置（例如 `Pictures/YingLi/影片_1000_1700000000000.jpg`）。
     *
     * 为什么位置由网关给出、而不是 UI 自己拼：往 MediaStore 里写的是网关（`RELATIVE_PATH` +
     * `DISPLAY_NAME` 都在它手里），只有它知道文件最终落在哪；UI 再拼一遍就是第二份真相，
     * 写入位置一改就会漂移。也不用 `DATA` 列去反查绝对路径——它在 API 29 起已废弃，
     * 而本项目开着 `-Werror`，且 URI 访问本就不需要绝对路径（见 Media3ScreenshotGateway）。
     */
    data class Saved(
        val displayName: String,
        val uri: String = "",
        val location: String = "",
    ) : ScreenshotResult

    data class Failed(val reason: ScreenshotFailure) : ScreenshotResult
}

interface ScreenshotGateway {
    /**
     * 捕获当前帧并保存。[rotation] 是当前的画面旋转角度：保存的图片要与用户看到的朝向一致，
     * 所以由调用方传入，而不是让网关去猜。
     */
    suspend fun capture(
        videoTitle: String,
        positionMillis: Long,
        rotation: VideoRotation,
    ): ScreenshotResult
}

interface ScreenshotFileGateway {
    suspend fun delete(uri: String): Result<Unit>
}

/**
 * 截图删除失败码。网关用它作为异常 message，UI 用它决定提示文案；
 * 两侧共用同一份定义，避免各写一个字符串而漂移。
 */
enum class ScreenshotDeleteFailure(val code: String) {
    URI_INVALID("SCREENSHOT_URI_INVALID"),
    PERMISSION_DENIED("SCREENSHOT_DELETE_PERMISSION_DENIED"),
    FAILED("SCREENSHOT_DELETE_FAILED"),
}

sealed interface ScreenshotUiState {
    data object Idle : ScreenshotUiState
    data object Armed : ScreenshotUiState
    data object Capturing : ScreenshotUiState
    data class Preview(
        val displayName: String,
        val uri: String = "",
        /** 给用户看的保存位置；为空表示网关没给（例如单测里的存根），此时倒计时结束不提示路径。 */
        val location: String = "",
        val remainingMillis: Long = PREVIEW_DURATION_MILLIS,
        /**
         * 是否已展开为大图预览。展开期间倒计时**定格**（删除入口只在展开态出现，见设计稿 §4.10）。
         *
         * 为什么把"展开"建模成 Preview 自己的一位、而不是另开一个 Preview/Expanded 状态类型：
         * 展开前后卡片上的东西完全一样，差别只有尺寸、倒计时是否推进、删除按钮是否在场；
         * 拆成两个状态会让 reducer 多出一组来回搬运 displayName/uri/location 的复制分支。
         */
        val expanded: Boolean = false,
    ) : ScreenshotUiState {
        init {
            require(displayName.isNotBlank() && remainingMillis in 0..PREVIEW_DURATION_MILLIS)
            // 展开态必须有图可放大：没有 uri 的预览卡在界面上点不开，也不该进入这个状态。
            require(!expanded || uri.isNotBlank())
        }
    }
    data class Failed(val reason: ScreenshotFailure) : ScreenshotUiState

    companion object {
        const val PREVIEW_DURATION_MILLIS = 3_000L
    }
}

sealed interface ScreenshotUiEvent {
    data object Arm : ScreenshotUiEvent
    data object CaptureStarted : ScreenshotUiEvent
    data class CaptureCompleted(val result: ScreenshotResult) : ScreenshotUiEvent
    data class TimeElapsed(val millis: Long) : ScreenshotUiEvent {
        init { require(millis >= 0) }
    }

    /**
     * 预览卡展开 / 收起（点击卡片）。
     *
     * 展开没有做"再点一次收起"的对称切换，而是两个显式事件：设计稿里只有"点一下放大"，
     * 收起由大图预览自己的关闭按钮（点背景或返回键）完成，走的是另一条路径——
     * 把两者混成一个 Toggle 会让"收起"的入口变得不可见。
     */
    data class ExpandChanged(val expanded: Boolean) : ScreenshotUiEvent
    data object Close : ScreenshotUiEvent
    data object MediaChanged : ScreenshotUiEvent
}

object ScreenshotUiReducer {
    fun reduce(state: ScreenshotUiState, event: ScreenshotUiEvent): ScreenshotUiState = when (event) {
        ScreenshotUiEvent.Arm -> ScreenshotUiState.Armed
        ScreenshotUiEvent.CaptureStarted -> if (state == ScreenshotUiState.Armed) {
            ScreenshotUiState.Capturing
        } else {
            state
        }
        is ScreenshotUiEvent.CaptureCompleted -> when (val result = event.result) {
            is ScreenshotResult.Saved -> ScreenshotUiState.Preview(
                displayName = result.displayName,
                uri = result.uri,
                location = result.location,
            )
            is ScreenshotResult.Failed -> ScreenshotUiState.Failed(result.reason)
        }
        is ScreenshotUiEvent.TimeElapsed -> if (state is ScreenshotUiState.Preview && !state.expanded) {
            val remaining = (state.remainingMillis - event.millis).coerceAtLeast(0)
            // 倒计时归零就是"卡片消失"这一件事：状态回到 Idle，要不要提示保存路径由
            // ViewModel 依「倒计时到期」这条原因决定（规则见 screenshotExpiredNaturally）。
            if (remaining == 0L) ScreenshotUiState.Idle else state.copy(remainingMillis = remaining)
        } else {
            state
        }
        is ScreenshotUiEvent.ExpandChanged -> if (state is ScreenshotUiState.Preview) {
            // 展开需要一张真实的图（Preview 的 init 也兜着这条不变式）。
            state.copy(expanded = event.expanded && state.uri.isNotBlank())
        } else {
            state
        }
        ScreenshotUiEvent.Close, ScreenshotUiEvent.MediaChanged -> ScreenshotUiState.Idle
    }
}

/**
 * 倒计时是否**自然到期**（而不是被关闭按钮 / 删除 / 换媒体打断）。
 *
 * 只有自然到期才提示保存路径：关闭与删除都是用户主动放弃这张卡，再弹一次"已保存到…"
 * 是噪音；换媒体同理，那条截图已经不属于当前上下文了。
 *
 * 注意必须用**更新时间之前**的状态来判断：`TimeElapsed` 把剩余时间减到 0 时状态已经变成
 * Idle，从结果态反推不出原因。
 */
fun screenshotExpiredNaturally(state: ScreenshotUiState, event: ScreenshotUiEvent): Boolean =
    state is ScreenshotUiState.Preview &&
        !state.expanded &&
        event is ScreenshotUiEvent.TimeElapsed &&
        event.millis >= state.remainingMillis

interface PictureInPictureGateway {
    fun isAvailable(): Boolean
    fun enter(): Boolean
}

enum class AudioFocusEvent {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    DUCK,
    HEADSET_DISCONNECTED,
}

fun interface AudioFocusGateway {
    fun observe(listener: (AudioFocusEvent) -> Unit): AutoCloseable
}
