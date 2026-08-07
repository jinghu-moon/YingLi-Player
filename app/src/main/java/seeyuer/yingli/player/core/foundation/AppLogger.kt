package seeyuer.yingli.player.core.foundation

enum class AppLogLevel {
    INFO,
    WARNING,
    ERROR,
}

sealed interface LogValue {
    val value: String

    data class Public(override val value: String) : LogValue

    data class Sensitive(override val value: String) : LogValue
}

data class AppLogEvent(
    val code: String,
    val message: String,
    val attributes: Map<String, LogValue> = emptyMap(),
) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]+"))) {
            "Log event codes must be stable SCREAMING_SNAKE_CASE identifiers."
        }
    }
}

data class AppLogRecord(
    val level: AppLogLevel,
    val code: String,
    val message: String,
    val attributes: Map<String, String>,
)

fun interface AppLogSink {
    fun emit(record: AppLogRecord)
}

fun interface AppLogger {
    fun log(level: AppLogLevel, event: AppLogEvent)
}

class RedactingAppLogger(
    private val redactor: SensitiveValueRedactor,
    private val sink: AppLogSink,
) : AppLogger {
    override fun log(level: AppLogLevel, event: AppLogEvent) {
        val safeAttributes = event.attributes.mapValues { (_, value) ->
            when (value) {
                is LogValue.Public -> redactor.redact(value.value)
                is LogValue.Sensitive -> REDACTED_VALUE
            }
        }
        sink.emit(
            AppLogRecord(
                level = level,
                code = event.code,
                message = redactor.redact(event.message),
                attributes = safeAttributes,
            ),
        )
    }

    private companion object {
        const val REDACTED_VALUE = "[REDACTED]"
    }
}
