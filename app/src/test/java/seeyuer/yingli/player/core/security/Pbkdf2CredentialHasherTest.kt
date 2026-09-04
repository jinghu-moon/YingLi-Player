package seeyuer.yingli.player.core.security

import java.security.SecureRandom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pbkdf2CredentialHasherTest {
    @Test
    fun `credential verifies correct secret and rejects wrong secret`() {
        val hasher = Pbkdf2CredentialHasher(120_000, SecureRandom(byteArrayOf(1, 2, 3)))
        val credential = hasher.create("123456".toCharArray(), 1)

        assertTrue(hasher.verify("123456".toCharArray(), credential))
        assertFalse(hasher.verify("654321".toCharArray(), credential))
    }
}
