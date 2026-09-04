package seeyuer.yingli.player.app.settings

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.domain.settings.BackupCodec
import seeyuer.yingli.player.domain.settings.SettingsDocumentGateway

class AndroidSettingsDocumentGateway(
    context: Context,
    private val dispatchers: AppDispatchers,
) : SettingsDocumentGateway {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(uri: String): String? = withContext(dispatchers.io) {
        runCatching {
            resolver.openInputStream(Uri.parse(uri))?.bufferedReader()?.use { reader ->
                val document = StringBuilder()
                val buffer = CharArray(BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    document.append(buffer, 0, count)
                    if (document.length > BackupCodec.MAX_DOCUMENT_CHARS) return@use null
                }
                document.toString()
            }
        }.getOrNull()
    }

    override suspend fun write(uri: String, content: String): Boolean = withContext(dispatchers.io) {
        runCatching {
            resolver.openOutputStream(Uri.parse(uri), "wt")?.bufferedWriter()?.use { writer ->
                content.chunkedSequence(BUFFER_SIZE).forEach(writer::write)
            } != null
        }.getOrDefault(false)
    }

    private companion object {
        const val BUFFER_SIZE = 8_192
    }
}
