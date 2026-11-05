package com.karianoot.app

import android.content.Context
import android.net.Uri
import com.karianoot.engine.VoiceCloner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The voices the user made from recordings, kept on the phone only. */
class Voices(private val ctx: Context, private val models: Models) {
    private val dir = File(ctx.getExternalFilesDir(null), "voices").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val _custom = MutableStateFlow(load())
    val custom: StateFlow<List<VoiceInfo>> = _custom

    private fun load(): List<VoiceInfo> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val f = File(dir, o.getString("file"))
            if (!f.exists()) null else VoiceInfo(o.getString("id"), "blue_25", "", o.getString("name"),
                if (o.getBoolean("personal")) "שימוש אישי" else "הקול שלי", female = false,
                customFile = f, personalOnly = o.getBoolean("personal"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<VoiceInfo>) {
        val a = JSONArray()
        for (v in list) a.put(JSONObject().put("id", v.id).put("name", v.name).put("file", v.customFile!!.name).put("personal", v.personalOnly))
        index.writeText(a.toString())
        _custom.value = list
    }

    /** Decodes the recording, makes the voice and saves it. Runs for a few seconds. */
    fun create(name: String, uri: Uri, personalOnly: Boolean): VoiceInfo {
        val kit = models.dir(Catalog.clone)
        val wav = AudioDecoder.decode(ctx, uri)
        val voice = VoiceCloner(File(kit, "codec_encoder.onnx"), File(kit, "reference_encoder.onnx")).use { it.clone(wav) }
        val id = "custom_${System.currentTimeMillis()}"
        val f = File(dir, "$id.json")
        voice.save(f)
        val info = VoiceInfo(id, "blue_25", "", name.ifBlank { "קול חדש" }, if (personalOnly) "שימוש אישי" else "הקול שלי",
            female = false, customFile = f, personalOnly = personalOnly)
        save(_custom.value + info)
        return info
    }

    fun delete(v: VoiceInfo) { v.customFile?.delete(); save(_custom.value.filter { it.id != v.id }) }
}
