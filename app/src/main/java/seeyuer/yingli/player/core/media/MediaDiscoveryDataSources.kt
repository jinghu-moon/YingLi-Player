package seeyuer.yingli.player.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.model.media.*

class MediaStoreDiscoveryDataSource(
    private val query: MediaStoreQuery,
    private val dispatchers: AppDispatchers,
) : MediaDiscoveryDataSource {
    constructor(resolver: ContentResolver, dispatchers: AppDispatchers) : this(
        MediaStoreQuery { projection, sortOrder ->
            resolver.query(COLLECTION, projection, null, null, sortOrder)
        },
        dispatchers,
    )

    constructor(context: Context, dispatchers: AppDispatchers) : this(
        context.applicationContext.contentResolver,
        dispatchers,
    )

    override val mode = MediaSourceMode.MEDIA_STORE

    override fun discover(source: MediaSource): Flow<MediaDiscoveryEvent> = flow {
        val seen = mutableSetOf<String>()
        try {
            query.query(PROJECTION, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { cursor ->
                val id = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val name = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val mime = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val size = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val modified = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val duration = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val width = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val height = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                if (listOf(id, name, mime, size, modified).any { it < 0 }) {
                    emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.MALFORMED_ENTRY, true)))
                    return@use
                }
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    try {
                        val mediaId = cursor.getLong(id)
                        val uri = ContentUris.withAppendedId(COLLECTION, mediaId).toString()
                        val mimeType = cursor.getString(mime)
                        val fileName = cursor.getString(name)
                        if (uri in seen || !mimeType.startsWith("video/") || fileName.isBlank()) continue
                        seen += uri
                        emit(MediaDiscoveryEvent.Candidate(MediaCandidate(
                            source.id,
                            MediaIdentityEvidence(
                                MediaUri(uri), source.volumeId, mediaId.toString(), fileName,
                                cursor.getLong(size).coerceAtLeast(0), cursor.getLong(modified).coerceAtLeast(0) * 1000,
                                cursor.longOrNull(duration), cursor.intOrNull(width), cursor.intOrNull(height),
                            ),
                            mimeType,
                        )))
                    } catch (_: RuntimeException) {
                        emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.MALFORMED_ENTRY, true)))
                    }
                }
            }
        } catch (_: SecurityException) {
            emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.PERMISSION, true)))
        }
    }.flowOn(dispatchers.io)

    private fun android.database.Cursor.longOrNull(index: Int): Long? = if (index < 0 || isNull(index)) null else getLong(index)
    private fun android.database.Cursor.intOrNull(index: Int): Int? = if (index < 0 || isNull(index) || getInt(index) <= 0) null else getInt(index)

    private companion object {
        val COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
        )
    }
}

fun interface MediaStoreQuery {
    fun query(projection: Array<String>, sortOrder: String): android.database.Cursor?
}

class SafTreeDiscoveryDataSource(
    private val documents: SafDocumentGateway,
    private val dispatchers: AppDispatchers,
    private val metadataReader: MediaMetadataReader = MediaMetadataReader.None,
) : MediaDiscoveryDataSource {
    constructor(context: Context, dispatchers: AppDispatchers) : this(
        DocumentFileGateway(context),
        dispatchers,
        AndroidMediaMetadataReader(context),
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
        val pending = ArrayDeque<Pair<SafDocumentNode, Int>>()
        val visited = mutableSetOf<String>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (document, depth) = pending.removeFirst()
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
            children.forEach { child ->
                val name = child.name.orEmpty()
                if (!source.includeHidden && name.startsWith('.')) return@forEach
                if (child.isDirectory) {
                    pending.add(child to depth + 1)
                } else if (child.mimeType?.startsWith("video/") == true && name.isNotBlank()) {
                    val uri = child.uri
                    if (visited.add(uri)) {
                        val mediaUri = MediaUri(uri)
                        val metadata = readMetadata(mediaUri)
                        emit(MediaDiscoveryEvent.Candidate(MediaCandidate(
                            source.id,
                            MediaIdentityEvidence(
                                mediaUri, source.volumeId, uri.substringAfterLast('/'), name,
                                child.length.coerceAtLeast(0), child.lastModified.coerceAtLeast(0),
                                metadata.durationMillis, metadata.width, metadata.height,
                            ),
                            child.mimeType ?: "video/*",
                        )))
                    }
                }
            }
        }
    }.flowOn(dispatchers.io)

    private fun readMetadata(uri: MediaUri): VideoMetadata =
        runCatching { metadataReader.read(uri) }.getOrDefault(VideoMetadata())

    private companion object { const val MAX_DEPTH = 64 }
}

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

class AllFilesDiscoveryDataSource(
    private val dispatchers: AppDispatchers,
) : MediaDiscoveryDataSource {
    override val mode = MediaSourceMode.ALL_FILES

    override fun discover(source: MediaSource): Flow<MediaDiscoveryEvent> = flow {
        val root = runCatching { File(java.net.URI(source.rootUri.value)) }.getOrNull()
        if (root == null || !root.canRead()) {
            emit(MediaDiscoveryEvent.Failure(ScanFailure(source.id, ScanFailureKind.SOURCE_OFFLINE, true)))
            return@flow
        }
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (file, depth) = pending.removeFirst()
            if (depth > MAX_DEPTH || (!source.includeHidden && file.isHidden)) continue
            if (file.isDirectory) {
                file.listFiles()?.forEach { pending.add(it to depth + 1) }
            } else {
                val mime = file.videoMimeType() ?: continue
                // Indexing discovers files only. Expensive container parsing belongs to lazy enrichment.
                val mediaUri = MediaUri(Uri.fromFile(file).toString())
                emit(MediaDiscoveryEvent.Candidate(MediaCandidate(
                    source.id,
                    MediaIdentityEvidence(
                        mediaUri, source.volumeId, null, file.name,
                        file.length().coerceAtLeast(0), file.lastModified().coerceAtLeast(0),
                        null, null, null,
                    ),
                    mime,
                )))
            }
        }
    }.flowOn(dispatchers.io)

    private fun File.videoMimeType(): String? {
        val extension = extension.lowercase()
        val resolved = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return resolved?.takeIf { it.startsWith("video/") }
    }

    private companion object { const val MAX_DEPTH = 64 }
}
