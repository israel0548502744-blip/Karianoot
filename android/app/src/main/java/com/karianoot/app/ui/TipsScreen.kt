package com.karianoot.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karianoot.app.KarianootApp
import com.karianoot.app.Tips
import com.karianoot.engine.TipText
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("d.M")

/**
 * "יומן הטיפים": the tips given (typed in, or found in the studio's script), marked as won / lost, with the success
 * rate this week, this month and overall, and a weekly summary script made from them.
 */
@Composable
fun TipsScreen(app: KarianootApp, studioText: () -> String, toStudio: (String) -> Unit) {
    val ctx = LocalContext.current
    val tips by app.tips.list.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<TipText.Pick>?>(null) }
    val today = LocalDate.now()
    val week = tips.filter { !it.day.isBefore(Tips.weekStart(today)) }
    val month = tips.filter { it.day.year == today.year && it.day.month == today.month }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(10.dp))
        GoldText("יומן הטיפים", 30.sp)
        Text("מסמנים כל טיפ כשהמשחק נגמר, ורואים כמה פגעתם. מהיומן יוצא גם תסריט לסרטון סיכום שבועי.", color = C.Faint, fontSize = 13.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("השבוע", Tips.stats(week), Modifier.weight(1f))
            StatCard("החודש", Tips.stats(month), Modifier.weight(1f))
            StatCard("הכל", Tips.stats(tips), Modifier.weight(1f))
        }
        GoldButton("סרטון סיכום שבועי", Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoAwesome) {
            toStudio(Tips.summary(week))
            Toast.makeText(ctx, "התסריט מחכה בסטודיו. אפשר לשנות אותו לפני הקריינות.", Toast.LENGTH_LONG).show()
        }
        val open = tips.filter { it.status == Tips.Status.PENDING }
        if (open.isNotEmpty()) SoftButton("תסריט מ-${open.size} הטיפים הפתוחים", Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoAwesome, color = C.Gold) {
            toStudio(com.karianoot.engine.ScriptWriter.write(open.reversed().map { com.karianoot.engine.ScriptWriter.Match(it.match, it.pick, it.odds) },
                seed = (System.currentTimeMillis() % 1000).toInt()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("הוספת טיפ", Modifier.weight(1f), icon = Icons.Rounded.Add, color = C.Gold) { adding = true }
            SoftButton("מהתסריט בסטודיו", Modifier.weight(1f), icon = Icons.Rounded.Description) {
                val t = TipText.parse(studioText())
                if (t.isEmpty()) Toast.makeText(ctx, "לא מצאתי בתסריט משחקים בצורה \"קבוצה נגד קבוצה\".", Toast.LENGTH_LONG).show() else found = t
            }
        }
        if (tips.isEmpty()) Text("עוד אין טיפים ביומן.", color = C.Sub, fontSize = 15.sp)
        for ((date, list) in tips.groupBy { it.date }) {
            val d = list.first().day
            Text(if (d == today) "היום" else if (d == today.minusDays(1)) "אתמול" else d.format(DAY), color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            for (t in list) TipRow(t, onStatus = { app.tips.set(t, it) }, onDelete = { app.tips.remove(t) })
        }
        Spacer(Modifier.height(20.dp))
    }
    if (adding) AddTipDialog(onAdd = { m, p, o -> app.tips.add(m, p, o); adding = false }) { adding = false }
    found?.let { f -> FoundTipsDialog(f, onAdd = { chosen -> chosen.forEach { app.tips.add(it.match, it.pick, it.odds) }; found = null
        Toast.makeText(ctx, "נוספו ${chosen.size} טיפים ליומן", Toast.LENGTH_SHORT).show() }) { found = null } }
}

@Composable
private fun StatCard(title: String, s: Tips.Stats, modifier: Modifier) {
    Column(modifier.glass(RoundedCornerShape(18.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = C.Sub, fontSize = 12.sp)
        Text(if (s.decided == 0) "—" else "${s.percent}%", color = C.GoldLight, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        Text("${s.won} מתוך ${s.decided}", color = C.Text, fontSize = 12.sp)
        if (s.decided > 0) Text("‎" + (if (s.profit >= 0) "+" else "") + "%.2f".format(s.profit) + "‏ יח׳", color = if (s.profit >= 0) C.Green else C.Red, fontSize = 11.sp)
    }
}

@Composable
private fun TipRow(t: Tips.Tip, onStatus: (Tips.Status) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.match, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val line = listOfNotNull(t.pick.ifBlank { null }, t.odds?.let { "יחס " + "%.2f".format(java.util.Locale.US, it) }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, color = C.Faint, fontSize = 12.sp)
        }
        for ((st, icon, col) in listOf(Triple(Tips.Status.WON, Icons.Rounded.Check, C.Green), Triple(Tips.Status.LOST, Icons.Rounded.Close, C.Red),
            Triple(Tips.Status.PENDING, Icons.Rounded.HourglassEmpty, C.Sub))) StatusDot(icon, col, t.status == st) { onStatus(st) }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).pressable(onClick = onDelete), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Delete, "מחיקה", tint = C.Faint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusDot(icon: ImageVector, color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape)
        .background(if (selected) color.copy(alpha = 0.25f) else Color(0x10FFF4DC))
        .border(1.dp, if (selected) color else C.Stroke, CircleShape).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (selected) color else C.Faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AddTipDialog(onAdd: (String, String, Float?) -> Unit, close: () -> Unit) {
    var match by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf("") }
    var odds by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("טיפ חדש", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldBox(match, "המשחק (למשל: ארסנל נגד צ'לסי)") { match = it }
                FieldBox(pick, "ההימור (למשל: ניצחון ארסנל)") { pick = it }
                FieldBox(odds, "יחס (למשל: 2.10)") { odds = it.filter { c -> c.isDigit() || c == '.' }.take(6) }
            }
        },
        confirmButton = { SoftButton("הוספה", icon = Icons.Rounded.Add, color = C.Gold, enabled = match.isNotBlank()) { onAdd(match, pick, odds.toFloatOrNull()) } },
        dismissButton = { SoftButton("ביטול", onClick = close) },
    )
}

@Composable
private fun FoundTipsDialog(found: List<TipText.Pick>, onAdd: (List<TipText.Pick>) -> Unit, close: () -> Unit) {
    val chosen = remember { mutableStateListOf<Boolean>().apply { repeat(found.size) { add(true) } } }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("טיפים מהתסריט", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("אלה המשחקים שמצאתי. מסירים את מה שלא צריך.", color = C.Sub, fontSize = 13.sp)
                found.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(chosen[i], { chosen[i] = it }, colors = CheckboxDefaults.colors(checkedColor = C.Gold, uncheckedColor = C.Sub, checkmarkColor = Color(0xFF1A1307)))
                        Column {
                            Text(t.match, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            val line = listOfNotNull(t.pick.ifBlank { null }, t.odds?.let { "יחס " + "%.2f".format(java.util.Locale.US, it) }).joinToString(" · ")
                            if (line.isNotEmpty()) Text(line, color = C.Faint, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { SoftButton("הוספה ליומן", icon = Icons.Rounded.Add, color = C.Gold, enabled = chosen.any { it }) { onAdd(found.filterIndexed { i, _ -> chosen[i] }) } },
        dismissButton = { SoftButton("ביטול", onClick = close) },
    )
}

/** "התור": narrations waiting to be made one after another (for the night). */
@Composable
fun QueueDialog(app: KarianootApp, close: () -> Unit) {
    val q by app.queue.collectAsState()
    val running by app.queueRunning.collectAsState()
    var charging by remember { mutableStateOf(app.queueWhenCharging) }
    val all = remember { (com.karianoot.app.Catalog.voices + app.voices.custom.value).map(app.voiceOrder::named) }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("תור הקריינויות", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("כל קריינות בתור נוצרת אחרי הקודמת, גם כשהמסך כבוי. בבוקר הכל מחכה בהקלטות.", color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                SettingSwitch("רק כשהטלפון בטעינה", "התור מחכה למטען, ואז מתחיל לבד.", charging) { charging = it; app.queueWhenCharging = it }
                if (q.isEmpty()) Text("התור ריק. בסטודיו לוחצים \"לתור\" כדי להוסיף.", color = C.Faint, fontSize = 13.sp)
                q.forEachIndexed { i, item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFF4DC)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", color = C.Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.text, color = C.Text, fontSize = 14.sp, maxLines = 2)
                            Text(all.firstOrNull { it.id == item.voiceId }?.name ?: "קול שנמחק", color = C.Faint, fontSize = 12.sp)
                        }
                        if (!(running && i == 0)) CircleIcon(Icons.Rounded.Delete, "הסרה", tint = C.Sub, size = 38.dp) { app.unqueue(item) }
                    }
                }
            }
        },
        confirmButton = {
            if (running) SoftButton("עצירת התור", icon = Icons.Rounded.Stop, color = C.Red) { app.stopQueue() }
            else SoftButton("הפעלת התור", icon = Icons.Rounded.PlayArrow, color = C.Gold, enabled = q.isNotEmpty() && !app.job.value.busy) { app.startQueue(); close() }
        },
        dismissButton = { SoftButton("סגירה", onClick = close) },
    )
}
