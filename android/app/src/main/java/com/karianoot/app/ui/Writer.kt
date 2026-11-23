package com.karianoot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karianoot.engine.ScriptWriter

/** One match being typed into the script writer. */
class Row4(match: String = "", pick: String = "", odds: String = "", reason: String = "") {
    var match by mutableStateOf(match); var pick by mutableStateOf(pick); var odds by mutableStateOf(odds); var reason by mutableStateOf(reason)
    fun toMatch() = ScriptWriter.Match(match.trim(), pick.trim(), odds.replace(',', '.').toFloatOrNull(), reason.trim())
}

/**
 * "כותב תסריטים": the matches (teams, pick, odds, why) -> a finished script in one of three styles, with other wording on
 * every "עוד גרסה". Hand-written phrases, so the Hebrew is always right.
 */
@Composable
fun WriterDialog(start: List<ScriptWriter.Match> = emptyList(), onScript: (String) -> Unit, close: () -> Unit) {
    val rows = remember { mutableStateListOf<Row4>().apply {
        if (start.isEmpty()) add(Row4()) else start.forEach { add(Row4(it.match, it.pick, it.odds?.let { o -> "%.2f".format(java.util.Locale.US, o) } ?: "", it.reason)) }
    } }
    var style by remember { mutableIntStateOf(0) }
    var seed by remember { mutableIntStateOf((System.currentTimeMillis() % 1000).toInt()) }
    var preview by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("כותב תסריטים", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = preview
                if (p != null) {
                    Text(p, color = C.Text, fontSize = 15.sp, lineHeight = 23.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x22FFF4DC)).padding(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("עוד גרסה", Modifier.weight(1f), icon = Icons.Rounded.Refresh) {
                            seed++; preview = ScriptWriter.write(rows.map { it.toMatch() }, ScriptWriter.Style.entries[style], seed)
                        }
                        SoftButton("עריכת המשחקים", Modifier.weight(1f)) { preview = null }
                    }
                    return@Column
                }
                Text("ממלאים משחק, הימור ויחס. אפשר להוסיף גם נימוק. התסריט נכתב לבד.", color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                rows.forEachIndexed { i, r ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.Stroke, RoundedCornerShape(16.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("משחק ${i + 1}", color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (rows.size > 1) CircleIcon(Icons.Rounded.Delete, "הסרה", size = 34.dp, tint = C.Sub) { rows.removeAt(i) }
                        }
                        FieldBox(r.match, "ארסנל נגד צ'לסי") { r.match = it }
                        FieldBox(r.pick, "ההימור: ניצחון ארסנל") { r.pick = it }
                        FieldBox(r.odds, "יחס: 2.10") { r.odds = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }
                        FieldBox(r.reason, "נימוק (לא חובה)", singleLine = false) { r.reason = it }
                    }
                }
                SoftButton("עוד משחק", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) { rows.add(Row4()) }
                Text("סגנון", color = C.Sub, fontSize = 14.sp)
                Segmented(listOf("מתלהב", "רגוע", "דרמטי"), style) { style = it }
            }
        },
        confirmButton = {
            val p = preview
            if (p == null) SoftButton("כתוב", icon = Icons.Rounded.AutoAwesome, color = C.Gold, enabled = rows.any { it.match.isNotBlank() }) {
                preview = ScriptWriter.write(rows.map { it.toMatch() }, ScriptWriter.Style.entries[style], seed)
            } else SoftButton("לסטודיו", icon = Icons.Rounded.AutoAwesome, color = C.Gold) { onScript(p); close() }
        },
        dismissButton = { SoftButton("ביטול", onClick = close) },
    )
}
