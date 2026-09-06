package seeyuer.yingli.player.core.common

import java.util.UUID

fun interface IdGenerator {
    fun newId(): String
}

object UuidGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
