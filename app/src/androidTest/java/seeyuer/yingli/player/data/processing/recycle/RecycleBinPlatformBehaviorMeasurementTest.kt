package seeyuer.yingli.player.data.processing.recycle

import android.Manifest
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.Stage0EvidenceRecorder

/**
 * 阶段 0-B 第 2 / 3 / 4 / 5 / 8 项的取证探针（见 `docs/architecture/Organizing-Page-Function-Design.md` §14.1）。
 *
 * 回答的是四组**平台事实**，不是产品行为：
 * - U1：`MediaStore.DATE_EXPIRES` 在真机上是否可读、值是多少、移入回收站后是否才被赋值
 * - ②：本应用创建的媒体能否直接置 `IS_TRASHED`；其他应用创建的媒体走什么路径
 * - ③：`createTrashRequest()` / `createDeleteRequest()` 返回的 `PendingIntent` 是否成形
 * - U3：`createTrashRequest()` 是否也有 2000 URI 上限
 *
 * **本类不下产品断言**：它只把实测值写成 JSON。唯一硬断言是"测量本身有效"（探针行被创建）。
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinPlatformBehaviorMeasurementTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val resolver: ContentResolver = context.contentResolver
    private val ownPackage: String = context.packageName

    private val videoUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private val idProjection = arrayOf(MediaStore.MediaColumns._ID)

    private val factsProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.IS_TRASHED,
        MediaStore.MediaColumns.DATE_EXPIRES,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )

    // ── 探针 1：列清单与可读性 ────────────────────────────────────────────────

    @Test
    fun mediaStoreTrashColumnInventory() {
        val default = queryRows(factsProjection, defaultTrashArgs())
        val onlyTrashed = queryRows(factsProjection, trashArgs(MediaStore.MATCH_ONLY))
        val includeTrashed = queryRows(factsProjection, trashArgs(MediaStore.MATCH_INCLUDE))

        val defaultRows = default.getOrNull() ?: emptyList()
        val ownRows = defaultRows.filter { it["owner_package_name"] == ownPackage }
        val foreignRows = defaultRows.filter {
            val owner = it["owner_package_name"]
            owner != null && owner != ownPackage
        }
        val nullOwnerRows = defaultRows.filter { it["owner_package_name"] == null }

        Stage0EvidenceRecorder.record(
            context,
            "stage0-recycle-column-inventory-${configTag()}.json",
            """
            { "apiLevel": ${Build.VERSION.SDK_INT}
            , "permissions": ${permissionState()}
            , "device": ${str(Build.MODEL)}
            , "ownPackage": ${str(ownPackage)}
            , "projection": ${factsProjection.toList().jsonArray()}
            , "defaultQueryError": ${err(default.exceptionOrNull())}
            , "trashedOnlyQueryError": ${err(onlyTrashed.exceptionOrNull())}
            , "includeTrashedQueryError": ${err(includeTrashed.exceptionOrNull())}
            , "cursorColumns": ${CURSOR_COLUMNS.jsonArray()}
            , "firstRowRaw": ${firstRowRaw()}
            , "defaultCount": ${defaultRows.size}
            , "trashedOnlyCount": ${onlyTrashed.getOrNull()?.size ?: -1}
            , "includeTrashedCount": ${includeTrashed.getOrNull()?.size ?: -1}
            , "ownPackageCount": ${ownRows.size}
            , "foreignPackageCount": ${foreignRows.size}
            , "nullOwnerCount": ${nullOwnerRows.size}
            , "rowsWithNonZeroDateExpires": ${defaultRows.count { it["date_expires"] != null && it["date_expires"] != "0" }}
            , "distinctOwners": ${defaultRows.mapNotNull { it["owner_package_name"] }.distinct().sorted().jsonArray()}
            , "samples": ${defaultRows.take(8).map { it.toJson() }.joinToString(",", "[", "]")}
            , "trashedSamples": ${(onlyTrashed.getOrNull() ?: emptyList()).take(8).map { it.toJson() }.joinToString(",", "[", "]")}
            }
            """.trimIndent(),
        )
    }

    // ── 探针 2：本应用自有媒体的直接移入 / 恢复闭环 ───────────────────────────

    @Test
    fun ownMediaTrashRestoreCycle() = withProbeVideo { uri ->
        val before = readTrashFacts(uri)
        val trashed = setTrashed(uri, true)
        val afterTrash = readTrashFacts(uri)
        val visibleWhileTrashed = countInQuery(trashedQuery = false, uri = uri)
        val visibleInTrashedQuery = countInQuery(trashedQuery = true, uri = uri)
        val restored = setTrashed(uri, false)
        val afterRestore = readTrashFacts(uri)
        val visibleAfterRestore = countInQuery(trashedQuery = false, uri = uri)

        Stage0EvidenceRecorder.record(
            context,
            "stage0-recycle-own-media-cycle-${configTag()}.json",
            """
            { "apiLevel": ${Build.VERSION.SDK_INT}
            , "permissions": ${permissionState()}
            , "uri": ${str(uri.toString())}
            , "before": ${before.toJson()}
            , "setTrashedTrue": ${outcome(trashed)}
            , "afterTrash": ${afterTrash.toJson()}
            , "visibleInDefaultQueryWhileTrashed": $visibleWhileTrashed
            , "visibleInTrashedQueryWhileTrashed": $visibleInTrashedQuery
            , "setTrashedFalse": ${outcome(restored)}
            , "afterRestore": ${afterRestore.toJson()}
            , "visibleInDefaultQueryAfterRestore": $visibleAfterRestore
            }
            """.trimIndent(),
        )
    }

    // ── 探针 3：他应用媒体的直接修改路径与 MANAGE_MEDIA ─────────────────────

    @Test
    fun foreignMediaModificationPath() {
        val foreign = findForeignVideoUri()

        // 只把 IS_TRASHED 写成它当前的值（0），不改动他应用数据；目的只是观察是否抛权限异常。
        val directUpdate = foreign?.let { uri ->
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) },
                    null,
                    null,
                )
            }
        }
        val directInsertValues = foreign?.let { uri ->
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/") },
                    null,
                    null,
                )
            }
        }

        Stage0EvidenceRecorder.record(
            context,
            "stage0-recycle-foreign-media-${configTag()}.json",
            """
            { "apiLevel": ${Build.VERSION.SDK_INT}
            , "permissions": ${permissionState()}
            , "foreignUriFound": ${foreign != null}
            , "foreignUri": ${foreign?.let { str(it.toString()) } ?: "null"}
            , "method": "direct IS_TRASHED=0 update (no-op value)"
            , "directUpdateOutcome": ${outcome(directUpdate)}
            , "methodRelPath": "direct RELATIVE_PATH re-set to 'Movies/' (no-op value)"
            , "directRelPathOutcome": ${outcome(directInsertValues)}
            , "directDeleteAttempted": false
            }
            """.trimIndent(),
        )
    }

    // ── 探针 4：请求式 API 的成形性与 2000 URI 上限（U3） ────────────────────

    @Test
    fun trashAndDeleteRequestShapeAndBatchLimit() = withProbeVideo { ownUri ->
        val foreign = findForeignVideoUri()
        val singleOwn = runCatching { MediaStore.createTrashRequest(resolver, listOf(ownUri), true) }
        val singleForeign = foreign?.let { uri ->
            runCatching { MediaStore.createTrashRequest(resolver, listOf(uri), true) }
        }
        val untrashOwn = runCatching { MediaStore.createTrashRequest(resolver, listOf(ownUri), false) }

        val pool = distinctVideoUris().ifEmpty { listOf(ownUri) }
        val trashBySize = listOf(1999, 2000, 2001, 5001).map { count ->
            count to runCatching { MediaStore.createTrashRequest(resolver, pool.cycled(count), true) }
        }
        val deleteBySize = listOf(2000, 2001).map { count ->
            count to runCatching { MediaStore.createDeleteRequest(resolver, pool.cycled(count)) }
        }

        Stage0EvidenceRecorder.record(
            context,
            "stage0-recycle-request-api-${configTag()}.json",
            """
            { "apiLevel": ${Build.VERSION.SDK_INT}
            , "permissions": ${permissionState()}
            , "distinctVideoUrisAvailable": ${pool.size}
            , "padStrategy": "cycle existing distinct URIs until count reached"
            , "singleOwnTrashRequest": ${intentOutcome(singleOwn)}
            , "singleForeignTrashRequest": ${intentOutcome(singleForeign)}
            , "singleOwnUntrashRequest": ${intentOutcome(untrashOwn)}
            , "trashRequestBySize": ${trashBySize.joinToString(",", "[", "]") { (n, r) -> "{ \"uris\": $n, \"outcome\": ${intentOutcome(r)} }" }}
            , "deleteRequestBySize": ${deleteBySize.joinToString(",", "[", "]") { (n, r) -> "{ \"uris\": $n, \"outcome\": ${intentOutcome(r)} }" }}
            }
            """.trimIndent(),
        )
    }

    // ── 探针辅助 ────────────────────────────────────────────────────────────

    /**
     * 权限状态必须写进每份快照：同一台设备在不同授权下结果完全不同，
     * 没有授权状态的数字拿来引用就会得出错误结论（本探针第一版即因未记录
     * `MANAGE_EXTERNAL_STORAGE` 而被污染）。
     */
    private fun permissionState(): String {
        fun granted(permission: String): Boolean =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        return """
        { "readMediaVideo": ${granted(Manifest.permission.READ_MEDIA_VIDEO)}
        , "readMediaVisualUserSelected": ${granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)}
        , "readExternalStorage": ${granted(Manifest.permission.READ_EXTERNAL_STORAGE)}
        , "manageMediaDeclared": ${granted(Manifest.permission.MANAGE_MEDIA)}
        , "canManageMedia": ${MediaStore.canManageMedia(context)}
        , "isExternalStorageManager": ${Environment.isExternalStorageManager()}
        }
        """.trimIndent()
    }

    /**
     * 诊断用：不指定投影，把第一行的**每一个**列按名字原样打印。
     * 用于区分「该列被 MediaProvider 省略」与「该列存在但被置空」——
     * 这两种情况对"UI 能不能显示文件名/剩余天数"的含义完全不同。
     */
    private fun firstRowRaw(): String = runCatching {
        val cursor = resolver.query(videoUri, null, defaultTrashArgs(), null) ?: error("null cursor")
        cursor.use { c ->
            if (!c.moveToFirst()) {
                "{}"
            } else {
                val names = c.columnNames
                names.indices.joinToString(",", "{", "}") { index ->
                    // 逐列 runCatching：某些列是 BLOB，直接 getString 会抛
                    // "Unable to convert BLOB to string" 并把整次诊断打断。
                    val value = runCatching {
                        if (c.isNull(index)) null else c.getString(index)
                    }.getOrElse { "<${it::class.java.simpleName}: ${it.message}>" }
                    "\"${esc(names[index])}\": ${str(value)}"
                }
            }
        }
    }.getOrElse { "{\"error\": ${str(it.message)}}" }

    /**
     * 快照文件名带授权配置标签。标签由 `-e configTag <tag>` 显式传入，
     * **不从权限状态推导**——推导会让三次运行落到同一个文件名上，互相覆盖，
     * 最后只留下一份却看不出是哪一份（本探针第二版即因此丢掉了两组证据）。
     */
    private fun configTag(): String =
        InstrumentationRegistry.getArguments().getString("configTag")
            ?: when {
                Environment.isExternalStorageManager() -> "allfiles"
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED -> "readmediavideo"

                else -> "noperm"
            }

    private fun <T> withProbeVideo(block: (Uri) -> T): T {
        val uri = requireNotNull(createProbeVideo()) { "probe video row must be insertable" }
        return try {
            block(uri)
        } finally {
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    /** 只写一个 ftyp box：探针关心的是**行**，不是可播放性（行内容由 MediaProvider 原样保存）。 */
    private fun createProbeVideo(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "stage0-recycle-probe.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/YingLi-Stage0")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(videoUri, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(FTYP_BOX) }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    private fun defaultTrashArgs(): Bundle =
        Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE) }

    private fun trashArgs(match: Int): Bundle =
        Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, match) }

    private fun readTrashFacts(uri: Uri): TrashFacts {
        val row = queryRows(factsProjection, null, uri).getOrNull()?.firstOrNull()
        return TrashFacts(
            isTrashed = row?.get("is_trashed"),
            dateExpires = row?.get("date_expires"),
            dateAdded = row?.get("date_added"),
            ownerPackage = row?.get("owner_package_name"),
            columnsPresent = CURSOR_COLUMNS.toList(),
        )
    }

    private fun setTrashed(uri: Uri, trashed: Boolean): Result<Int> =
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
                },
                null,
                null,
            )
        }

    private fun countInQuery(trashedQuery: Boolean, uri: Uri): Int {
        val args = if (trashedQuery) trashArgs(MediaStore.MATCH_ONLY) else defaultTrashArgs()
        val selfId = ContentUris.parseId(uri).toString()
        return queryRows(idProjection, args).getOrNull()?.count { it["_id"] == selfId } ?: -1
    }

    private fun findForeignVideoUri(): Uri? {
        val rows = queryRows(factsProjection, defaultTrashArgs()).getOrNull() ?: return null
        val match = rows.firstOrNull {
            val owner = it["owner_package_name"]
            owner != null && owner != ownPackage
        } ?: return null
        val id = match["_id"]?.toLongOrNull() ?: return null
        return ContentUris.withAppendedId(videoUri, id)
    }

    private fun distinctVideoUris(): List<Uri> =
        (queryRows(idProjection, defaultTrashArgs()).getOrNull() ?: emptyList())
            .mapNotNull { it["_id"]?.toLongOrNull() }
            .map { ContentUris.withAppendedId(videoUri, it) }

    private fun List<Uri>.cycled(count: Int): List<Uri> = List(count) { this[it % size] }

    private fun queryRows(
        projection: Array<String>,
        args: Bundle?,
        singleUri: Uri? = null,
    ): Result<List<Map<String, String?>>> = runCatching {
        val cursor = if (singleUri == null) {
            resolver.query(videoUri, projection, args, null)
        } else {
            resolver.query(singleUri, projection, null, null)
        } ?: error("MediaStore query returned null cursor")
        cursor.use { readRows(it, projection) }
    }

    private fun readRows(cursor: Cursor, projection: Array<String>): List<Map<String, String?>> {
        CURSOR_COLUMNS.clear()
        CURSOR_COLUMNS.addAll(cursor.columnNames.toList())
        val rows = mutableListOf<Map<String, String?>>()
        while (cursor.moveToNext()) {
            rows += projection.associateWith { name ->
                val index = cursor.getColumnIndex(name)
                if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
            }
        }
        return rows
    }

    private data class TrashFacts(
        val isTrashed: String?,
        val dateExpires: String?,
        val dateAdded: String?,
        val ownerPackage: String?,
        val columnsPresent: List<String>,
    ) {
        fun toJson(): String =
            """
            { "isTrashed": ${str(isTrashed)}
            , "dateExpires": ${str(dateExpires)}
            , "dateAdded": ${str(dateAdded)}
            , "owner": ${str(ownerPackage)}
            , "cursorColumns": ${columnsPresent.jsonArray()}
            }
            """.trimIndent()
    }

    private fun Map<String, String?>.toJson(): String =
        """
        { "id": ${str(this["_id"])}, "name": ${str(this[MediaStore.MediaColumns.DISPLAY_NAME])}
        , "owner": ${str(this["owner_package_name"])}, "isTrashed": ${str(this["is_trashed"])}
        , "dateExpires": ${str(this["date_expires"])}, "dateAdded": ${str(this["date_added"])}
        , "relativePath": ${str(this["relative_path"])}
        }
        """.trimIndent()

    companion object {
        private val FTYP_BOX = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x02, 0x00,
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '1'.code.toByte(),
        )

        /** 最近一次查询返回的游标列名，用于判断某个列是否真的可投影。 */
        private val CURSOR_COLUMNS = mutableListOf<String>()
    }
}

private fun esc(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun str(value: String?): String = if (value == null) "null" else "\"${esc(value)}\""

private fun List<String>.jsonArray(): String = joinToString(",", "[", "]") { "\"${esc(it)}\"" }

private fun err(error: Throwable?): String =
    if (error == null) "null" else "\"${esc(error::class.java.simpleName)}: ${esc(error.message ?: "")}\""

private fun <T> outcome(result: Result<T>?): String = when {
    result == null -> "null"
    result.isSuccess -> "\"ok\""
    else -> {
        val error = result.exceptionOrNull()!!
        "\"${esc(error::class.java.simpleName)}: ${esc(error.message ?: "")}\""
    }
}

private fun intentOutcome(result: Result<PendingIntent>?): String = when {
    result == null -> "null"
    result.isSuccess -> {
        val intent = result.getOrNull()
        "{ \"ok\": true, \"intentSenderNull\": ${intent?.intentSender == null}" +
            ", \"creatorPackage\": ${str(intent?.creatorPackage)} }"
    }

    else -> {
        val error = result.exceptionOrNull()!!
        "{ \"ok\": false, \"exception\": \"${esc(error::class.java.simpleName)}\"" +
            ", \"message\": ${str(error.message)}, \"recoverable\": ${error is RecoverableSecurityException} }"
    }
}
