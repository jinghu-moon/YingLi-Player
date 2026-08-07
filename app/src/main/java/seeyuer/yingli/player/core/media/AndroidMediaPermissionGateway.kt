package seeyuer.yingli.player.core.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppDispatchers

class AndroidMediaPermissionGateway(
    context: Context,
    private val dispatchers: AppDispatchers,
) : MediaPermissionGateway {
    private val appContext = context.applicationContext

    override suspend fun inspect(): MediaPermissionSnapshot = withContext(dispatchers.io) {
        MediaPermissionSnapshot(
            allFilesAccess = Environment.isExternalStorageManager(),
            mediaStoreReadAccess = hasMediaStoreReadAccess(),
            persistedSafTrees = appContext.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toSet(),
        )
    }

    override suspend fun persistSafTree(uri: String): PermissionActionResult = withContext(dispatchers.io) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            PermissionActionResult.SafTreeGranted(uri)
        } catch (_: SecurityException) {
            PermissionActionResult.Denied
        }
    }

    override suspend fun isSafTreeAccessible(uri: String): Boolean = inspect().persistedSafTrees.contains(uri)

    private fun hasMediaStoreReadAccess(): Boolean {
        val primaryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (appContext.checkSelfPermission(primaryPermission) == PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            appContext.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
    }
}
