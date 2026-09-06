package seeyuer.yingli.player.core.common

fun interface SensitiveValueRedactor {
    fun redact(value: String): String
}

class DefaultSensitiveValueRedactor : SensitiveValueRedactor {
    override fun redact(value: String): String =
        value
            .replace(SECRET_PATTERN) { match -> "${match.groupValues[1]}=[REDACTED]" }
            .replace(URI_PATTERN, "[REDACTED_URI]")
            .replace(WINDOWS_PATH_PATTERN, "[REDACTED_PATH]")
            .replace(UNIX_PATH_PATTERN, "[REDACTED_PATH]")

    private companion object {
        val SECRET_PATTERN = Regex(
            pattern = "(?i)\\b(password|passphrase|token|secret|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s,;]+",
        )
        val URI_PATTERN = Regex("\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+")
        val WINDOWS_PATH_PATTERN = Regex("(?i)\\b[A-Z]:\\\\(?:[^\\\\\\s]+\\\\)*[^\\\\\\s]+")
        val UNIX_PATH_PATTERN = Regex("(?<![A-Za-z0-9:])/(?:[^/\\s]+/)+[^/\\s]+")
    }
}
