package seeyuer.yingli.player.data.filesystem

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.FileOperationFailure
import seeyuer.yingli.player.domain.library.FileOperationGateway
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.FileOperationTarget
import seeyuer.yingli.player.domain.library.TrashEntry

class AndroidFileOperationGateway(
    context: Context,
    private val dispatchers: AppDispatchers,
) : FileOperationGateway {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override suspend fun rename(target: FileOperationTarget, newName: String): FileOperationResult = onIo {
        if (!newName.isSafeFileName()) return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.NAME_CONFLICT)
        val source = Uri.parse(target.sourceUri.value)
        when (source.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                val document = DocumentFile.fromSingleUri(context, source)
                    ?: return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
                if (document.parentFile?.findFile(newName) != null) {
                    return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.NAME_CONFLICT)
                }
                if (document.renameTo(newName)) {
                    FileOperationResult.Success(MediaUri(document.uri.toString()))
                } else {
                    FileOperationResult.RecoverableFailure(FileOperationFailure.READ_ONLY)
                }
            }
            ContentResolver.SCHEME_FILE -> renameFile(source, newName)
            else -> FileOperationResult.RecoverableFailure(FileOperationFailure.PERMISSION_REQUIRED)
        }
    }

    override suspend fun move(target: FileOperationTarget, destination: MediaUri): FileOperationResult = onIo {
        val source = Uri.parse(target.sourceUri.value)
        val destinationUri = Uri.parse(destination.value)
        when {
            source.scheme == ContentResolver.SCHEME_FILE && destinationUri.scheme == ContentResolver.SCHEME_FILE ->
                moveFile(source, destinationUri)
            source.scheme == ContentResolver.SCHEME_CONTENT && destinationUri.scheme == ContentResolver.SCHEME_CONTENT ->
                copyContentThenDelete(source, destinationUri)
            else -> FileOperationResult.RecoverableFailure(FileOperationFailure.VOLUME_OFFLINE)
        }
    }

    override suspend fun trash(target: FileOperationTarget): FileOperationResult = onIo {
        val source = Uri.parse(target.sourceUri.value)
        if (source.scheme != ContentResolver.SCHEME_FILE) {
            return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.PERMISSION_REQUIRED)
        }
        val file = source.path?.let(::File)
            ?: return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
        if (!file.exists()) return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
        val trashDirectory = File(file.parentFile, ".Trash/YingLi")
        if (!trashDirectory.exists() && !trashDirectory.mkdirs()) {
            return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.READ_ONLY)
        }
        val targetFile = uniqueTarget(trashDirectory, file.name)
        if (file.renameTo(targetFile)) success(targetFile) else FileOperationResult.RecoverableFailure(FileOperationFailure.READ_ONLY)
    }

    override suspend fun restore(entry: TrashEntry): FileOperationResult = onIo {
        val trashed = entry.trashedUri?.value?.let(Uri::parse)
            ?: return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
        val original = Uri.parse(entry.originalUri.value)
        if (trashed.scheme == ContentResolver.SCHEME_FILE && original.scheme == ContentResolver.SCHEME_FILE) {
            val sourceFile = trashed.path?.let(::File)
                ?: return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
            val originalFile = original.path?.let(::File)
                ?: return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.TARGET_MISSING)
            if (originalFile.exists()) return@onIo FileOperationResult.RecoverableFailure(FileOperationFailure.NAME_CONFLICT)
            if (sourceFile.renameTo(originalFile)) success(originalFile)
            else FileOperationResult.RecoverableFailure(FileOperationFailure.READ_ONLY)
        } else {
            FileOperationResult.RecoverableFailure(FileOperationFailure.PERMISSION_REQUIRED)
        }
    }

    override suspend fun purge(entry: TrashEntry): FileOperationResult = onIo {
        val value = entry.trashedUri ?: entry.originalUri
        val uri = Uri.parse(value.value)
        val removed = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.delete() == true
            ContentResolver.SCHEME_CONTENT -> resolver.delete(uri, null, null) > 0
            else -> false
        }
        if (removed) FileOperationResult.Success(value)
        else FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
    }

    private fun renameFile(source: Uri, newName: String): FileOperationResult {
        val file = source.path?.let(::File)
            ?: return FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
        val target = File(file.parentFile, newName)
        if (target.exists()) return FileOperationResult.RecoverableFailure(FileOperationFailure.NAME_CONFLICT)
        return if (file.renameTo(target)) success(target)
        else FileOperationResult.RecoverableFailure(FileOperationFailure.READ_ONLY)
    }

    private fun moveFile(source: Uri, destination: Uri): FileOperationResult {
        val file = source.path?.let(::File)
            ?: return FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
        val directory = destination.path?.let(::File)
            ?: return FileOperationResult.RecoverableFailure(FileOperationFailure.TARGET_MISSING)
        if (!directory.isDirectory) return FileOperationResult.RecoverableFailure(FileOperationFailure.TARGET_MISSING)
        val target = File(directory, file.name)
        if (target.exists()) return FileOperationResult.RecoverableFailure(FileOperationFailure.NAME_CONFLICT)
        return if (file.renameTo(target)) success(target)
        else FileOperationResult.RecoverableFailure(FileOperationFailure.VOLUME_OFFLINE)
    }

    private fun copyContentThenDelete(source: Uri, destination: Uri): FileOperationResult {
        val sourceDocument = DocumentFile.fromSingleUri(context, source)
            ?: return FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING)
        val directory = DocumentFile.fromTreeUri(context, destination)
            ?: return FileOperationResult.RecoverableFailure(FileOperationFailure.TARGET_MISSING)
        val name = sourceDocument.name ?: "video"
        if (directory.findFile(name) != null) return FileOperationResult.RecoverableFailure(FileOperationFailure.NAME_CONFLICT)
        val target = directory.createFile(sourceDocument.type ?: "video/*", name)
            ?: return FileOperationResult.RecoverableFailure(FileOperationFailure.READ_ONLY)
        try {
            resolver.openInputStream(source).use { input ->
                resolver.openOutputStream(target.uri, "w").use { output ->
                    if (input == null || output == null) throw FileNotFoundException()
                    input.copyTo(output)
                }
            }
        } catch (cancelled: CancellationException) {
            resolver.delete(target.uri, null, null)
            throw cancelled
        } catch (_: IOException) {
            resolver.delete(target.uri, null, null)
            return FileOperationResult.RecoverableFailure(FileOperationFailure.PARTIAL)
        }
        if (!sourceDocument.delete()) {
            resolver.delete(target.uri, null, null)
            return FileOperationResult.RecoverableFailure(FileOperationFailure.PARTIAL)
        }
        return FileOperationResult.Success(MediaUri(target.uri.toString()))
    }

    private suspend fun onIo(block: () -> FileOperationResult): FileOperationResult = try {
        withContext(dispatchers.io) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        FileOperationResult.RecoverableFailure(FileOperationFailure.PERMISSION_REQUIRED)
    } catch (_: Exception) {
        FileOperationResult.RecoverableFailure(FileOperationFailure.UNKNOWN)
    }

    private fun success(file: File) = FileOperationResult.Success(MediaUri(Uri.fromFile(file).toString()))
    private fun String.isSafeFileName() = isNotBlank() && length <= 255 && none { it == '/' || it == '\\' || it == '\u0000' }
    private fun uniqueTarget(directory: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(directory, name)
        var suffix = 1
        while (candidate.exists()) candidate = File(directory, "$base ($suffix++)$extension")
        return candidate
    }
}
