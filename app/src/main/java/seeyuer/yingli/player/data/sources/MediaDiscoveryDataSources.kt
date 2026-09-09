package seeyuer.yingli.player.data.sources

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
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
                val relativePath = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
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
                                relativePath = cursor.stringOrNull(relativePath)?.normalizeRelativePath(),
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
    private fun android.database.Cursor.stringOrNull(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)

    private companion object {
        val COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.MediaColumns.RELATIVE_PATH,
        )
    }
}

fun interface MediaStoreQuery {
    fun query(projection: Array<String>, sortOrder: String): android.database.Cursor?
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

    private companion object { const val MAX_DEPTH = 64 }
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
