package seeyuer.yingli.player.data.settings

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.DefaultSensitiveValueRedactor
import seeyuer.yingli.player.core.common.DiagnosticLogStore
import seeyuer.yingli.player.domain.settings.DiagnosticReport
import seeyuer.yingli.player.domain.settings.DiagnosticsReporter
import seeyuer.yingli.player.domain.settings.GitHubReleaseParser
import seeyuer.yingli.player.domain.settings.UpdateCheckResult
import seeyuer.yingli.player.domain.settings.UpdateSource

class LocalDiagnosticsReporter(
    private val context: Context,
    private val records: DiagnosticLogStore,
    private val dispatchers: AppDispatchers,
) : DiagnosticsReporter {
    private val redactor = DefaultSensitiveValueRedactor()

    override suspend fun createReport(): DiagnosticReport = withContext(dispatchers.default) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val snapshot = records.snapshot()
        val content = buildString {
            appendLine("YingLi diagnostics")
            appendLine("package=${context.packageName}")
            appendLine("version=${packageInfo.versionName.orEmpty()}")
            appendLine("sdk=${android.os.Build.VERSION.SDK_INT}")
            snapshot.forEach { record ->
                append(record.level.name)
                append('|')
                append(record.code)
                append('|')
                append(redactor.redact(record.message))
                if (record.attributes.isNotEmpty()) {
                    append('|')
                    append(record.attributes.toSortedMap().entries.joinToString(",") { (key, value) ->
                        "$key=${redactor.redact(value)}"
                    })
                }
                appendLine()
            }
        }
        DiagnosticReport(redactor.redact(content), snapshot.size)
    }
}

class GitHubReleaseUpdateSource(
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : UpdateSource {
    private val mutex = Mutex()
    private var lastCheckEpochMillis: Long? = null

    override suspend fun check(currentVersion: String): UpdateCheckResult = mutex.withLock {
        val now = clock.now().toEpochMilli()
        if (lastCheckEpochMillis?.let { now - it < MIN_CHECK_INTERVAL_MILLIS } == true) {
            return@withLock UpdateCheckResult.RateLimited
        }
        lastCheckEpochMillis = now
        withContext(dispatchers.io) {
            val response = requestLatestRelease() ?: return@withContext UpdateCheckResult.Unavailable
            val release = GitHubReleaseParser.parse(response) ?: return@withContext UpdateCheckResult.InvalidResponse
            if (isNewer(release.tagName, currentVersion)) {
                UpdateCheckResult.Available(release)
            } else {
                UpdateCheckResult.Current
            }
        }
    }

    private fun requestLatestRelease(): String? {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "YingLi-Android")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    result.append(buffer, 0, count)
                    if (result.length > GitHubReleaseParser.MAX_RESPONSE_CHARS) return null
                }
                result.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").substringBefore('-').split('.')
            .map { it.toIntOrNull() ?: 0 }
        val candidateParts = parts(candidate)
        val currentParts = parts(current)
        val size = maxOf(candidateParts.size, currentParts.size)
        return (0 until size).firstNotNullOfOrNull { index ->
            val difference = (candidateParts.getOrNull(index) ?: 0) - (currentParts.getOrNull(index) ?: 0)
            difference.takeIf { it != 0 }
        }?.let { it > 0 } ?: false
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/jinghu-moon/YingLi-Player/releases/latest"
        const val MIN_CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
        const val TIMEOUT_MILLIS = 8_000
        const val BUFFER_SIZE = 4_096
    }
}
