package seeyuer.yingli.player.core.model

sealed interface AppFailure {
    val code: String

    data object PermissionDenied : AppFailure {
        override val code: String = "PERMISSION_DENIED"
    }

    data object StorageUnavailable : AppFailure {
        override val code: String = "STORAGE_UNAVAILABLE"
    }

    data object DatabaseUnavailable : AppFailure {
        override val code: String = "DATABASE_UNAVAILABLE"
    }

    data object UnsupportedMediaFormat : AppFailure {
        override val code: String = "UNSUPPORTED_MEDIA_FORMAT"
    }

    data object DecodingFailed : AppFailure {
        override val code: String = "DECODING_FAILED"
    }

    data object Cancelled : AppFailure {
        override val code: String = "TASK_CANCELLED"
    }

    data class Unknown(
        val causeType: String,
    ) : AppFailure {
        override val code: String = "UNKNOWN"
    }
}
