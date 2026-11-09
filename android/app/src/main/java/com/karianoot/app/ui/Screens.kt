package com.karianoot.app.ui

import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.karianoot.app.Catalog
import com.karianoot.app.Creation
import com.karianoot.app.KarianootApp
import com.karianoot.app.ModelInfo
import com.karianoot.app.ModelKind
import com.karianoot.app.ModelState
import com.karianoot.app.Models
import com.karianoot.app.Speech
import com.karianoot.app.VoiceInfo
import com.karianoot.engine.BlueTts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

enum class Tab(val title: String, val icon: ImageVector) {
    Studio("סטודיו", Icons.Rounded.GraphicEq), Library("ההקלטות", Icons.Rounded.LibraryMusic),
    Models("מודלים", Icons.Rounded.Memory), About("אודות", Icons.Rounded.Info)
}

@Composable
fun App(app: KarianootApp, sharedText: String?, startTab: Tab = Tab.Studio, motion: Boolean = true) {
    val models = app.models
    val speech = app.speech
    var tab by remember { mutableStateOf(startTab) }
    val history = remember { mutableStateListOf<Creation>().apply { addAll(speech.history()) } }
    LaunchedEffect(Unit) { if (motion) models.watch() }
    KarianootTheme(motion) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            NightBackground()
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Studio -> StudioScreen(app, sharedText, history) { tab = Tab.Models }
                        Tab.Library -> LibraryScreen(app, history)
                        Tab.Models -> ModelsScreen(app)
                        Tab.About -> AboutScreen(app)
                    }
                }
                BottomBar(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(64.dp).glass(RoundedCornerShape(32.dp), strong = true).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in Tab.entries) {
            val sel = t == tab
            val tint by animateColorAsState(if (sel) Color(0xFF1A1307) else C.Sub, label = "tab")
            Column(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(26.dp))
                    .then(if (sel) Modifier.background(C.GoldButton) else Modifier).pressable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, t.title, tint = tint, modifier = Modifier.size(22.dp))
                Text(t.title, color = tint, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

private const val SAMPLE = "ערב טוב לכולם! היום יש לנו שלושה משחקים מעולים, ואני הולך להסביר לכם בדיוק למה. " +
    "תישארו עד הסוף, ואל תשכחו להצטרף לקבוצה."

@Composable
fun StudioScreen(app: KarianootApp, sharedText: String?, history: MutableList<Creation>, openModels: () -> Unit) {
    val ctx = LocalContext.current
    val models = app.models
    val speech = app.speech
    val states by models.states.collectAsState()
    val job by app.job.collectAsState()
    var text by remember { mutableStateOf(sharedText ?: "") }
    val custom by app.voices.custom.collectAsState()
    val cloning by app.cloning.collectAsState()
    var newVoice by remember { mutableStateOf(false) }
    var gender by remember { mutableStateOf(0) } // 0 all, 1 men, 2 women
    var fixesOpen by remember { mutableStateOf(false) }
    val fixes by app.pronounce.list.collectAsState()
    val ready = (Catalog.voices + custom).filter { v -> v.needs.all { states[it] == ModelState.Ready } }
    // The last voice and pause are remembered across app starts.
    val prefs = remember { ctx.getSharedPreferences("studio", android.content.Context.MODE_PRIVATE) }
    var voiceId by remember { mutableStateOf(prefs.getString("voice", null) ?: Catalog.voices.first().id) }
    LaunchedEffect(voiceId) { prefs.edit().putString("voice", voiceId).apply() }
    val voice = ready.firstOrNull { it.id == voiceId } ?: ready.firstOrNull()
    var alive by remember(voice?.id) { mutableFloatStateOf(voice?.liveliness ?: 1f) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var quality by remember { mutableStateOf(1) } // 0 fast, 1 good, 2 best
    var pause by remember { mutableFloatStateOf(prefs.getFloat("pause", 0.15f)) }
    LaunchedEffect(pause) { prefs.edit().putFloat("pause", pause).apply() }
    val busy = job.busy
    val last = job.result
    val g2pReady = states["g2p"] == ModelState.Ready
    // A finished job (also one that finished while the app was in the background) joins the list.
    LaunchedEffect(job.result) {
        val c = job.result ?: return@LaunchedEffect
        if (history.none { it.file == c.file }) history.add(0, c)
        while (history.size > Speech.KEEP) history.removeAt(history.size - 1)
    }
    LaunchedEffect(job.error) { job.error?.let { Toast.makeText(ctx, if (it == "הקריינות בוטלה") it else "שגיאה: $it", Toast.LENGTH_LONG).show() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(10.dp))
        Column(Modifier.enter(0)) {
            GoldText("קריינות", 40.sp)
            Text("קריינות בעברית, ישר מהטלפון. בלי אינטרנט.", color = C.Sub, fontSize = 15.sp)
        }

        if (!g2pReady || ready.isEmpty()) {
            Section(Modifier.enter(1)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = C.Gold, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("כדי להתחיל, מורידים פעם אחת את מנוע ההגייה, ואז מודל קולות: Qwen (1.5GB, הכי טבעי) או Blue 2.5 (270MB, מהיר).",
                        color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
                GoldButton("להורדת המודלים", Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, onClick = openModels)
            }
        }

        Section(Modifier.enter(2)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 170.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x55000000))
                .border(1.dp, C.Stroke, RoundedCornerShape(18.dp)).padding(14.dp)) {
                if (text.isEmpty()) Text("כתבו או הדביקו כאן את הטקסט לקריינות…", color = C.Faint, fontSize = 17.sp)
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    textStyle = TextStyle(color = C.Text, fontSize = 18.sp, fontFamily = Rubik, lineHeight = 28.sp, textDirection = TextDirection.Rtl),
                    cursorBrush = SolidColor(C.Gold))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${text.length} תווים", color = C.Faint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (text.isEmpty()) SoftButton("טקסט לדוגמה", icon = Icons.Rounded.AutoAwesome) { text = SAMPLE }
                else SoftButton("נקה", icon = Icons.Rounded.Close) { text = "" }
            }
        }

        Section(Modifier.enter(3), title = "קול") {
            Segmented(listOf("הכל", "גברים", "נשים"), gender) { gender = it }
            val shown = (Catalog.voices + custom).filter { gender == 0 || (gender == 1 && !it.female) || (gender == 2 && it.female) }
            for (g in VOICE_GROUPS) {
                val inGroup = shown.filter(g.match)
                if (inGroup.isEmpty() && !g.mine) continue
                val readyIn = inGroup.filter { v -> v.needs.all { states[it] == ModelState.Ready } }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(g.title, color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(g.subtitle, color = C.Faint, fontSize = 12.sp)
                    }
                    if (readyIn.isEmpty() && !g.mine) {
                        SoftButton(g.missing, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, onClick = openModels)
                    } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (v in readyIn) VoiceChip(v, v.id == voice?.id) { voiceId = v.id }
                        if (g.mine) NewVoiceChip(cloning) { newVoice = true }
                    }
                }
            }
            if (voice?.personalOnly == true) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, tint = C.Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("קול לשימוש אישי בלבד: זכויות הקול לא ברורות. לא להכניס לאפליקציה שנמכרת.", color = C.Amber, fontSize = 12.sp)
            }
        }

        Section(Modifier.enter(4), title = "כיוונון") {
            val isQwen = voice?.modelId == Catalog.qwen.id
            if (isQwen) Text("ב-Qwen הקצב, הרגש והאיכות נקבעים לבד. אפשר לכוון רק את ההפסקה בין משפטים.", color = C.Faint, fontSize = 12.sp)
            else LabeledSlider("מהירות", "×%.2f".format(speed), speed, 0.7f..1.4f) { speed = it }
            LabeledSlider("הפסקה בין משפטים", "%.2f שנ׳".format(pause), pause, 0f..0.8f) { pause = it }
            if (!isQwen) {
                LabeledSlider("חיות (פחות רובוטי)", "%.1f".format(alive), alive, 0.8f..1.5f) { alive = it }
                Text("איכות", color = C.Sub, fontSize = 14.sp)
                Segmented(listOf("מהיר", "איכותי", "מקסימום"), quality) { quality = it }
            }
            SoftButton(if (fixes.isEmpty()) "תיקוני הגייה" else "תיקוני הגייה (${fixes.size})", Modifier.fillMaxWidth(),
                icon = Icons.Rounded.RecordVoiceOver) { fixesOpen = true }
        }
        if (fixesOpen) PronounceDialog(app) { fixesOpen = false }

        if (newVoice) NewVoiceDialog(app, states, openModels = { newVoice = false; openModels() }) { newVoice = false }

        AnimatedVisibility(busy, enter = fadeIn(), exit = fadeOut()) {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wave(Modifier.size(42.dp, 28.dp), animate = true)
                    Spacer(Modifier.width(12.dp))
                    Text(job.stage, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${(job.progress * 100).toInt()}%", color = C.Gold, fontWeight = FontWeight.Bold)
                }
                GoldProgress(job.progress)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("אפשר לצאת מהאפליקציה. תקבלו התראה כשהקריינות מוכנה.", color = C.Faint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SoftButton("ביטול", icon = Icons.Rounded.Close) { app.cancelNarration() }
                }
            }
        }

        if (!busy && voice != null && text.isNotBlank()) speech.estimateSeconds(text, voice, speed)?.let { est ->
            Text("זמן משוער: " + formatSeconds(est.coerceAtLeast(1f)), color = C.Faint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        }
        GoldButton(if (busy) "מקריא…" else "צור קריינות", Modifier.fillMaxWidth().enter(5), icon = Icons.Rounded.AutoAwesome,
            enabled = !busy && g2pReady && voice != null && text.isNotBlank()) {
            val v = voice ?: return@GoldButton
            app.narrate(text, v, BlueTts.Options(steps = v.steps[quality], speed = speed, silence = pause, temperature = alive))
        }

        last?.let { CreationCard(it, app, history, highlight = true) { history.remove(it); speech.delete(it); app.clearResult() } }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun VoiceChip(v: VoiceInfo, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(20.dp))
            .then(if (selected) Modifier.border(2.dp, C.GoldBrush, RoundedCornerShape(20.dp)) else Modifier.border(1.dp, C.Stroke, RoundedCornerShape(20.dp)))
            .background(if (selected) Color(0x26E3BF6A) else Color(0x10FFF4DC)).pressable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (selected) C.GoldButton else Brush.linearGradient(listOf(Color(0x33FFF4DC), Color(0x11FFF4DC)))),
            contentAlignment = Alignment.Center) {
            Text(v.name.split(" ").last().take(2), color = if (selected) Color(0xFF1A1307) else C.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(v.name, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(if (v.personalOnly) "⚠ אישי" else v.note.ifEmpty { if (v.female) "אישה" else "גבר" }, color = if (v.personalOnly) C.Amber else C.Faint, fontSize = 11.sp)
    }
}

@Composable
private fun NewVoiceChip(cloning: String?, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, C.GoldBrush, RoundedCornerShape(20.dp))
            .background(Color(0x10FFF4DC)).pressable(enabled = cloning == null, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.GoldButton), contentAlignment = Alignment.Center) {
            if (cloning != null) Wave(Modifier.size(26.dp, 18.dp), animate = true)
            else Icon(Icons.Rounded.Add, null, tint = Color(0xFF1A1307), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(if (cloning != null) "מכין…" else "קול חדש", color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("מהקלטה", color = C.Faint, fontSize = 11.sp)
    }
}

/** "Say this word like that": the user's own pronunciation fixes. */
@Composable
private fun PronounceDialog(app: KarianootApp, close: () -> Unit) {
    val list by app.pronounce.list.collectAsState()
    var word by remember { mutableStateOf("") }
    var say by remember { mutableStateOf("") }
    @Composable fun Field(value: String, hint: String, onChange: (String) -> Unit) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x55000000))
            .border(1.dp, C.Stroke, RoundedCornerShape(14.dp)).padding(12.dp)) {
            if (value.isEmpty()) Text(hint, color = C.Faint)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = C.Text, fontSize = 16.sp, fontFamily = Rubik), cursorBrush = SolidColor(C.Gold), singleLine = true)
        }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("תיקוני הגייה", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("מילה שיוצאת לא נכון? כתבו אותה, ואיך היא צריכה להישמע: בכתיב \"כמו שזה נשמע\", או בניקוד (הכי מדויק). " +
                    "התיקון חל על כל הקריינויות, גם עם ו/ה/ב/ל/מ/ש/כ לפני המילה.", color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                Field(word, "המילה (למשל: היחס)") { word = it }
                Field(say, "איך להגיד אותה (למשל: הַיַּחַס)") { say = it }
                SoftButton("הוספה", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, enabled = word.isNotBlank() && say.isNotBlank(), color = C.Gold) {
                    app.pronounce.add(word, say); word = ""; say = ""
                }
                for (f in list) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.word}  ←  ${f.sayAs}", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.Delete, "מחיקה", tint = C.Sub, modifier = Modifier.size(22.dp).pressable { app.pronounce.remove(f) })
                }
            }
        },
        confirmButton = { SoftButton("סגירה", onClick = close) },
    )
}

/** Make a voice from an audio file: name, file, and whose voice it is. */
@Composable
private fun NewVoiceDialog(app: KarianootApp, states: Map<String, ModelState>, openModels: () -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<android.net.Uri?>(null) }
    var ownVoice by remember { mutableStateOf(false) }
    var female by remember { mutableStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri = it }
    val kitReady = states["clone"] == ModelState.Ready && states["blue_25"] == ModelState.Ready
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("קול חדש מהקלטה", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!kitReady) {
                    Text("צריך להוריד קודם את Blue 2.5 ואת \"ערכת קול מהקלטה\" (117MB) במסך המודלים.", color = C.Text, fontSize = 14.sp)
                    return@Column
                }
                Text("בוחרים הקלטה של 10 שניות ומעלה: דיבור ברור, בלי מוזיקה. הקול החדש ידבר בסגנון של ההקלטה. " +
                    "ההקלטה נשארת בטלפון ולא נשלחת לשום מקום." +
                    (if (states["qwen_he"] == ModelState.Ready) " נוצרות שתי גרסאות: Blue (מהירה) ו-Qwen (הכי טבעית)." else ""),
                    color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x55000000))
                    .border(1.dp, C.Stroke, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    if (name.isEmpty()) Text("שם הקול (למשל: נמר)", color = C.Faint)
                    BasicTextField(name, { name = it }, Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = C.Text, fontSize = 16.sp, fontFamily = Rubik), cursorBrush = SolidColor(C.Gold), singleLine = true)
                }
                Segmented(listOf("קול של גבר", "קול של אישה"), female) { female = it }
                SoftButton(if (uri == null) "בחירת קובץ הקלטה" else "✓ נבחר קובץ (להחלפה)", Modifier.fillMaxWidth(), icon = Icons.Rounded.AudioFile) {
                    pick.launch(arrayOf("audio/*"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ownVoice, { ownVoice = it }, colors = CheckboxDefaults.colors(checkedColor = C.Gold, uncheckedColor = C.Sub, checkmarkColor = Color(0xFF1A1307)))
                    Text("זה הקול שלי, או שבעל הקול הסכים", color = C.Text, fontSize = 14.sp)
                }
                if (!ownVoice) Text("⚠ הקול יסומן \"שימוש אישי בלבד\": זכויות הקול לא ברורות, ולא מכניסים אותו לאפליקציה שנמכרת.",
                    color = C.Amber, fontSize = 12.sp, lineHeight = 17.sp)
            }
        },
        confirmButton = {
            if (!kitReady) SoftButton("למסך המודלים", onClick = openModels)
            else SoftButton("צור קול", enabled = uri != null, color = C.Gold) {
                app.createVoice(name, uri!!, personalOnly = !ownVoice, female = female == 1) { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                close()
            }
        },
        dismissButton = { SoftButton("ביטול", onClick = close) },
    )
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, color = C.Sub, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(value, color = C.Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Slider(v, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = C.GoldLight, activeTrackColor = C.Gold, inactiveTrackColor = Color(0x22FFF4DC)))
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color(0x44000000)).border(1.dp, C.Stroke, CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(Modifier.weight(1f).height(40.dp).clip(CircleShape).then(if (sel) Modifier.background(C.GoldButton) else Modifier).pressable { onSelect(i) },
                contentAlignment = Alignment.Center) {
                Text(o, color = if (sel) Color(0xFF1A1307) else C.Sub, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** Gold bars that breathe while something is playing or being made. */
@Composable
fun Wave(modifier: Modifier, animate: Boolean, bars: Int = 5) {
    val t = loop(1100, 0f, 6.283f, still = 1.2f)
    Canvas(modifier) {
        val w = size.width / (bars * 2 - 1)
        for (i in 0 until bars) {
            val h = size.height * (if (animate) 0.3f + 0.7f * abs(sin(t + i * 0.9f)) else 0.35f + 0.15f * (i % 3))
            drawRoundRect(C.GoldBrush, Offset(i * 2 * w, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
fun CreationCard(c: Creation, app: KarianootApp, history: MutableList<Creation>, highlight: Boolean = false, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val speech = app.speech
    var showParts by remember(c.createdAt) { mutableStateOf(false) }
    val redoing by app.redoing.collectAsState()
    var playing by remember(c) { mutableStateOf(false) }
    var pos by remember(c) { mutableFloatStateOf(0f) }
    var prepared by remember(c) { mutableStateOf(false) }
    val player = remember(c) { MediaPlayer() }
    DisposableEffect(c) { onDispose { player.release() } }
    LaunchedEffect(playing) {
        while (playing) { pos = runCatching { player.currentPosition / player.duration.toFloat() }.getOrDefault(0f); delay(80) }
    }
    Column(Modifier.fillMaxWidth().then(if (highlight) Modifier.border(1.dp, C.GoldBrush, RoundedCornerShape(24.dp)) else Modifier).glass().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "נגן", size = 52.dp, gold = true) {
                if (playing) { player.pause(); playing = false } else {
                    runCatching {
                        if (!prepared) {
                            player.reset(); player.setDataSource(c.file.path); player.prepare(); prepared = true
                            player.setOnCompletionListener { playing = false; pos = 0f; it.seekTo(0) }
                        }
                        player.start(); playing = true
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.text, color = C.Text, fontSize = 15.sp, maxLines = 2)
                Text("${c.voice} · ${formatSeconds(c.seconds)}" + if (c.genSeconds > 0) " · נוצר ב-${formatSeconds(c.genSeconds)}" else "", color = C.Faint, fontSize = 12.sp)
            }
        }
        GoldProgress(pos)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("שתף", icon = Icons.Rounded.Share) {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", c.file)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "שיתוף הקריינות"))
            }
            SoftButton("שמור", icon = Icons.Rounded.SaveAlt) {
                Toast.makeText(ctx, if (speech.exportToMusic(c)) "נשמר בתיקייה Music/Karianoot" else "השמירה נכשלה", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.weight(1f))
            CircleIcon(Icons.Rounded.Delete, "מחק", tint = C.Red, onClick = onDelete)
        }
        if (c.parts.size > 1 && c.voiceId.isNotEmpty())
            SoftButton(if (showParts) "סגירת תיקון משפטים" else "משפט יצא לא טוב? תיקון משפט", Modifier.fillMaxWidth(), icon = Icons.Rounded.Tune,
                color = if (showParts) C.Gold else C.Text) { showParts = !showParts }
        AnimatedVisibility(showParts) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("משפט יצא לא טוב? לחצו ↻ לידו, ורק הוא ייווצר מחדש.", color = C.Faint, fontSize = 12.sp)
                c.parts.forEachIndexed { i, p ->
                    val busyHere = redoing?.first == c.file && redoing?.second == i
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${p.text}", color = C.Text, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        if (busyHere) Wave(Modifier.size(32.dp, 22.dp), animate = true)
                        else CircleIcon(Icons.Rounded.Refresh, "ליצור מחדש", size = 40.dp) {
                            if (playing) { player.pause(); playing = false }
                            app.redoPart(c, i) { n ->
                                if (n == null) Toast.makeText(ctx, "לא הצלחתי ליצור מחדש", Toast.LENGTH_SHORT).show()
                                else {
                                    val k = history.indexOfFirst { it.file == c.file }
                                    if (k >= 0) history[k] = n
                                    Toast.makeText(ctx, "משפט ${i + 1} נוצר מחדש ✨", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(app: KarianootApp, history: MutableList<Creation>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(10.dp))
        GoldText("ההקלטות שלי", 30.sp)
        Text("נשמרות 30 ההקלטות האחרונות.", color = C.Faint, fontSize = 13.sp)
        if (history.isEmpty()) Text("עוד אין הקלטות. כל קריינות שתיצרו תופיע כאן.", color = C.Sub, fontSize = 15.sp)
        history.toList().forEachIndexed { i, c -> Box(Modifier.enter(i.coerceAtMost(8))) { CreationCard(c, app, history) { history.remove(c); app.speech.delete(c) } } }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun ModelsScreen(app: KarianootApp) {
    val models = app.models
    val states by models.states.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(10.dp))
        GoldText("מודלים", 30.sp)
        Text("כל מודל יורד פעם אחת ונשאר בטלפון גם אחרי עדכון של האפליקציה. מקום פנוי: ${formatSize(models.freeSpace())}",
            color = C.Sub, fontSize = 14.sp)
        Catalog.downloadable.forEachIndexed { i, m -> ModelCard(m, states[m.id] ?: ModelState.Missing, models, Modifier.enter(i)) }
        MyVoices(app)
        Text("בקרוב", color = C.Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
        Catalog.soon.forEach { m -> ModelCard(m, ModelState.Missing, models, Modifier) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ModelCard(m: ModelInfo, state: ModelState, models: Models, modifier: Modifier) {
    val soon = m.kind == ModelKind.SOON
    Column(modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.title, color = if (soon) C.Sub else C.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(m.subtitle, color = C.Faint, fontSize = 13.sp)
            }
            if (!soon) Text(formatSize(m.size), color = C.Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("רישיון: ${m.license}", color = C.Faint, fontSize = 12.sp)
        if (soon) return@Column
        when (state) {
            ModelState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ מותקן", color = C.Green, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SoftButton("מחק", icon = Icons.Rounded.Delete, color = C.Red) { models.delete(m) }
            }
            is ModelState.Downloading -> {
                Row {
                    Text(if (state.paused) "ממתין לרשת…" else "מוריד…", color = C.Text, modifier = Modifier.weight(1f))
                    Text("${formatSize(state.done)} / ${formatSize(state.total)}", color = C.Gold, fontSize = 13.sp)
                }
                GoldProgress(state.done.toFloat() / state.total)
                SoftButton("ביטול", icon = Icons.Rounded.Close) { models.cancel(m) }
            }
            is ModelState.Failed -> {
                Text(state.reason, color = C.Red, fontSize = 13.sp)
                GoldButton("נסה שוב", Modifier.fillMaxWidth(), icon = Icons.Rounded.Download) { models.start(m) }
            }
            ModelState.Missing -> GoldButton("הורד", Modifier.fillMaxWidth(), icon = Icons.Rounded.Download) { models.start(m) }
        }
    }
}

@Composable
private fun MyVoices(app: KarianootApp) {
    val custom by app.voices.custom.collectAsState()
    if (custom.isEmpty()) return
    Text("הקולות שלי", color = C.Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
    for (v in custom) Row(Modifier.fillMaxWidth().glass().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(v.name, color = C.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(if (v.personalOnly) "⚠ שימוש אישי בלבד: זכויות הקול לא ברורות" else "הקול שלי / בהסכמה",
                color = if (v.personalOnly) C.Amber else C.Faint, fontSize = 12.sp)
        }
        SoftButton("מחק", icon = Icons.Rounded.Delete, color = C.Red) { app.voices.delete(v) }
    }
}

@Composable
fun AboutScreen(app: KarianootApp) {
    val custom by app.voices.custom.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(10.dp))
        GoldText("אודות", 30.sp)
        Section {
            Text("קריינות מייצרת קול מטקסט בעברית, כולו בתוך הטלפון. שום טקסט או הקלטה לא נשלחים לאינטרנט; " +
                "האינטרנט משמש רק להורדת המודלים.", color = C.Text, fontSize = 15.sp, lineHeight = 22.sp)
        }
        Section(title = "תודה ליוצרי המודלים") {
            for (m in Catalog.all.filter { it.kind != ModelKind.SOON }) {
                Column {
                    Text(m.title, color = C.Gold, fontWeight = FontWeight.SemiBold)
                    Text("${m.credit} · רישיון ${m.license}", color = C.Sub, fontSize = 13.sp)
                    Text("huggingface.co/${m.repo}", color = C.Faint, fontSize = 12.sp, textAlign = TextAlign.Start)
                }
            }
            Text("ONNX Runtime — Microsoft, רישיון MIT", color = C.Sub, fontSize = 13.sp)
            Text("גופן Rubik — רישיון SIL Open Font License", color = C.Sub, fontSize = 13.sp)
        }
        val personal = (Catalog.voices + custom).filter { it.personalOnly }.distinctBy { it.name }
        if (personal.isNotEmpty()) Section(title = "⚠ קולות לשימוש אישי בלבד") {
            Text("הקולות האלה נוצרו מהקלטות שזכויות הקול בהן לא ברורות (למשל קול מ-ElevenLabs). " +
                "מותר להשתמש בהם רק באופן אישי. אם האפליקציה תימכר, צריך למחוק אותם ולא לכלול אותם בה.",
                color = C.Amber, fontSize = 13.sp, lineHeight = 19.sp)
            for (v in personal) Text("• ${v.name}", color = C.Text, fontSize = 14.sp)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** How the voice picker is grouped: by model, best first; the user's own voices last. */
private class VoiceGroup(val title: String, val subtitle: String, val missing: String, val mine: Boolean = false, val match: (VoiceInfo) -> Boolean)

private val VOICE_GROUPS = listOf(
    VoiceGroup("Qwen", "הכי טבעי וחי · איטי יותר", "להורדת Qwen (1.5GB) במסך המודלים") { it.modelId == "qwen_he" && it.customFile == null },
    VoiceGroup("Blue 2.5", "מהיר", "להורדת Blue 2.5 במסך המודלים") { it.modelId == "blue_25" && it.customFile == null },
    VoiceGroup("Blue V3", "רועי ורותם", "להורדת Blue V3 במסך המודלים") { it.modelId == "blue_v3" && it.customFile == null },
    VoiceGroup("הקולות שלי", "מהקלטות", "", mine = true) { it.customFile != null },
)
