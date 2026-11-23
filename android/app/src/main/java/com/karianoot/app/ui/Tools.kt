package com.karianoot.app.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.karianoot.app.Creation
import com.karianoot.app.KarianootApp
import com.karianoot.app.Polish
import com.karianoot.engine.Script
import com.karianoot.engine.Sfx
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Markers ({שריקה}, {הפסקה 2}) in gold and template blanks ([קבוצה]) in amber, inside the text box. */
val MarkerHighlight = VisualTransformation { t ->
    val s = t.text
    val out = buildAnnotatedString {
        append(s)
        for (m in Script.MARKER.findAll(s)) addStyle(SpanStyle(color = C.Gold, fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
        for (m in Regex("\\[[^\\]]{1,20}]").findAll(s)) addStyle(SpanStyle(color = C.Amber), m.range.first, m.range.last + 1)
    }
    TransformedText(out, OffsetMapping.Identity)
}

/** [marker] put at the cursor of [v], with a space on each side when needed. */
fun insertAtCursor(v: TextFieldValue, marker: String): TextFieldValue {
    val s = v.text
    val at = v.selection.end.coerceIn(0, s.length)
    val before = if (at > 0 && !s[at - 1].isWhitespace()) " " else ""
    val after = if (at < s.length && !s[at].isWhitespace()) " " else ""
    val ins = before + marker + after
    return TextFieldValue(s.substring(0, at) + ins + s.substring(at), TextRange(at + ins.length))
}

@Composable
private fun DialogBox(title: String, close: () -> Unit, confirm: @Composable () -> Unit = { SoftButton("סגירה", onClick = close) },
                      dismiss: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text(title, color = C.Gold, fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22FFF4DC))
        .border(1.dp, C.Stroke, RoundedCornerShape(14.dp)).then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier).padding(12.dp)) {
        content()
    }
}

/** Plays a file once (one shared player per dialog). */
@Composable
private fun rememberPlayer(): (java.io.File) -> Unit {
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    return { f -> runCatching { player.reset(); player.setDataSource(f.path); player.prepare(); player.start() } }
}

/** "אפקט / הפסקה": a pause of a chosen length or a football sound effect, put where the cursor is. */
@Composable
fun EffectsDialog(app: KarianootApp, onInsert: (String) -> Unit, close: () -> Unit) {
    val play = rememberPlayer()
    DialogBox("אפקטים והפסקות", close) {
        Text("נכנס במקום שבו הסמן עומד בטקסט. בטקסט זה נראה כך: {שריקה}. אפשר גם להקליד לבד.", color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
        Text("הפסקה", color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, m) in listOf("חצי שנייה" to "{הפסקה 0.5}", "שנייה" to "{הפסקה}", "שתי שניות" to "{הפסקה 2}", "שלוש שניות" to "{הפסקה 3}"))
                SoftButton(label, icon = Icons.Rounded.Add) { onInsert(m); close() }
        }
        Text("אפקטים", color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        for (name in Sfx.NAMES.keys) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            CircleIcon(Icons.Rounded.PlayArrow, "השמעה", size = 40.dp) { play(app.speech.effectSample(name)) }
            Spacer(Modifier.width(8.dp))
            SoftButton("הוספה", icon = Icons.Rounded.Add, color = C.Gold) { onInsert("{$name}"); close() }
        }
        Text("כל האפקטים נוצרים בתוך האפליקציה (בלי הקלטות של אחרים), כך שמותר להשתמש בהם בכל סרטון.", color = C.Faint, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Openers that make people stop scrolling, picked three at a time. */
val HOOKS = listOf(
    "עצרו הכל! יש לי היום טופס שאתם לא רוצים לפספס.",
    "אם הייתם צריכים לשים רק משחק אחד הערב, זה המשחק.",
    "שלושה משחקים, יחס כולל מטורף, ונימוק לכל אחד. בואו נתחיל.",
    "אתמול פגענו. היום אנחנו הולכים על עוד.",
    "את הטיפ הזה רוב האנשים לא רואים. אני כן.",
    "הבנקר של השבוע כבר כאן, ותקשיבו עד הסוף.",
    "ערב כדורגל ענק מחכה לנו, ויש לי בשבילכם את הטופס המושלם.",
    "רגע לפני שהמשחקים מתחילים, הנה כל מה שאתם צריכים לדעת.",
    "יום המזל שלכם מתחיל עכשיו.",
    "טופס אחד, ארבעה משחקים, ויחס שיגרום לכם לחייך.",
    "אל תגללו! הטיפ הזה יכול לשנות לכם את סוף השבוע.",
    "כולם מדברים על המשחק הגדול, אבל הכסף נמצא במשחק אחר.",
    "תשמרו את הסרטון הזה, אתם תודו לי אחר כך.",
    "הקבוצה הזאת לא הפסידה כבר חודש, והיחס עליה עדיין גבוה. איך זה הגיוני?",
    "זה ההימור הכי בטוח שלי השבוע.",
    "ליגת האלופות חוזרת, ואני מגיע עם טופס מוכן.",
    "שלושה טיפים, שתי דקות, אפס בלבול.",
    "אם אתם חדשים פה, תעקבו. כל יום אני מביא טופס חדש.",
    "בואו נדבר על ההפתעה של הערב.",
    "יש משחק אחד הערב שהיחס בו פשוט טעות. בואו אסביר לכם.",
    "הסטטיסטיקה לא משקרת, ותכף תראו למה.",
    "מוכנים? כי הערב אנחנו הולכים על הכל.",
    "הטופס של היום נבנה בזהירות, משחק אחרי משחק.",
    "חמישה ניצחונות ברצף. היום אנחנו מנסים שישה.",
    "שמעתם על הקבוצה הזאת? אחרי הערב כולם ישמעו.",
    "מי שהיה איתנו אתמול כבר יודע. מי שלא, זה הזמן להצטרף.",
    "תנו לי דקה אחת, ואני אחסוך לכם שעה של התלבטויות.",
    "הערב יש דרבי, ובדרבי הכל יכול לקרות. כמעט הכל.",
)

/** Building blocks for a tips video script; [brackets] are for the user to fill in. */
private val BLOCKS = listOf(
    "פתיחה" to "ערב טוב לכל האוהדים! היום יש לנו [מספר] משחקים חמים, ואני הולך להסביר לכם בדיוק על מה לשים.",
    "משחק" to "המשחק הראשון: [קבוצה] נגד [קבוצה]. אני הולך על [הימור], ביחס של [יחס].",
    "נימוק" to "למה? כי [קבוצה] ניצחה ב-[מספר] מתוך חמשת המשחקים האחרונים שלה, והיא פשוט בכושר מטורף.",
    "עוד משחק" to "ועכשיו למשחק השני: [קבוצה] מול [קבוצה]. פה אני ממליץ על [הימור], והיחס [יחס].",
    "בנקר" to "והבנקר של הערב: [קבוצה]. פה אני לא רואה שום הפתעה.",
    "הימור נועז" to "ועכשיו ההימור הנועז: [קבוצה] מול [קבוצה]. אני הולך על [הימור] ביחס [יחס]. סיכון קטן, פוטנציאל גדול.",
    "שערים" to "במשחק בין [קבוצה] ל[קבוצה] אני הולך על מעל [מספר] שערים. שתי ההתקפות בכושר, וההגנות פחות.",
    "שתיהן מבקיעות" to "[קבוצה] נגד [קבוצה]: שתי הקבוצות מבקיעות. בחמשת המפגשים האחרונים ביניהן זה קרה כמעט תמיד.",
    "סיכום" to "ביחד זה יוצא טופס ביחס כולל של [יחס]. אל תפספסו את זה!",
    "שני פרשנים" to "א: אז מה אתה אומר על [קבוצה] נגד [קבוצה]?\nב: אני הולך על [הימור], ביחס [יחס].\nא: מעניין. ולמה דווקא זה?\nב: כי [קבוצה] בכושר מטורף.",
)

private val CLOSERS = listOf(
    "מי שעוד לא הצטרף לקבוצה, זה הזמן. בהצלחה לכולם, ונתראה במשחק הבא!",
    "אם אהבתם, תנו לייק ותעקבו. מחר יש עוד.",
    "כתבו לי בתגובות על מה אתם הולכים הערב.",
    "שתפו את החבר שתמיד מפסיד בטופס.",
    "תזכרו: שמים רק מה שמוכנים להפסיד. בהצלחה!",
    "נתראה מחר עם טופס חדש. לילה טוב!",
    "הקישור לקבוצה בביו. בואו להיות חלק מהמשפחה.",
)

/** The user's own templates (text they saved), in prefs "templates". */
class MyTemplates(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("templates", Context.MODE_PRIVATE)
    fun list(): List<Pair<String, String>> = runCatching {
        val a = JSONArray(prefs.getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("t") to o.getString("x") } }
    }.getOrDefault(emptyList())
    fun save(list: List<Pair<String, String>>) {
        prefs.edit().putString("list", JSONArray().also { a -> list.forEach { (t, x) -> a.put(JSONObject().put("t", t).put("x", x)) } }.toString()).apply()
    }
}

/** "תבניות ופתיחים": three fresh openers at a time, building blocks, closers, and the user's own saved templates. */
@Composable
fun TemplatesDialog(app: KarianootApp, current: String, onPick: (String) -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val mine = remember { MyTemplates(ctx) }
    val saved = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(mine.list()) } }
    var tab by remember { mutableStateOf(0) }
    var hooks by remember { mutableStateOf(HOOKS.shuffled().take(3)) }
    DialogBox("תבניות ופתיחים", close) {
        Segmented(listOf("פתיחים", "בניין", "סגירה", "שלי"), tab) { tab = it }
        when (tab) {
            0 -> {
                Text("משפט ראשון שתופס. לוחצים כדי להוסיף, או \"עוד שלושה\" לפתיחים אחרים.", color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                for (h in hooks) Card({ onPick(h); close() }) { Text(h, color = C.Text, fontSize = 15.sp, lineHeight = 21.sp) }
                SoftButton("עוד שלושה", Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh) {
                    hooks = (HOOKS - hooks.toSet()).shuffled().take(3)
                }
            }
            1 -> {
                Text("לוחצים על משפט והוא נוסף לטקסט. מחליפים את מה שבסוגריים [כמו כאן] בפרטים של המשחק.", color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                for ((title, t) in BLOCKS) Card({ onPick(t) }) {
                    Text(title, color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(t, color = C.Text, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            2 -> for (t in CLOSERS) Card({ onPick(t); close() }) { Text(t, color = C.Text, fontSize = 15.sp, lineHeight = 21.sp) }
            else -> {
                SoftButton("שמירת הטקסט שלי כתבנית", Modifier.fillMaxWidth(), icon = Icons.Rounded.Save, color = C.Gold, enabled = current.isNotBlank()) {
                    val title = current.trim().lineSequence().first().take(28)
                    saved.add(0, title to current.trim()); mine.save(saved)
                    Toast.makeText(ctx, "נשמר בתבניות שלי", Toast.LENGTH_SHORT).show()
                }
                if (saved.isEmpty()) Text("עוד אין תבניות שלך. כותבים סרטון פעם אחת, שומרים, ומחר רק מחליפים משחקים.", color = C.Faint, fontSize = 13.sp, lineHeight = 19.sp)
                for ((i, st) in saved.withIndex()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Card({ onPick(st.second); close() }) {
                        Text(st.first, color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(st.second, color = C.Text, fontSize = 13.sp, maxLines = 3)
                    } }
                    Spacer(Modifier.width(6.dp))
                    CircleIcon(Icons.Rounded.Delete, "מחיקה", tint = C.Sub, size = 40.dp) { saved.removeAt(i); mine.save(saved) }
                }
            }
        }
    }
}

/** A ready mix of speed, sentence pause and liveliness. */
data class Persona(val id: String, val name: String, val speed: Float, val pause: Float, val liveliness: Float)

val PERSONAS = listOf(
    Persona("veteran", "פרשן ותיק", 0.95f, 0.45f, 1.0f),
    Persona("hype", "צעיר מתלהב", 1.12f, 0.22f, 1.35f),
    Persona("news", "קריין חדשות", 1.0f, 0.35f, 0.9f),
    Persona("drama", "דרמטי", 0.9f, 0.6f, 1.2f),
    Persona("tiktok", "קצבי לטיקטוק", 1.2f, 0.18f, 1.2f),
)

@Composable
fun PersonaRow(selected: String?, onPick: (Persona) -> Unit) {
    Text("סגנון מוכן", color = C.Sub, fontSize = 14.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in PERSONAS) {
            val sel = p.id == selected
            Box(Modifier.clip(CircleShape).then(if (sel) Modifier.background(C.GoldButton) else Modifier.background(Color(0x14FFF4DC)))
                .border(1.dp, if (sel) Color.Transparent else C.Stroke, CircleShape).pressable { onPick(p) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(p.name, color = if (sel) Color(0xFF1A1307) else C.Text, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().pressable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = C.Faint, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF1A1307),
            checkedTrackColor = C.Gold, uncheckedThumbColor = C.Sub, uncheckedTrackColor = Color(0x22FFF4DC), uncheckedBorderColor = C.Stroke))
    }
}

/** "אורך מדויק": the narration comes out exactly this long (the pace changes, the pitch does not). */
@Composable
fun FitLength(fit: Boolean, target: Float, onFit: (Boolean) -> Unit, onTarget: (Float) -> Unit) {
    SettingSwitch("אורך מדויק", "הקצב משתנה כדי שהקריינות תצא בדיוק באורך הזה (עד כ-30% מהר או לאט יותר).", fit, onFit)
    if (fit) LabeledSlider("אורך", "${target.toInt()} שנ׳", target, 10f..180f) { onTarget((it / 1f).toInt().toFloat()) }
}

/** The text field look used in dialogs. */
@Composable
fun FieldBox(value: String, hint: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x55000000))
        .border(1.dp, C.Stroke, RoundedCornerShape(14.dp)).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, color = C.Faint)
        BasicTextField(value, onChange, Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = C.Text, fontSize = 16.sp, fontFamily = Rubik), cursorBrush = SolidColor(C.Gold), singleLine = singleLine)
    }
}

/** Settings: the "responsible gambling" closing line. */
@Composable
fun ResponsiblePanel(app: KarianootApp) {
    var on by remember { mutableStateOf(app.responsible) }
    var line by remember { mutableStateOf(app.responsibleLine) }
    SettingSwitch("שורת \"הימור אחראי\" בסוף", "בסוף כל קריינות נקראת שורה קבועה. טיקטוק ואינסטגרם מורידים פחות סרטוני הימורים שיש בהם אזהרה.", on) {
        on = it; app.responsible = it
    }
    if (on) {
        FieldBox(line, "הנוסח", singleLine = false) { line = it; app.responsibleLine = it }
        if (line != KarianootApp.RESPONSIBLE_DEFAULT) SoftButton("חזרה לנוסח הרגיל", Modifier.fillMaxWidth()) {
            line = KarianootApp.RESPONSIBLE_DEFAULT; app.responsibleLine = line
        }
    }
}

/** Settings: names downloaded after the app was built. */
@Composable
fun LexiconPanel(app: KarianootApp) {
    val scope = rememberCoroutineScope()
    val st by app.lexicon.status.collectAsState()
    var checking by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Text("שחקנים וקבוצות חדשים נוספים למילון ההגייה דרך האינטרנט, בלי לעדכן את האפליקציה.", color = C.Faint, fontSize = 13.sp, lineHeight = 18.sp)
    Text(if (st.first == 0) "עוד לא ירדה רשימה" else "רשימה ${st.first}: ${st.second} שמות חדשים", color = C.Sub, fontSize = 14.sp)
    SoftButton(if (checking) "בודק…" else "בדיקה עכשיו", Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, enabled = !checking) {
        checking = true
        scope.launch {
            val ok = app.lexicon.refresh(force = true)
            checking = false
            Toast.makeText(ctx, if (ok) "המילון מעודכן" else "אין חיבור. ננסה שוב מחר לבד.", Toast.LENGTH_SHORT).show()
        }
    }
}

/** "עריכת סאונד": studio sound, an effect before and after, background music that steps aside for the words. */
@Composable
fun PolishDialog(app: KarianootApp, c: Creation, onMake: (Polish) -> Unit, close: () -> Unit) {
    var studio by remember { mutableStateOf(true) }
    var intro by remember { mutableStateOf<String?>(null) }
    var outro by remember { mutableStateOf<String?>(null) }
    var music by remember { mutableStateOf<android.net.Uri?>(null) }
    var level by remember { mutableFloatStateOf(0.15f) }
    var duck by remember { mutableStateOf(true) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { music = it }
    val play = rememberPlayer()
    @Composable fun EffectPick(title: String, value: String?, onPick: (String?) -> Unit) {
        Text(title, color = C.Sub, fontSize = 14.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in listOf<String?>(null) + Sfx.NAMES.keys) {
                val sel = name == value
                Box(Modifier.clip(CircleShape).then(if (sel) Modifier.background(C.GoldButton) else Modifier.background(Color(0x14FFF4DC)))
                    .border(1.dp, if (sel) Color.Transparent else C.Stroke, CircleShape)
                    .pressable { onPick(name); if (name != null) play(app.speech.effectSample(name)) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(name ?: "בלי", color = if (sel) Color(0xFF1A1307) else C.Text, fontSize = 13.sp)
                }
            }
        }
    }
    DialogBox("עריכת סאונד", close,
        confirm = { SoftButton("צור גרסה", icon = Icons.Rounded.Check, color = C.Gold, enabled = studio || music != null || intro != null || outro != null) {
            onMake(Polish(music, level, duck, studio && !c.studio, intro, outro))
        } },
        dismiss = { SoftButton("ביטול", onClick = close) }) {
        Text("נוצרת הקלטה חדשה, והמקורית נשארת.", color = C.Faint, fontSize = 13.sp)
        if (c.studio) Text("✓ להקלטה הזאת כבר יש סאונד אולפן.", color = C.Green, fontSize = 13.sp)
        else SettingSwitch("סאונד אולפן", "קול נקי וקרוב, ועוצמה אחידה בגובה שטיקטוק ואינסטגרם מנגנים.", studio) { studio = it }
        EffectPick("אפקט בפתיחה", intro) { intro = it }
        EffectPick("אפקט בסיום", outro) { outro = it }
        Text("מוזיקת רקע", color = C.Sub, fontSize = 14.sp)
        SoftButton(if (music == null) "בחירת שיר או ביט מהטלפון" else "✓ נבחר קובץ (לחצו להחלפה)", Modifier.fillMaxWidth(), icon = Icons.Rounded.MusicNote) {
            pick.launch(arrayOf("audio/*"))
        }
        if (music != null) {
            LabeledSlider("עוצמת המוזיקה", "${(level * 100).toInt()}%", level, 0.05f..0.4f) { level = it }
            SettingSwitch("המוזיקה מפנה מקום לדיבור", "חלשה כשמדברים, עולה בהפסקות ובסוף.", duck) { duck = it }
        }
    }
}

/** Share a narration: straight to WhatsApp or Telegram, or any app. */
@Composable
fun ShareDialog(c: Creation, close: () -> Unit) {
    val ctx = LocalContext.current
    fun send(pkg: String?) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", c.file)
        val i = Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val ok = if (pkg != null) runCatching { ctx.startActivity(Intent(i).setPackage(pkg)) }.isSuccess else false
        if (!ok) {
            if (pkg != null) Toast.makeText(ctx, "האפליקציה לא מותקנת. בחרו אחרת.", Toast.LENGTH_SHORT).show()
            ctx.startActivity(Intent.createChooser(i, "שיתוף הקריינות"))
        }
        close()
    }
    DialogBox("שיתוף", close) {
        SoftButton("וואטסאפ", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share, color = C.Gold) { send("com.whatsapp") }
        SoftButton("וואטסאפ עסקי", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share) { send("com.whatsapp.w4b") }
        SoftButton("טלגרם", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share) { send("org.telegram.messenger") }
        SoftButton("אפליקציה אחרת…", Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoAwesome) { send(null) }
    }
}
