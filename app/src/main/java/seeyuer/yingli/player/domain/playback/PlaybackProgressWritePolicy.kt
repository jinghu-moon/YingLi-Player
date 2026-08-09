package seeyuer.yingli.player.domain.playback

enum class ProgressWriteReason {
    PERIODIC,
    PAUSED,
    STOPPED,
    BACKGROUNDED,
    ENDED,
}

data class PlaybackProgressSample(
    val positionMillis: Long,
    val durationMillis: Long?,
    val incognito: Boolean,
)

sealed interface ProgressWriteDecision {
    data object Skip : ProgressWriteDecision
    data class Write(val positionMillis: Long, val completed: Boolean) : ProgressWriteDecision
}

class PlaybackProgressWritePolicy(
    private val intervalMillis: Long = 5_000,
) {
    private var lastWriteEpochMillis: Long? = null
    private var lastWrite: ProgressWriteDecision.Write? = null

    init {
        require(intervalMillis > 0)
    }

    fun reset() {
        lastWriteEpochMillis = null
        lastWrite = null
    }

    fun evaluate(
        sample: PlaybackProgressSample,
        reason: ProgressWriteReason,
        nowEpochMillis: Long,
    ): ProgressWriteDecision {
        if (sample.incognito) return ProgressWriteDecision.Skip
        val duration = sample.durationMillis?.coerceAtLeast(0)
        val position = sample.positionMillis.coerceIn(0, duration ?: Long.MAX_VALUE)
        val candidate = ProgressWriteDecision.Write(
            positionMillis = if (reason == ProgressWriteReason.ENDED) duration ?: position else position,
            completed = reason == ProgressWriteReason.ENDED,
        )
        if (candidate == lastWrite) return ProgressWriteDecision.Skip
        if (reason == ProgressWriteReason.PERIODIC) {
            val previous = lastWriteEpochMillis
            if (previous != null && nowEpochMillis - previous < intervalMillis) return ProgressWriteDecision.Skip
        }
        lastWriteEpochMillis = nowEpochMillis
        lastWrite = candidate
        return candidate
    }
}
