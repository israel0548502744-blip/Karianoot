package com.karianoot.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karianoot.app.Catalog
import com.karianoot.app.KarianootApp
import com.karianoot.app.SlipReader
import com.karianoot.engine.SlipText
import com.karianoot.engine.TipText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "צילום טופס": pick a screenshot of a betting slip; the phone reads it (Tesseract), finds the matches, picks and odds,
 * and makes a script from them (and, if wanted, adds them to the tips journal). Nothing is sent anywhere.
 */
@Composable
fun SlipDialog(app: KarianootApp, onScript: (String) -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf<String?>(null) }
    var picks by remember { mutableStateOf<List<TipText.Pick>>(emptyList()) }
    val chosen = remember { mutableStateListOf<Boolean>() }
    var showRaw by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.Default) { SlipReader.read(ctx, uri, app.models.dir(Catalog.ocr)) } }
            reading = false
            r.onSuccess { t ->
                raw = t; picks = SlipText.parse(t)
                chosen.clear(); repeat(picks.size) { chosen.add(true) }
            }.onFailure { Toast.makeText(ctx, "לא הצלחתי לקרוא: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF14120F),
        title = { Text("תסריט מצילום טופס", color = C.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val t = raw
                when {
                    reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Wave(Modifier.size(32.dp, 20.dp), animate = true); Spacer(Modifier.width(10.dp)); Text("קורא את הטופס…", color = C.Text)
                    }
                    t == null -> {
                        Text("בוחרים צילום מסך של הטופס (מאפליקציית ההימורים או מהווינר). הטלפון קורא אותו בעצמו, בלי אינטרנט, " +
                            "ומוצא את המשחקים, ההימורים והיחסים.", color = C.Sub, fontSize = 13.sp, lineHeight = 19.sp)
                        SoftButton("בחירת צילום מסך", Modifier.fillMaxWidth(), icon = Icons.Rounded.PhotoCamera, color = C.Gold) {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                    else -> {
                        if (picks.isEmpty()) Text("לא מצאתי משחקים בצורה \"קבוצה - קבוצה\". אפשר להכניס את הטקסט כמו שהוא ולתקן ביד.",
                            color = C.Amber, fontSize = 13.sp, lineHeight = 19.sp)
                        else Text("מצאתי ${picks.size} משחקים. מסירים את מה שלא צריך:", color = C.Sub, fontSize = 13.sp)
                        picks.forEachIndexed { i, p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(chosen.getOrElse(i) { true }, { chosen[i] = it },
                                    colors = CheckboxDefaults.colors(checkedColor = C.Gold, uncheckedColor = C.Sub, checkmarkColor = Color(0xFF1A1307)))
                                Column {
                                    Text(p.match, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    val line = listOfNotNull(p.pick.ifBlank { null }, p.odds?.let { "יחס " + "%.2f".format(java.util.Locale.US, it) }).joinToString(" · ")
                                    if (line.isNotEmpty()) Text(line, color = C.Faint, fontSize = 12.sp)
                                }
                            }
                        }
                        SoftButton(if (showRaw) "הסתרת מה שנקרא" else "מה בדיוק נקרא מהתמונה", Modifier.fillMaxWidth(), icon = Icons.Rounded.Description) { showRaw = !showRaw }
                        AnimatedVisibility(showRaw) { Text(t, color = C.Faint, fontSize = 12.sp, lineHeight = 17.sp) }
                        SoftButton("צילום אחר", Modifier.fillMaxWidth(), icon = Icons.Rounded.PhotoCamera) {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                }
            }
        },
        confirmButton = {
            val t = raw
            if (t != null && !reading) {
                val sel = picks.filterIndexed { i, _ -> chosen.getOrElse(i) { true } }
                if (sel.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("גם ליומן", icon = Icons.Rounded.SportsSoccer) {
                        sel.forEach { app.tips.add(it.match, it.pick, it.odds) }
                        Toast.makeText(ctx, "נוספו ${sel.size} טיפים ליומן", Toast.LENGTH_SHORT).show()
                    }
                    SoftButton("לתסריט", icon = Icons.Rounded.AutoAwesome, color = C.Gold) {
                        onScript(com.karianoot.engine.ScriptWriter.write(sel.map { com.karianoot.engine.ScriptWriter.Match(it.match, it.pick, it.odds) },
                            seed = (System.currentTimeMillis() % 1000).toInt())); close()
                    }
                } else SoftButton("הטקסט כמו שהוא", color = C.Gold) { onScript(t.trim()); close() }
            } else SoftButton("סגירה", onClick = close)
        },
        dismissButton = { if (raw != null) SoftButton("ביטול", onClick = close) },
    )
}
