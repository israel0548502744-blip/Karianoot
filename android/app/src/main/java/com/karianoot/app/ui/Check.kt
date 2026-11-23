package com.karianoot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karianoot.app.Creation
import com.karianoot.app.KarianootApp
import com.karianoot.app.Speech

/**
 * The result of "בדיקת הקריינות": sentences where the recognizer missed words, each with ↻ to make it again.
 * The recognizer also mishears names and numbers, so it says "worth listening to", not "wrong".
 */
@Composable
fun CheckDialog(app: KarianootApp, c: Creation, result: List<Speech.Check>?, onRedo: (Int) -> Unit, close: () -> Unit) {
    val checking by app.checking.collectAsState()
    val redoing by app.redoing.collectAsState()
    AlertDialog(
        onDismissRequest = { if (checking == null) close() },
        containerColor = Color(0xFF14120F),
        title = { Text("בדיקת הקריינות", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val ch = checking
                when {
                    ch != null && ch.first == c.file -> {
                        Text("הטלפון מקשיב לקריינות… ${(ch.second * 100).toInt()}%", color = C.Text, fontSize = 15.sp)
                        GoldProgress(ch.second)
                    }
                    result == null -> Text("לא הצלחתי לבדוק.", color = C.Red)
                    else -> {
                        val bad = result.filter { it.missed.isNotEmpty() }
                        if (bad.isEmpty()) Text("✓ כל ${result.size} המשפטים נשמעו כמו שצריך.", color = C.Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        else Text("${bad.size} מתוך ${result.size} משפטים כדאי לשמוע שוב. לפעמים זה רק הבודק שלא מכיר שם, אז האוזן שלך מחליטה.",
                            color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                        for (r in bad) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFF4DC))
                            .border(1.dp, C.Stroke, RoundedCornerShape(14.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("משפט ${r.index + 1}", color = C.Gold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                val busyHere = redoing?.first == c.file && redoing?.second == r.index
                                if (busyHere) Wave(Modifier.size(30.dp, 20.dp), animate = true)
                                else if (c.voiceId.isNotEmpty()) CircleIcon(Icons.Rounded.Refresh, "ליצור מחדש", size = 38.dp) { onRedo(r.index) }
                            }
                            Text(r.text, color = C.Text, fontSize = 14.sp)
                            Text("נשמע: ${r.heard}", color = C.Faint, fontSize = 12.sp)
                            Text("כדאי לבדוק: " + r.missed.joinToString(", ") { it.ref }, color = C.Amber, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { if (checking == null) SoftButton("סגירה", onClick = close) },
    )
}
