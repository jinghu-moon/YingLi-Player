package seeyuer.yingli.player.data.security

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import seeyuer.yingli.player.data.room.VaultItemEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.security.ChunkedAeadRandomAccessReader
import seeyuer.yingli.player.core.security.SecureRandomAccessReader
import seeyuer.yingli.player.core.security.VaultCipher
import seeyuer.yingli.player.domain.security.KeyAccessResult
import seeyuer.yingli.player.domain.security.KeyManagementGateway
import seeyuer.yingli.player.domain.security.SecurePlaybackSource
import seeyuer.yingli.player.domain.security.VaultItem
import seeyuer.yingli.player.domain.security.VaultItemId
import seeyuer.yingli.player.domain.security.VaultOperationResult
import seeyuer.yingli.player.domain.security.VaultRepository

class AndroidVaultRepository(
    context: Context,
    database: YingLiDatabase,
    private val keys: KeyManagementGateway,
    private val cipher: VaultCipher,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers,
) : VaultRepository, SecurePlaybackSource {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val dao = database.vaultDao()
    private val root = File(applicationContext.noBackupFilesDir, VAULT_DIRECTORY)

    override val items: Flow<List<VaultItem>> = dao.observeItems().map { rows -> rows.map { it.toDomain() } }

    override suspend fun import(uri: String, removeSourceAfterVerification: Boolean): VaultOperationResult =
        withContext(dispatchers.io) {
            root.mkdirs()
            val parsed = Uri.parse(uri)
            val originalSize = resolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0 } ?: descriptor.parcelFileDescriptor.statSize.takeIf { it > 0 }
            } ?: return@withContext VaultOperationResult.Failure("SOURCE_SIZE_UNAVAILABLE")
            val key = availableKey() ?: return@withContext VaultOperationResult.Failure("VAULT_KEY_UNAVAILABLE")
            val id = VaultItemId(idGenerator.newId())
            val contentToken = "${id.value}.ylv"
            val metadataToken = "${id.value}.meta.ylv"
            val contentPart = File(root, "$contentToken.part")
            val metadataPart = File(root, "$metadataToken.part")
            val contentFile = File(root, contentToken)
            val metadataFile = File(root, metadataToken)
            try {
                resolver.openInputStream(parsed)?.use { input ->
                    FileOutputStream(contentPart).use { output ->
                        cipher.encrypt(input, output, originalSize, key.key, key.version)
                    }
                } ?: return@withContext VaultOperationResult.Failure("SOURCE_UNREADABLE")
                val metadata = encryptedMetadata(parsed)
                FileOutputStream(metadataPart).use { output ->
                    cipher.encrypt(
                        ByteArrayInputStream(metadata),
                        output,
                        metadata.size.toLong(),
                        key.key,
                        key.version,
                    )
                }
                metadata.fill(0)
                verify(contentPart, key.key)
                verify(metadataPart, key.key)
                check(contentPart.renameTo(contentFile)) { "CONTENT_COMMIT_FAILED" }
                check(metadataPart.renameTo(metadataFile)) { "METADATA_COMMIT_FAILED" }
                val item = VaultItem(
                    id,
                    contentToken,
                    metadataToken,
                    contentFile.length() + metadataFile.length(),
                    key.version,
                    clock.now().toEpochMilli(),
                )
                try {
                    dao.upsert(item.toEntity())
                } catch (error: Exception) {
                    contentFile.delete()
                    metadataFile.delete()
                    throw error
                }
                val sourceRemoved = removeSourceAfterVerification && runCatching {
                    resolver.delete(parsed, null, null) > 0
                }.getOrDefault(false)
                VaultOperationResult.Success(item, sourceRemoved)
            } catch (_: CancellationException) {
                contentPart.delete()
                metadataPart.delete()
                VaultOperationResult.Canceled
            } catch (_: Exception) {
                contentPart.delete()
                metadataPart.delete()
                contentFile.delete()
                metadataFile.delete()
                VaultOperationResult.Failure("VAULT_IMPORT_FAILED")
            }
        }

    override suspend fun export(itemId: VaultItemId, outputUri: String): VaultOperationResult =
        withContext(dispatchers.io) {
            val entity = dao.item(itemId.value) ?: return@withContext VaultOperationResult.Failure("VAULT_ITEM_NOT_FOUND")
            val item = entity.toDomain()
            val key = (keys.key(item.keyVersion) as? KeyAccessResult.Available)
                ?: return@withContext VaultOperationResult.Failure("VAULT_KEY_UNAVAILABLE")
            val source = File(root, item.encryptedContentToken)
            val target = Uri.parse(outputUri)
            try {
                source.inputStream().use { input ->
                    resolver.openOutputStream(target, "w")?.use { output -> cipher.decrypt(input, output, key.key) }
                        ?: return@withContext VaultOperationResult.Failure("OUTPUT_UNAVAILABLE")
                }
                VaultOperationResult.Success(item)
            } catch (_: CancellationException) {
                runCatching { resolver.delete(target, null, null) }
                VaultOperationResult.Canceled
            } catch (_: Exception) {
                runCatching { resolver.delete(target, null, null) }
                VaultOperationResult.Failure("VAULT_EXPORT_FAILED")
            }
        }

    override suspend fun delete(itemId: VaultItemId): Boolean = withContext(dispatchers.io) {
        val entity = dao.item(itemId.value) ?: return@withContext true
        val content = File(root, entity.encryptedContentToken)
        val metadata = File(root, entity.encryptedMetadataToken)
        val contentDeleted = !content.exists() || content.delete()
        val metadataDeleted = !metadata.exists() || metadata.delete()
        if (contentDeleted && metadataDeleted) {
            dao.delete(itemId.value)
            true
        } else {
            false
        }
    }

    override suspend fun open(itemId: VaultItemId): SecureRandomAccessReader? = withContext(dispatchers.io) {
        val item = dao.item(itemId.value) ?: return@withContext null
        val key = keys.key(item.keyVersion) as? KeyAccessResult.Available ?: return@withContext null
        val file = File(root, item.encryptedContentToken)
        val randomAccessFile = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return@withContext null
        try {
            ChunkedAeadRandomAccessReader(randomAccessFile, key.key)
        } catch (_: Exception) {
            randomAccessFile.close()
            null
        }
    }

    private suspend fun availableKey(): KeyAccessResult.Available? =
        (keys.current() as? KeyAccessResult.Available) ?: (keys.create() as? KeyAccessResult.Available)

    private fun encryptedMetadata(uri: Uri): ByteArray {
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0)
        }
        return buildJsonObject {
            put("displayName", displayName ?: "YingLi vault item")
            put("mimeType", resolver.getType(uri) ?: "application/octet-stream")
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun verify(file: File, key: javax.crypto.SecretKey) {
        file.inputStream().use { input -> cipher.decrypt(input, DiscardingOutputStream, key) }
    }

    private fun VaultItemEntity.toDomain() = VaultItem(
        VaultItemId(id), encryptedContentToken, encryptedMetadataToken, encryptedBytes, keyVersion, createdAtEpochMillis,
    )

    private fun VaultItem.toEntity() = VaultItemEntity(
        id.value, encryptedContentToken, encryptedMetadataToken, encryptedBytes, keyVersion, createdAtEpochMillis,
    )

    private object DiscardingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    private companion object { const val VAULT_DIRECTORY = "vault" }
}
