package seeyuer.yingli.player.domain.playback

data class ResumePlaybackPolicy(
    val rewindMillis: Long = 3_000,
    val nearEndMillis: Long = 5_000,
    val nearEndFraction: Double = 0.95,
    val autoAdvance: Boolean = false,
) {
    init {
        require(rewindMillis >= 0)
        require(nearEndMillis >= 0)
        require(nearEndFraction in 0.0..1.0)
    }

    fun startPosition(
        savedPositionMillis: Long,
        durationMillis: Long?,
        completed: Boolean,
    ): Long {
        val saved = savedPositionMillis.coerceAtLeast(0)
        if (completed) return 0
        if (durationMillis != null && durationMillis > 0) {
            val clamped = saved.coerceAtMost(durationMillis)
            val nearEnd = durationMillis - clamped <= nearEndMillis ||
                clamped.toDouble() / durationMillis >= nearEndFraction
            if (nearEnd) return 0
        }
        return (saved - rewindMillis).coerceAtLeast(0)
    }
}
