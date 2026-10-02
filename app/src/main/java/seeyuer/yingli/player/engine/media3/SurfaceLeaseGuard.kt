package seeyuer.yingli.player.engine.media3

import seeyuer.yingli.player.domain.playback.SurfaceLease
import seeyuer.yingli.player.domain.playback.SurfaceOwner

internal class SurfaceLeaseGuard {
    private var current: SurfaceLease? = null
    private var lastGeneration = 0L

    fun acquire(owner: SurfaceOwner, token: String): SurfaceLease {
        lastGeneration += 1L
        val lease = SurfaceLease(owner, lastGeneration, token)
        current = lease
        return lease
    }

    fun release(lease: SurfaceLease): Boolean {
        if (current != lease) return false
        current = null
        return true
    }

    fun clear() {
        current = null
    }
}
