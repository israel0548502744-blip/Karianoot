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
fun App(models: Models, speech: Speech, sharedText: String?) {
    var tab by remember { mutableStateOf(Tab.Studio) }
    val history = remember { mutableStateListOf<Creation>().apply { addAll(speech.history()) } }
    LaunchedEffect(Unit) { models.watch() }
    KarianootTheme {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            NightBackground()
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Studio -> StudioScreen(models, speech, sharedText, history) { tab = Tab.Models }
                        Tab.Library -> LibraryScreen(speech, history)
                        Tab.Models -> ModelsScreen(models)
                        Tab.About -> AboutScreen()
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
fun StudioScreen(models: Models, speech: Speech, sharedText: String?, history: MutableList<Creation>, openModels: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by models.states.collectAsState()
    var text by remember { mutableStateOf(sharedText ?: "") }
    val ready = Catalog.voices.filter { states[it.modelId] == ModelState.Ready }
    var voiceId by remember { mutableStateOf(Catalog.voices.first().id) }
    val voice = ready.firstOrNull { it.id == voiceId } ?: ready.firstOrNull()
    var speed by remember { mutableFloatStateOf(1.0f) }
    var quality by remember { mutableStateOf(1) } // 0 fast, 1 good, 2 best
    var pause by remember { mutableFloatStateOf(0.15f) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var last by remember { mutableStateOf<Creation?>(null) }
    val g2pReady = states["g2p"] == ModelState.Ready

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
                    Text("כדי להתחיל, מורידים פעם אחת את מנוע ההגייה וקול אחד (בערך 700MB).", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
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
            if (ready.isEmpty()) Text("אין עדיין קולות מותקנים.", color = C.Faint, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (v in ready) VoiceChip(v, v.id == voice?.id) { voiceId = v.id }
            }
        }

        Section(Modifier.enter(4), title = "כיוונון") {
            LabeledSlider("מהירות", "×%.2f".format(speed), speed, 0.7f..1.4f) { speed = it }
            LabeledSlider("הפסקה בין משפטים", "%.2f שנ׳".format(pause), pause, 0f..0.8f) { pause = it }
            Text("איכות", color = C.Sub, fontSize = 14.sp)
            Segmented(listOf("מהיר", "איכותי", "מקסימום"), quality) { quality = it }
        }

        AnimatedVisibility(busy, enter = fadeIn(), exit = fadeOut()) {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wave(Modifier.size(42.dp, 28.dp), animate = true)
                    Spacer(Modifier.width(12.dp))
                    Text(stage, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${(progress * 100).toInt()}%", color = C.Gold, fontWeight = FontWeight.Bold)
                }
                GoldProgress(progress)
            }
        }

        GoldButton(if (busy) "מקריא…" else "צור קריינות", Modifier.fillMaxWidth().enter(5), icon = Icons.Rounded.AutoAwesome,
            enabled = !busy && g2pReady && voice != null && text.isNotBlank()) {
            val v = voice ?: return@GoldButton
            busy = true; progress = 0f
            scope.launch {
                runCatching {
                    speech.speak(text, v, BlueTts.Options(steps = intArrayOf(5, 10, 16)[quality], speed = speed, silence = pause)) { s, p ->
                        stage = s; progress = p
                    }
                }.onSuccess { c -> last = c; history.add(0, c) }
                    .onFailure { e -> Toast.makeText(ctx, "שגיאה: ${e.message}", Toast.LENGTH_LONG).show() }
                busy = false
            }
        }

        last?.let { CreationCard(it, speech, highlight = true) { history.remove(it); speech.delete(it); last = null } }
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
            Text(v.name.take(1), color = if (selected) Color(0xFF1A1307) else C.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(v.name, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(v.note, color = C.Faint, fontSize = 11.sp)
    }
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
    val t by rememberInfiniteTransition(label = "wave").animateFloat(0f, 6.283f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val w = size.width / (bars * 2 - 1)
        for (i in 0 until bars) {
            val h = size.height * (if (animate) 0.3f + 0.7f * abs(sin(t + i * 0.9f)) else 0.35f + 0.15f * (i % 3))
            drawRoundRect(C.GoldBrush, Offset(i * 2 * w, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
fun CreationCard(c: Creation, speech: Speech, highlight: Boolean = false, onDelete: () -> Unit) {
    val ctx = LocalContext.current
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
    }
}

@Composable
fun LibraryScreen(speech: Speech, history: MutableList<Creation>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(10.dp))
        GoldText("ההקלטות שלי", 30.sp)
        if (history.isEmpty()) Text("עוד אין הקלטות. כל קריינות שתיצרו תופיע כאן.", color = C.Sub, fontSize = 15.sp)
        history.toList().forEachIndexed { i, c -> Box(Modifier.enter(i.coerceAtMost(8))) { CreationCard(c, speech) { history.remove(c); speech.delete(c) } } }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun ModelsScreen(models: Models) {
    val states by models.states.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(10.dp))
        GoldText("מודלים", 30.sp)
        Text("כל מודל יורד פעם אחת ונשאר בטלפון גם אחרי עדכון של האפליקציה. מקום פנוי: ${formatSize(models.freeSpace())}",
            color = C.Sub, fontSize = 14.sp)
        Catalog.downloadable.forEachIndexed { i, m -> ModelCard(m, states[m.id] ?: ModelState.Missing, models, Modifier.enter(i)) }
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
fun AboutScreen() {
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
        Spacer(Modifier.height(20.dp))
    }
}
