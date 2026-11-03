package dev.amatv.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import dev.amatv.R
import dev.amatv.integration.appleMusicShareUrl

/** TV sharing uses the phone QR; a non-TV device may offer its native Sharesheet. */
internal fun shareAppleMusicLink(context: Context, url: String, title: String): Boolean {
    val destination = appleMusicShareUrl(url) ?: return false
    if ((context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, destination)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    return try {
        if (context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) return false
        context.startActivity(Intent.createChooser(send, context.getString(R.string.actions_share)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun copyAppleMusicLink(context: Context, url: String): Boolean {
    val destination = appleMusicShareUrl(url) ?: return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    return try {
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), destination))
        // Android 13+ already displays the system's copy confirmation.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(context, R.string.actions_link_copied, Toast.LENGTH_SHORT).show()
        }
        true
    } catch (_: SecurityException) {
        false
    }
}

@Composable
internal fun AppleMusicShareDialog(url: String, itemTitle: String, onDismiss: () -> Unit) {
    AppleMusicManagementDialog(url, itemTitle, onDone = onDismiss, onDismiss = onDismiss, sharing = true)
}
