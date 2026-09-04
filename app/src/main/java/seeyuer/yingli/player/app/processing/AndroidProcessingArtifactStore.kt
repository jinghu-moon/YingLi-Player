package seeyuer.yingli.player.app.processing

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.foundation.IdGenerator
import seeyuer.yingli.player.domain.processing.ProcessingArtifact
import seeyuer.yingli.player.domain.processing.ProcessingArtifactStore
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.ProcessingOutput

class AndroidProcessingArtifactStore(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val idGenerator: IdGenerator,
) : ProcessingArtifactStore {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.cacheDir, TEMP_DIRECTORY)

    override suspend fun allocate(taskId: ProcessingTaskId, displayName: String): ProcessingArtifact =
        withContext(dispatchers.io) {
            val safeName = displayName.toSafeDisplayName()
            val taskDirectory = File(root, taskId.value).also { check(it.mkdirs() || it.isDirectory) }
            val file = File(taskDirectory, "${idGenerator.newId()}$PARTIAL_SUFFIX")
            check(file.createNewFile())
            ProcessingArtifact(idGenerator.newId(), file.absolutePath, safeName)
        }

    override suspend fun commit(artifact: ProcessingArtifact): ProcessingOutput = withContext(dispatchers.io) {
        val temporary = ownedFile(artifact.temporaryPath)
        check(temporary.isFile)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, artifact.displayName)
            put(MediaStore.Video.Media.MIME_TYPE, artifact.displayName.mimeType())
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/YingLi-Output")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val output = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(output, "w")).use { sink ->
                temporary.inputStream().use { source -> source.copyTo(sink) }
            }
            resolver.update(output, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            check(temporary.delete() || !temporary.exists())
            temporary.parentFile?.delete()
            ProcessingOutput(artifact.displayName, output.toString())
        } catch (failure: Exception) {
            resolver.delete(output, null, null)
            throw failure
        }
    }

    override suspend fun abort(artifact: ProcessingArtifact) = withContext(dispatchers.io) {
        val temporary = ownedFile(artifact.temporaryPath)
        check(temporary.delete() || !temporary.exists())
        temporary.parentFile?.delete()
        Unit
    }

    override suspend fun cleanupExpired(nowEpochMillis: Long): Int = withContext(dispatchers.io) {
        if (!root.isDirectory) return@withContext 0
        var removed = 0
        root.listFiles().orEmpty().forEach { taskDirectory ->
            taskDirectory.listFiles().orEmpty().forEach { file ->
                if (file.name.endsWith(PARTIAL_SUFFIX) && nowEpochMillis - file.lastModified() >= RETENTION_MILLIS) {
                    if (ownedFile(file.absolutePath).delete()) removed += 1
                }
            }
            taskDirectory.delete()
        }
        removed
    }

    private fun ownedFile(path: String): File {
        val candidate = File(path).canonicalFile
        val rootPath = root.canonicalFile.toPath()
        check(candidate.toPath().startsWith(rootPath))
        return candidate
    }

    private fun String.toSafeDisplayName(): String {
        val value = substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(MAX_DISPLAY_NAME_LENGTH)
        require(value.isNotBlank() && '.' in value)
        return value
    }

    private fun String.mimeType(): String = when (substringAfterLast('.').lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    private companion object {
        const val TEMP_DIRECTORY = "processing-artifacts"
        const val PARTIAL_SUFFIX = ".partial"
        const val MAX_DISPLAY_NAME_LENGTH = 120
        const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    }
}
