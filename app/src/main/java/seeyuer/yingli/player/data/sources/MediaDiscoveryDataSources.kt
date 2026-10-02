package seeyuer.yingli.player.data.sources

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque
import java.io.File
import android.webkit.MimeTypeMap
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.*
import seeyuer.yingli.player.domain.catalog.MediaDiscoveryDataSource
import seeyuer.yingli.player.domain.catalog.MediaDiscoveryEvent

class MediaStoreDiscoveryDataSource(
    private val query: MediaStoreQuery,
    private val dispatchers: AppDispatchers,
    private val metadataReader: MediaMetadataReader = MediaMetadataReader.None,
    private val nomediaScanner: NomediaDiscoveryDataSource? = null,
) : MediaDiscoveryDataSource {
    constructor(resolver: ContentResolver, dispatchers: AppDispatchers) : this(
        MediaStoreQuery { collection, projection, sortOrder ->
            resolver.query(collection, projection, null, null, sortOrder)
        },
        dispatchers,
    )

    constructor(context: Context, dispatchers: AppDispatchers) : this(
        MediaStoreQuery { collection, projection, sortOrder ->
            context.applicationContext.contentResolver.query(collection, projection, null, null, sortOrder)
        },
        dispatchers,
        AndroidMediaMetadataReader(context),
        NomediaDiscoveryDataSource(AndroidMediaMetadataReader(context), dispatchers),
    )

    override val mode = MediaSourceMode.MEDIA_STORE

    override fun discover(source: MediaSource): Flow<MediaDiscoveryEvent> = flow {
        val seen = mutableSetOf<String>()
        try {
            val collection = if (source.includeHidden) FILE_COLLECTION else VIDEO_COLLECTION
            query.query(collection, PROJECTION, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { cursor ->
                val id = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val name = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val mime = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val size = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val modified = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val duration = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val width = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val height = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val relativePath = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (listOf(id, name, mime, size, modified).any { it < 0 }) {
                    emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.MALFORMED_ENTRY, true)))
                    return@use
                }
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    try {
                        val mediaId = cursor.getLong(id)
                        val uri = ContentUris.withAppendedId(collection, mediaId).toString()
                        val mimeType = cursor.getString(mime)
                        val fileName = cursor.getString(name)
                        if (uri in seen || !mimeType.startsWith("video/") || fileName.isBlank()) continue
                        seen += uri
                        val mediaUri = MediaUri(uri)
                        val mediaStoreDuration = cursor.longOrNull(duration)
                        val mediaStoreWidth = cursor.intOrNull(width)
                        val mediaStoreHeight = cursor.intOrNull(height)
                        val fallbackMetadata = if (
                            mediaStoreDuration == null || mediaStoreWidth == null || mediaStoreHeight == null
                        ) {
                            runCatching { metadataReader.read(mediaUri) }.getOrDefault(VideoMetadata())
                        } else {
                            VideoMetadata()
                        }
                        emit(MediaDiscoveryEvent.Candidate(MediaCandidate(
                            source.id,
                            MediaIdentityEvidence(
                                mediaUri, source.volumeId, mediaId.toString(), fileName,
                                cursor.getLong(size).coerceAtLeast(0), cursor.getLong(modified).coerceAtLeast(0) * 1000,
                                mediaStoreDuration ?: fallbackMetadata.durationMillis,
                                mediaStoreWidth ?: fallbackMetadata.width,
                                mediaStoreHeight ?: fallbackMetadata.height,
                                relativePath = cursor.stringOrNull(relativePath)?.normalizeRelativePath(),
                            ),
                            mimeType,
                        )))
                    } catch (_: RuntimeException) {
                        emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.MALFORMED_ENTRY, true)))
                    }
                }
            }
            if (source.includeNomedia) {
                nomediaScanner?.discover(source)?.collect { event -> emit(event) }
            }
        } catch (_: SecurityException) {
            emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.PERMISSION, true)))
        }
    }.flowOn(dispatchers.io)

    private fun android.database.Cursor.longOrNull(index: Int): Long? = if (index < 0 || isNull(index)) null else getLong(index)
    private fun android.database.Cursor.intOrNull(index: Int): Int? = if (index < 0 || isNull(index) || getInt(index) <= 0) null else getInt(index)
    private fun android.database.Cursor.stringOrNull(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)

    private companion object {
        val VIDEO_COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val FILE_COLLECTION: Uri = MediaStore.Files.getContentUri("external")
        val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.MediaColumns.RELATIVE_PATH,
        )
    }
}

/** Discovers videos below directories containing a .nomedia marker. */
class NomediaDiscoveryDataSource(
    private val metadataReader: MediaMetadataReader,
    private val dispatchers: AppDispatchers,
) {
    fun discover(source: MediaSource): Flow<MediaDiscoveryEvent> = flow {
        val root = Environment.getExternalStorageDirectory()
        val pending = ArrayDeque<Pair<File, Boolean>>()
        pending.add(root to false)
        try {
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (directory, insideNomedia) = pending.removeFirst()
                val children = directory.listFiles() ?: continue
                val marked = children.any { it.isFile && it.name == NOMEDIA_FILE }
                if (marked && !source.includeNomedia) continue
                val childInsideNomedia = insideNomedia || marked
                children.forEach { child ->
                    if (child.name == NOMEDIA_FILE) return@forEach
                    if (child.isDirectory) {
                        if (child.name == ANDROID_DIRECTORY) return@forEach
                        if (!source.includeHidden && child.name.startsWith('.')) return@forEach
                        pending.add(child to childInsideNomedia)
                    } else if (childInsideNomedia && isVideo(child) &&
                        (source.includeHidden || !child.name.startsWith('.'))
                    ) {
                        val uri = Uri.fromFile(child).toString()
                        val metadata = runCatching { metadataReader.read(MediaUri(uri)) }
                            .getOrDefault(VideoMetadata())
                        val relativePath = child.parentFile?.relativeTo(root)?.path
                            ?.replace(File.separatorChar, '/')
                            ?.ifBlank { null }
                        emit(MediaDiscoveryEvent.Candidate(MediaCandidate(
                            source.id,
                            MediaIdentityEvidence(
                                MediaUri(uri), source.volumeId, child.absolutePath,
                                child.name, child.length().coerceAtLeast(0), child.lastModified().coerceAtLeast(0),
                                metadata.durationMillis, metadata.width, metadata.height,
                                relativePath = relativePath,
                            ),
                            mimeType(child) ?: "video/*",
                        )))
                    }
                }
            }
        } catch (_: SecurityException) {
            emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.PERMISSION, true)))
        }
    }.flowOn(dispatchers.io)

    private fun isVideo(file: File): Boolean = mimeType(file)?.startsWith("video/") == true

    private fun mimeType(file: File): String? = file.extension
        .takeIf(String::isNotBlank)
        ?.lowercase()
        ?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)

    private companion object {
        const val NOMEDIA_FILE = ".nomedia"
        const val ANDROID_DIRECTORY = "Android"
    }
}

fun interface MediaStoreQuery {
    fun query(collection: Uri, projection: Array<String>, sortOrder: String): android.database.Cursor?
}

class SafTreeDiscoveryDataSource(
    private val documents: SafDocumentGateway,
    private val dispatchers: AppDispatchers,
) : MediaDiscoveryDataSource {
    constructor(context: Context, dispatchers: AppDispatchers) : this(
        DocumentFileGateway(context),
        dispatchers,
    )

    override val mode = MediaSourceMode.SAF_TREE

    override fun discover(source: MediaSource): Flow<MediaDiscoveryEvent> = flow {
        val root = try {
            documents.root(source.rootUri.value)
        } catch (_: SecurityException) {
            null
        }
        if (root == null || !root.canRead) {
            emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.PERMISSION, true)))
            return@flow
        }
        val pending = ArrayDeque<Triple<SafDocumentNode, Int, String>>()
        val visited = mutableSetOf<String>()
        pending.add(Triple(root, 0, ""))
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (document, depth, relativePath) = pending.removeFirst()
            if (!visited.add(document.uri) || depth > MAX_DEPTH) continue
            val children = try {
                document.children()
            } catch (_: SecurityException) {
                emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.PERMISSION, true)))
                continue
            } catch (_: RuntimeException) {
                emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.IO, true)))
                continue
            }
            if (children.any { !it.isDirectory && it.name == NOMEDIA_FILE } && !source.includeNomedia) {
                continue
            }
            children.forEach { child ->
                val name = child.name.orEmpty()
                if (!source.includeHidden && name.startsWith('.')) return@forEach
                if (child.isDirectory) {
                    pending.add(Triple(child, depth + 1, relativePath.appendPath(name)))
                } else if (child.mimeType?.startsWith("video/") == true && name.isNotBlank()) {
                    val uri = child.uri
                    if (visited.add(uri)) {
                        val mediaUri = MediaUri(uri)
                        emit(MediaDiscoveryEvent.Candidate(MediaCandidate(
                            source.id,
                            MediaIdentityEvidence(
                                mediaUri, source.volumeId, uri.substringAfterLast('/'), name,
                                child.length.coerceAtLeast(0), child.lastModified.coerceAtLeast(0),
                                null, null, null,
                                relativePath = relativePath.ifBlank { null },
                            ),
                            child.mimeType ?: "video/*",
                        )))
                    }
                }
            }
        }
    }.flowOn(dispatchers.io)

    private companion object {
        const val MAX_DEPTH = 64
        const val NOMEDIA_FILE = ".nomedia"
    }
}

private fun String.normalizeRelativePath(): String = trim().trim('/').replace('\\', '/')
private fun String.appendPath(child: String): String = listOf(this, child).filter(String::isNotBlank).joinToString("/")

interface SafDocumentNode {
    val uri: String
    val name: String?
    val mimeType: String?
    val isDirectory: Boolean
    val canRead: Boolean
    val length: Long
    val lastModified: Long

    fun children(): List<SafDocumentNode>
}

fun interface SafDocumentGateway {
    fun root(treeUri: String): SafDocumentNode?
}

private class DocumentFileGateway(private val context: Context) : SafDocumentGateway {
    override fun root(treeUri: String): SafDocumentNode? =
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.let(::DocumentFileNode)
}

private class DocumentFileNode(private val document: DocumentFile) : SafDocumentNode {
    override val uri: String get() = document.uri.toString()
    override val name: String? get() = document.name
    override val mimeType: String? get() = document.type
    override val isDirectory: Boolean get() = document.isDirectory
    override val canRead: Boolean get() = document.canRead()
    override val length: Long get() = document.length()
    override val lastModified: Long get() = document.lastModified()
    override fun children(): List<SafDocumentNode> = document.listFiles().map(::DocumentFileNode)
}
