package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AB 循环在**会话侧**的只读状态：区间 + 循环次数。
 *
 * 为什么它必须和 [AbLoopState] 分开：`AbLoopState` 是"用户设的区间"，不变量只有 `pointA < pointB`；
 * 循环次数属于会话（会随清除/切媒体归零），不是区间的一部分，把它塞进 `AbLoopState`
 * 会让"设点"这种纯函数也要顺手管计数。
 *
 * **唯一写入者是会话 runtime**（`PlaybackSessionRuntime`）；客户端只读它做投影。
 * 这一条是"AB 状态只有一份"的落点：bridge 不再有自己的 `abLoop` 字段，ViewModel 也不再有自己的计数。
 */
data class AbLoopSession(
    val state: AbLoopState = AbLoopState(),
    val loopCount: Long = 0,
) {
    init {
        require(loopCount >= 0)
    }

    val active: Boolean get() = state.active

    companion object {
        val EMPTY = AbLoopSession()
    }
}

/**
 * 会话侧 AB 状态的读口：实现方即权威持有者，读侧（客户端投影）只订阅它。
 *
 * 为什么不做成"进程内共享注册表"：AB 状态的传输已经走 MediaSession
 *（写=自定义命令、读=session extras），再放一份进程内共享状态就等于又开一条同步通道 ——
 * 那正是"三份状态互相覆盖"的起点。谁持有、怎么传，各只有一条路。
 */
interface AbLoopSessionStore {
    val abLoop: StateFlow<AbLoopSession>
}

/** 默认实现：一个进程内的可变状态，由会话 runtime 持有并写入。 */
class MutableAbLoopSessionStore : AbLoopSessionStore {
    private val mutableSession = MutableStateFlow(AbLoopSession.EMPTY)
    override val abLoop: StateFlow<AbLoopSession> = mutableSession.asStateFlow()

    /** 只在会话 runtime 内调用。 */
    fun publish(session: AbLoopSession) {
        mutableSession.value = session
    }
}
