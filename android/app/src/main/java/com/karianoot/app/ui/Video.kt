package com.karianoot.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.karianoot.app.Creation
import com.karianoot.app.KarianootApp
import com.karianoot.app.VideoMaker
import java.io.File

/** Sends [f] to one app ([pkg]) or, when it is not installed / null, to the share sheet. */
fun shareFile(ctx: android.content.Context, f: File, mime: String, pkg: String?, title: String = "שיתוף") {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val ok = pkg != null && runCatching { ctx.startActivity(Intent(i).setPackage(pkg)) }.isSuccess
    if (!ok) {
        if (pkg != null) Toast.makeText(ctx, "האפליקציה לא מותקנת. בחרו אחרת.", Toast.LENGTH_SHORT).show()
        ctx.startActivity(Intent.createChooser(i, title))
    }
}

/** The first of [pkgs] that is installed, or null. */
private fun installed(ctx: android.content.Context, vararg pkgs: String): String? =
    pkgs.firstOrNull { runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess }

/**
 * "סרטון עם כתוביות": pictures (optional), a title (optional), word-by-word or plain captions -> an MP4 ready for
 * TikTok / Reels / Shorts, then share it straight there or save it to the gallery.
 */
@Composable
fun VideoDialog(app: KarianootApp, c: Creation, close: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var karaoke by remember { mutableStateOf(true) }
    var made by remember { mutableStateOf<File?>(null) }
    val making by app.video.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { if (it.isNotEmpty()) images = it }
    val busyHere = making?.first == c.file
    AlertDialog(
        onDismissRequest = { if (!busyHere) close() },
        containerColor = Color(0xFF14120F),
        title = { Text("סרטון עם כתוביות", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val done = made
                when {
                    done != null -> {
                        Text("✓ הסרטון מוכן (${formatSize(done.length())}).", color = C.Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        SoftButton("לטיקטוק", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share, color = C.Gold) {
                            shareFile(ctx, done, "video/mp4", installed(ctx, "com.zhiliaoapp.musically", "com.ss.android.ugc.trill") ?: "com.zhiliaoapp.musically")
                        }
                        SoftButton("לאינסטגרם", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share) { shareFile(ctx, done, "video/mp4", "com.instagram.android") }
                        SoftButton("ליוטיוב", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share) { shareFile(ctx, done, "video/mp4", "com.google.android.youtube") }
                        SoftButton("לוואטסאפ", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share) { shareFile(ctx, done, "video/mp4", "com.whatsapp") }
                        SoftButton("אפליקציה אחרת…", Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoAwesome) { shareFile(ctx, done, "video/mp4", null) }
                        SoftButton("שמירה בגלריה", Modifier.fillMaxWidth(), icon = Icons.Rounded.SaveAlt) {
                            Toast.makeText(ctx, if (app.speech.exportVideo(done)) "נשמר בגלריה (Movies/Karianoot)" else "השמירה נכשלה", Toast.LENGTH_SHORT).show()
                        }
                    }
                    busyHere -> {
                        Text("מכין את הסרטון… ${((making?.second ?: 0f) * 100).toInt()}%", color = C.Text, fontSize = 15.sp)
                        GoldProgress(making?.second ?: 0f)
                        Text("אפשר להשאיר את המסך פתוח. סרטון של דקה לוקח בערך דקה.", color = C.Faint, fontSize = 12.sp)
                    }
                    else -> {
                        Text("סרטון אנכי לטיקטוק, ריילס ושורטס: המילים מופיעות כשהן נאמרות, והמילה שנאמרת עכשיו בזהב.",
                            color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                        SoftButton(if (images.isEmpty()) "תמונות לרקע (לא חובה)" else "✓ ${images.size} תמונות (להחלפה)", Modifier.fillMaxWidth(), icon = Icons.Rounded.Image) {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        if (images.isEmpty()) Text("בלי תמונות: רקע כהה עם זוהר זהב.", color = C.Faint, fontSize = 12.sp)
                        FieldBox(title, "כותרת למעלה (לא חובה), למשל: יום המזל") { if (it.length <= 40) title = it }
                        SettingSwitch("מילה-מילה", "המילה שנאמרת עכשיו נצבעת בזהב.", karaoke) { karaoke = it }
                    }
                }
            }
        },
        confirmButton = {
            when {
                made != null -> SoftButton("סגירה", onClick = close)
                busyHere -> SoftButton("ביטול", icon = Icons.Rounded.Close) { app.cancelVideo() }
                else -> SoftButton("צור סרטון", icon = Icons.Rounded.Movie, color = C.Gold, enabled = making == null) {
                    app.makeVideo(c, VideoMaker.Style(title, images, karaoke)) { f, err ->
                        if (f != null) made = f
                        else if (err != null) Toast.makeText(ctx, "לא הצלחתי ליצור סרטון: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        },
        dismissButton = {
            if (made == null && !busyHere) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("כתוביות בלבד", icon = Icons.Rounded.Subtitles) {
                    runCatching { shareFile(ctx, app.speech.srtFile(c), "application/x-subrip", null, "קובץ כתוביות") }
                        .onFailure { Toast.makeText(ctx, "לא הצלחתי להכין כתוביות", Toast.LENGTH_SHORT).show() }
                }
                SoftButton("ביטול", onClick = close)
            }
        },
    )
}
