package seeyuer.yingli.player.data.security

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import android.content.Context
import androidx.annotation.OptIn
import kotlinx.coroutines.runBlocking
import seeyuer.yingli.player.domain.security.SecurePlaybackSource
import seeyuer.yingli.player.domain.security.VaultItemId
import seeyuer.yingli.player.core.security.SecureRandomAccessReader

@OptIn(UnstableApi::class)
class VaultDataSource(
    private val source: SecurePlaybackSource,
) : BaseDataSource(false) {
    private var reader: SecureRandomAccessReader? = null
    private var position = 0L
    private var remaining = 0L
    private var openedUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val itemId = dataSpec.uri.host?.takeIf(String::isNotBlank)
            ?: dataSpec.uri.lastPathSegment?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("INVALID_VAULT_URI")
        val openedReader = runBlocking { source.open(VaultItemId(itemId)) }
            ?: throw IllegalStateException("VAULT_SOURCE_UNAVAILABLE")
        if (dataSpec.position > openedReader.size) {
            openedReader.close()
            throw IllegalArgumentException("POSITION_OUT_OF_RANGE")
        }
        reader = openedReader
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) openedReader.size - position
        else minOf(dataSpec.length, openedReader.size - position)
        openedUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = reader?.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
            ?: C.RESULT_END_OF_INPUT
        if (count < 0) return C.RESULT_END_OF_INPUT
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        reader?.close()
        reader = null
        position = 0
        remaining = 0
        openedUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val source: SecurePlaybackSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(source)
    }
}

@OptIn(UnstableApi::class)
class VaultAwareDataSource private constructor(
    private val defaultSource: DataSource,
    private val vaultSource: DataSource,
) : DataSource {
    private var activeSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        defaultSource.addTransferListener(transferListener)
        vaultSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(activeSource == null)
        val selected = if (dataSpec.uri.scheme == VAULT_SCHEME) vaultSource else defaultSource
        activeSource = selected
        return try {
            selected.open(dataSpec)
        } catch (error: Exception) {
            runCatching { selected.close() }
            activeSource = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(activeSource) { "DATA_SOURCE_NOT_OPEN" }.read(buffer, offset, length)

    override fun getUri(): Uri? = activeSource?.uri

    override fun close() {
        val selected = activeSource ?: return
        activeSource = null
        selected.close()
    }

    class Factory(context: Context, secureSource: SecurePlaybackSource) : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context.applicationContext)
        private val vaultFactory = VaultDataSource.Factory(secureSource)

        override fun createDataSource(): DataSource = VaultAwareDataSource(
            defaultFactory.createDataSource(),
            vaultFactory.createDataSource(),
        )
    }

    private companion object { const val VAULT_SCHEME = "vault" }
}
