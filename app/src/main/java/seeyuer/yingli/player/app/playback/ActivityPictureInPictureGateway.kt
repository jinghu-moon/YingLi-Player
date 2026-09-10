package seeyuer.yingli.player.app.playback

import android.app.Activity
import android.app.PictureInPictureParams
import android.util.Rational
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway

class ActivityPictureInPictureGateway(
    private val activity: Activity,
) : PictureInPictureGateway {
    override fun isAvailable(): Boolean =
        activity.packageManager.hasSystemFeature("android.software.picture_in_picture")

    override fun enter(): Boolean {
        if (!isAvailable()) return false
        val parameters = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(DEFAULT_ASPECT_WIDTH, DEFAULT_ASPECT_HEIGHT))
            .build()
        return activity.enterPictureInPictureMode(parameters)
    }

    private companion object {
        const val DEFAULT_ASPECT_WIDTH = 16
        const val DEFAULT_ASPECT_HEIGHT = 9
    }
}
