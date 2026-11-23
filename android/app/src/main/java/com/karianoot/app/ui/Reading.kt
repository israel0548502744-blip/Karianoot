package com.karianoot.app.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karianoot.app.KarianootApp
import com.karianoot.app.Speech
import com.karianoot.app.VoiceInfo
import com.karianoot.engine.IpaHebrew
import kotlinx.coroutines.launch

/** IPA as Hebrew with niqqud, the stressed syllable in gold. */
@Composable
fun ReadingText(ipa: String, size: Int = 18) {
    val words = ipa.split(' ').filter { it.isNotBlank() }
    Text(buildAnnotatedString {
        words.forEachIndexed { i, w ->
            if (i > 0) append(' ')
            for (s in IpaHebrew.render(w)) {
                if (s.stressed) withStyle(SpanStyle(color = C.GoldLight, fontWeight = FontWeight.ExtraBold)) { append(s.text) } else append(s.text)
            }
        }
    }, color = C.Text, fontSize = size.sp, lineHeight = (size + 8).sp)
}

/**
 * "איך זה ייקרא": every word of the text as the voice will say it (Hebrew with niqqud, stress in gold).
 * Tap a word to hear it and pick another way by ear; the choice is saved as a pronunciation fix.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingDialog(app: KarianootApp, text: String, voice: VoiceInfo?, close: () -> Unit) {
    var data by remember { mutableStateOf<List<List<Speech.Reading>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fixing by remember { mutableStateOf<Speech.Reading?>(null) }
    val fixes by app.pronounce.list.collectAsState()
    LaunchedEffect(text, fixes) {
        runCatching { app.speech.readings(text) }.onSuccess { data = it }.onFailure { error = it.message ?: it.toString() }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("איך זה ייקרא", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ככה הקול יגיד כל מילה. ההברה המודגשת בזהב היא ההברה שעליה הדגש. מילה לא נכונה? לוחצים עליה.",
                    color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                val d = data
                when {
                    error != null -> Text("לא הצלחתי: $error", color = C.Red, fontSize = 13.sp)
                    d == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Wave(Modifier.size(32.dp, 20.dp), animate = true); Spacer(Modifier.width(10.dp)); Text("קורא את הטקסט…", color = C.Text)
                    }
                    else -> for (sentence in d) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (r in sentence) Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x18FFF4DC)).border(1.dp, C.Stroke, RoundedCornerShape(12.dp))
                                .pressable { fixing = r }.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(r.word, color = C.Faint, fontSize = 11.sp)
                            ReadingText(r.ipa)
                        }
                    }
                }
            }
        },
        confirmButton = { SoftButton("סגירה", onClick = close) },
    )
    fixing?.let { r -> WordFixDialog(app, r, voice) { fixing = null } }
}

/** One word: its reading, the other stress choices, or a spelling of your own, each to hear; the right one is saved. */
@Composable
fun WordFixDialog(app: KarianootApp, r: Speech.Reading, voice: VoiceInfo?, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var playing by remember { mutableStateOf<String?>(null) }
    var spelling by remember { mutableStateOf("") }
    var spelled by remember { mutableStateOf<String?>(null) }
    val single = !r.ipa.contains(' ')
    val options = if (single) IpaHebrew.stressVariants(r.ipa) else listOf(r.ipa)
    fun play(ipa: String) {
        val v = voice ?: return Toast.makeText(ctx, "בחרו קול בסטודיו כדי לשמוע", Toast.LENGTH_SHORT).show()
        if (playing != null) return
        playing = ipa
        scope.launch {
            runCatching { app.speech.sayIpa(ipa, v) }.onSuccess { f ->
                runCatching { player.reset(); player.setDataSource(f.path); player.prepare(); player.start() }
            }.onFailure { Toast.makeText(ctx, "לא הצלחתי להשמיע", Toast.LENGTH_SHORT).show() }
            playing = null
        }
    }
    fun save(ipa: String) {
        app.pronounce.add(r.word, ipa)
        Toast.makeText(ctx, "נשמר: \"${r.word}\" ייקרא ככה בכל קריינות", Toast.LENGTH_SHORT).show()
        close()
    }
    @Composable fun Option(ipa: String, label: String?) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFF4DC)).border(1.dp, C.Stroke, RoundedCornerShape(14.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (label != null) Text(label, color = C.Faint, fontSize = 11.sp)
                ReadingText(ipa, 20)
            }
            if (playing == ipa) Wave(Modifier.size(30.dp, 20.dp), animate = true)
            else CircleIcon(Icons.Rounded.PlayArrow, "השמעה", size = 40.dp) { play(ipa) }
            Spacer(Modifier.width(6.dp))
            CircleIcon(Icons.Rounded.Check, "זה הנכון", size = 40.dp, gold = true) { save(ipa) }
        }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("\"${r.word}\"", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("▶ לשמוע, ✓ לבחור. " + if (single) "כל שורה שמה את הדגש על הברה אחרת." else "", color = C.Sub, fontSize = 13.sp)
                options.forEachIndexed { i, o -> Option(o, if (i == 0) "עכשיו" else null) }
                Text("או כתבו איך זה נשמע (אפשר בניקוד):", color = C.Sub, fontSize = 13.sp)
                FieldBox(spelling, "למשל: אַרְסֶנָל") { spelling = it; spelled = null }
                SoftButton("איך זה ייקרא?", Modifier.fillMaxWidth(), enabled = spelling.isNotBlank()) {
                    scope.launch { spelled = runCatching { app.speech.ipaOf(spelling) }.getOrNull() }
                }
                spelled?.let { Option(it, "מה שכתבתם") }
            }
        },
        confirmButton = { SoftButton("סגירה", onClick = close) },
    )
}

/** What to read aloud for a new voice: ~20 s, every Hebrew sound, the way a tips video sounds. */
const val RECORD_SCRIPT = "ערב טוב לכל האוהדים! היום אני מביא לכם שלושה משחקים חמים מהליגה האנגלית והספרדית. " +
    "ברצלונה פוגשת את סביליה, וצ'לסי מארחת את ליברפול. היחס על הניצחון של צ'לסי הוא שתיים ועשרים, " +
    "ואני חושב שזה הימור מצוין. אז תשבו, תקשיבו עד הסוף, ואל תשכחו לעקוב."

/** "הקלטה עכשיו": record yourself reading [RECORD_SCRIPT]; [onDone] gets the WAV. */
@Composable
fun RecordNow(onDone: (android.net.Uri) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec = remember { com.karianoot.app.VoiceRecorder(scope) }
    val st by rec.state.collectAsState()
    var seconds by remember { mutableStateOf(0f) }
    DisposableEffect(Unit) { onDispose { rec.cancel() } }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) rec.start() else Toast.makeText(ctx, "בלי הרשאת מיקרופון אפשר לבחור קובץ הקלטה", Toast.LENGTH_LONG).show()
    }
    val s = st
    if (s == null) {
        SoftButton(if (seconds > 0) "✓ הוקלטו ${seconds.toInt()} שניות (להקליט שוב)" else "הקלטה עכשיו", Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Mic, color = C.Gold) { ask.launch(android.Manifest.permission.RECORD_AUDIO) }
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22E3BF6A)).border(1.dp, C.GoldBrush, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("קראו בקול, בקצב שלכם:", color = C.Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(RECORD_SCRIPT, color = C.Text, fontSize = 15.sp, lineHeight = 23.sp)
            GoldProgress(s.second.coerceIn(0f, 1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.first.toInt()} / ${com.karianoot.app.VoiceRecorder.MAX_SECONDS} שנ׳", color = C.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SoftButton("סיום", icon = Icons.Rounded.Check, color = C.Gold, enabled = s.first >= 8f) {
                    scope.launch {
                        val f = java.io.File(ctx.cacheDir, "my_voice_${System.currentTimeMillis()}.wav")
                        seconds = rec.stop(f)
                        onDone(android.net.Uri.fromFile(f))
                    }
                }
            }
            if (s.first < 8f) Text("לפחות 8 שניות", color = C.Faint, fontSize = 12.sp)
        }
    }
}
