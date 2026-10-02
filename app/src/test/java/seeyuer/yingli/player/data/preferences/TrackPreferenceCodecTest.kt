package seeyuer.yingli.player.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.VideoScaleMode

class TrackPreferenceCodecTest {
    @Test
    fun `round trips every stored field`() {
        val preference = TrackPreference(
            audioLanguage = "ja",
            subtitleLanguage = "zh-Hans",
            subtitlesEnabled = true,
            speed = PlaybackSpeed.of(1.5f),
            scaleMode = VideoScaleMode.FILL,
            rotation = VideoRotation.DEGREES_270,
        )

        assertEquals(preference, TrackPreferenceCodec.parse(TrackPreferenceCodec.serialize(preference)))
    }

    @Test
    fun `records written before rotation existed fall back to no rotation`() {
        val legacy = "ja,zh-Hans,true,1.5,FILL"

        val parsed = TrackPreferenceCodec.parse(legacy)

        assertEquals(VideoRotation.DEGREES_0, parsed.rotation)
        assertEquals(VideoScaleMode.FILL, parsed.scaleMode)
        assertEquals("ja", parsed.audioLanguage)
    }

    @Test
    fun `unknown enum names fall back per field instead of dropping the record`() {
        val corrupted = "ja,,false,1.0,STRETCHED,FLIPPED"

        val parsed = TrackPreferenceCodec.parse(corrupted)

        assertEquals("ja", parsed.audioLanguage)
        assertEquals(VideoScaleMode.FIT, parsed.scaleMode)
        assertEquals(VideoRotation.DEGREES_0, parsed.rotation)
    }

    @Test
    fun `illegal speed falls back to normal`() {
        val parsed = TrackPreferenceCodec.parse(",,false,7.5,FIT,DEGREES_90")

        assertEquals(PlaybackSpeed.Normal, parsed.speed)
        assertEquals(VideoRotation.DEGREES_90, parsed.rotation)
    }

    @Test
    fun `empty record parses to defaults`() {
        assertEquals(TrackPreference(), TrackPreferenceCodec.parse(""))
    }
}
