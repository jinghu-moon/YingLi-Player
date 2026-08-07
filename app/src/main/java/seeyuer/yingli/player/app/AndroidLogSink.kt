package seeyuer.yingli.player.app

import android.util.Log
import seeyuer.yingli.player.core.foundation.AppLogLevel
import seeyuer.yingli.player.core.foundation.AppLogRecord
import seeyuer.yingli.player.core.foundation.AppLogSink

class AndroidLogSink : AppLogSink {
    override fun emit(record: AppLogRecord) {
        val attributes = record.attributes.entries.joinToString { (key, value) -> "$key=$value" }
        val message = listOf(record.code, record.message, attributes)
            .filter(String::isNotBlank)
            .joinToString(separator = " | ")
        when (record.level) {
            AppLogLevel.INFO -> Log.i(TAG, message)
            AppLogLevel.WARNING -> Log.w(TAG, message)
            AppLogLevel.ERROR -> Log.e(TAG, message)
        }
    }

    private companion object {
        const val TAG = "YingLi"
    }
}
