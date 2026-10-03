package seeyuer.yingli.player.engine.media3

import android.os.Bundle
import androidx.media3.session.SessionResult
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbLoopSessionCommands
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection

/**
 * AB 设点命令的**回执线格式**：服务端（`YingLiPlaybackService.onCustomCommand`）与客户端
 *（`Media3PlaybackController.requestSetAbPoint`）共用这一对转换，两端各写一套映射会让
 * "同一个拒绝码在两边含义漂移"。
 *
 * 放在 Media3 适配层的原因：它需要 `SessionResult` 这个 Media3 类型，而领域层不暴露 Media3 类型；
 * 服务层与控制器都依赖这一层，方向不会反转。
 *
 * 为什么用 `SessionResult` 而不是 session extras：extras 是**状态**通道（区间 + 计数，
 * 每次覆盖），而设点被拒是**一次性命令回执**。`SessionResult` 正是 Media3 为"自定义命令的
 * 执行结果"提供的通道，结果码与 extras 都会原样回到控制器
 *（`MediaControllerStub.onSessionResult` → `SessionResult.fromBundle`）。
 *
 * 结果码只做**粗分类**（成功 / 失败），精确的拒绝原因放在 extras 里用枚举名传递。
 *
 * 为什么所有拒绝共用一个 `RESULT_ERROR_UNKNOWN` 而不按原因细分：`SessionResult` 接受的
 * 是一组**由 Media3 定死的常量**，装不下我们自己的拒绝原因；真正的原因已经由 extras
 * （`ARG_REJECTION`）逐字带回，客户端解码时**extras 优先**。按原因硬塞非 Media3 常量
 * 既过不了静态检查，也没有多带回任何信息。
 *
 * `RESULT_ERROR_UNKNOWN` 与 `SessionResult` 构造参数注解里列出的 `SessionError.ERROR_UNKNOWN`
 * 是同一个值，只是在 `SessionResult` 这个类上重新声明了一份；lint 的 `WrongConstant`
 * 清单没有把这份重声明算进去，所以这里显式抑制。**不能**改用 `SessionResult(SessionError(...))`
 * 那条重载：它会把拒绝码所在的 extras 吞掉，客户端就只剩结果码可读 —— 真机用例
 * `setPointOnUnavailableMediaReturnsTheSessionRejection` 会因此收到 `INVALID_STATE`
 * 而不是 `AB_UNAVAILABLE`（本文件已经踩过一次，故记录在此）。
 */
@Suppress("WrongConstant")
internal fun AbLoopCommandOutcome.toSessionResult(): SessionResult = when (this) {
    AbLoopCommandOutcome.Applied -> SessionResult(SessionResult.RESULT_SUCCESS)
    is AbLoopCommandOutcome.Rejected -> SessionResult(
        SessionResult.RESULT_ERROR_UNKNOWN,
        Bundle().apply {
            putString(AbLoopSessionCommands.ARG_REJECTION, AbLoopSessionCommands.encodeRejection(rejection))
        },
    )
}

/**
 * 把回执解码回会话侧的语义结果。
 *
 * 两条不变量：
 * 1. **只有 `RESULT_SUCCESS` 才算成功** —— 任何非成功的结果码都必须变成一次可见的拒绝，
 *    客户端不允许把"不知道发生了什么"当成"设置成功"；
 * 2. **extras 优先、结果码兜底** —— 精确原因在 extras 里；只有它缺失时才按结果码回退
 *    （连接已断 → `NOT_CONNECTED`，其余 → `INVALID_STATE` 这个通用原因），绝不退化成静默。
 */
internal fun SessionResult.toAbLoopCommandOutcome(): AbLoopCommandOutcome {
    if (resultCode == SessionResult.RESULT_SUCCESS) return AbLoopCommandOutcome.Applied
    val rejection = AbLoopSessionCommands.decodeRejection(
        extras.getString(AbLoopSessionCommands.ARG_REJECTION),
    ) ?: rejectionFromResultCode(resultCode)
    return AbLoopCommandOutcome.Rejected(rejection)
}

private fun rejectionFromResultCode(resultCode: Int): PlaybackCommandRejection = when (resultCode) {
    SessionResult.RESULT_ERROR_SESSION_DISCONNECTED -> PlaybackCommandRejection.NOT_CONNECTED
    else -> PlaybackCommandRejection.INVALID_STATE
}
