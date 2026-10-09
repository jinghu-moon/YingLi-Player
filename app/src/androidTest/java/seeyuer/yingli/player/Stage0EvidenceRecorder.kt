package seeyuer.yingli.player

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 阶段 0 取证快照的唯一落盘出口。
 *
 * 取证测试（`*MeasurementTest`）不是行为契约测试：它们只把平台实测事实写成 JSON 证据，
 * 供设计文档引用，**不对被测行为下断言**。快照落在 `getExternalFilesDir(null)` 下，
 * 便于用 `adb pull` 取回后归档到 `docs/architecture/evidence/stage0/`。
 *
 * 注意 `adb pull <dir> <dst>` 会在 `$dst` 下再建一层 `files/`。
 */
object Stage0EvidenceRecorder {
    const val LOG_TAG = "YingLiStage0"

    fun record(context: Context, name: String, json: String) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeText(json)
        Log.i(LOG_TAG, "snapshot -> ${file.absolutePath}\n$json")
    }
}
