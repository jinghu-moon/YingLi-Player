package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AB 命令回执的线格式：拒绝码以**枚举名**跨会话边界传递，编解码只有这一对实现。
 *
 * 为什么要单独测它：设点被拒的回执是"用户能不能看到提示"的唯一依据，编解码错一位
 * （例如某端自己写一套 when 映射）就会退化成静默失败或错误的提示文案。
 */
class AbLoopSessionCommandsTest {
    @Test
    fun `every rejection round trips through the wire format`() {
        for (rejection in PlaybackCommandRejection.entries) {
            val encoded = AbLoopSessionCommands.encodeRejection(rejection)
            assertEquals(rejection.name, encoded)
            assertEquals(rejection, AbLoopSessionCommands.decodeRejection(encoded))
        }
    }

    @Test
    fun `unknown and missing rejection codes decode to null so the caller can fall back`() {
        assertNull(AbLoopSessionCommands.decodeRejection(null))
        assertNull(AbLoopSessionCommands.decodeRejection(""))
        assertNull(AbLoopSessionCommands.decodeRejection("SOMETHING_ELSE"))
    }
}
