package io.github.ravenor907.muninn.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.MessageService
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.delay

/** One thing Android hasn't let Muninn do yet. [id] also names it in the "not now" list. */
private class Missing(val id: String, val title: String, val detail: String, val permission: String?)

private const val HIDDEN = "setupHidden"

/** What's still missing on this phone, leaving out what it has no hardware for and what was put off with "not now". */
private fun missing(ctx: Context, app: Muninn): List<Missing> {
    val pm = ctx.packageManager
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val hidden = ctx.getSharedPreferences("muninn", Context.MODE_PRIVATE).getStringSet(HIDDEN, null).orEmpty()
    return buildList {
        if (pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) && !granted(Manifest.permission.RECORD_AUDIO))
            add(Missing("mic", "Microphone", "For calls and voice messages.", Manifest.permission.RECORD_AUDIO))
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) && !granted(Manifest.permission.CAMERA))
            add(Missing("camera", "Camera", "For video calls.", Manifest.permission.CAMERA))
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled())
            add(Missing("notify", "Notifications", "So you see new messages and calls.",
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null))
        // Messages only arrive right away if Android lets Muninn run in the background.
        if (app.identity.stayConnected && !MessageService.unrestricted(ctx))
            add(Missing("battery", "Run in the background", "Android's battery saver pauses Muninn while the phone sleeps, so messages arrive late.", null))
    }.filter { it.id !in hidden }
}

/** Muninn's page in Android's settings: where a permission Android won't ask for again is turned on. */
private fun openAppSettings(ctx: Context, notifications: Boolean) {
    val i = if (notifications) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

/**
 * "Finish setting up" on the chat list: the permissions Muninn still needs (microphone, camera, notifications,
 * running in the background), one tap each. Gone once nothing is missing; each line can be put off with "not now".
 * Never on a Kids' TV (a parent sets that up from the TV's own checklist).
 */
@Composable
fun SetupCard(app: Muninn, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (io.github.ravenor907.muninn.tv.KidMode.on(app)) return
    var tick by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(missing(ctx, app)) }
    // Android's own screens change these behind our back: look again every little while and after each answer.
    LaunchedEffect(tick) { while (true) { list = missing(ctx, app); delay(1500) } }
    var askedAt by remember { mutableLongStateOf(0L) }
    var asking by remember { mutableStateOf<Missing?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val m = asking; asking = null
        // An answer straight away means Android didn't show its question (refused for good): its settings page instead.
        if (!ok && m != null && System.currentTimeMillis() - askedAt < 500) openAppSettings(ctx, m.id == "notify")
        tick++
    }
    if (list.isEmpty()) return
    Panel(modifier) {
        Text("Finish setting up", style = Type.big)
        Text("Muninn works best with these. Tap one to allow it.", style = Type.small)
        list.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = Type.normal)
                    Text(m.detail, style = Type.small.copy(color = Palette.textMuted))
                }
                MButton("Allow", Modifier.padding(start = 8.dp), Kind.Accent) {
                    when {
                        m.id == "battery" -> MessageService.askUnrestricted(ctx)
                        m.permission != null -> { asking = m; askedAt = System.currentTimeMillis(); ask.launch(m.permission) }
                        else -> openAppSettings(ctx, m.id == "notify")
                    }
                }
                IconAction("close", "Not now: ${m.title}", Palette.textMuted, size = 18.dp) {
                    val p = ctx.getSharedPreferences("muninn", Context.MODE_PRIVATE)
                    p.edit().putStringSet(HIDDEN, p.getStringSet(HIDDEN, null).orEmpty() + m.id).apply()
                    tick++
                }
            }
        }
    }
}
