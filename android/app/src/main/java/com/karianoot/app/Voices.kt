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
        (0 until a.length()).map { a.getJSONObject(it) }.flatMap { o ->
            val f = File(dir, o.getString("file"))
            if (!f.exists()) return@flatMap emptyList<VoiceInfo>()
            val personal = o.getBoolean("personal")
            val blue = VoiceInfo(o.getString("id"), "blue_25", "", o.getString("name"),
                (if (personal) "שימוש אישי" else "הקול שלי") + " · 2.5", female = o.optBoolean("female", false),
                customFile = f, personalOnly = personal, needs = listOf("blue_25", "clone"))
            val spk = File(dir, o.getString("id") + ".spk")
            if (spk.exists()) listOf(blue, blue.copy(id = blue.id + "_q", modelId = Catalog.qwen.id, customFile = spk, note = "Qwen · " + blue.note,
                needs = listOf("g2p", Catalog.qwen.id))) else listOf(blue)
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<VoiceInfo>) {
        val a = JSONArray()
        for (v in list.filter { it.modelId != Catalog.qwen.id })
            a.put(JSONObject().put("id", v.id).put("name", v.name).put("file", v.customFile!!.name).put("personal", v.personalOnly).put("female", v.female))
        index.writeText(a.toString())
        _custom.value = load()
    }

    /**
     * Decodes the recording, makes the Blue voice and saves it; when Qwen is installed, [qwenPrint] also makes
     * its Qwen voice print. Runs for a few seconds (Qwen: longer, it loads the big model).
     */
    fun create(name: String, uri: Uri, personalOnly: Boolean, female: Boolean, qwenPrint: ((FloatArray) -> FloatArray)?): VoiceInfo {
        val kit = models.dir(Catalog.clone)
        val wav = AudioDecoder.decode(ctx, uri)
        val voice = VoiceCloner(File(kit, "codec_encoder.onnx"), File(kit, "reference_encoder.onnx")).use { it.clone(wav) }
        val id = "custom_${System.currentTimeMillis()}"
        val f = File(dir, "$id.json")
        voice.save(f)
        qwenPrint?.let { runCatching { File(dir, "$id.spk").writeBytes(com.karianoot.engine.QwenTts.writeSpk(it(wav))) } }
        val info = VoiceInfo(id, "blue_25", "", name.ifBlank { "קול חדש" }, if (personalOnly) "שימוש אישי" else "הקול שלי",
            female = female, customFile = f, personalOnly = personalOnly, needs = listOf("blue_25", "clone"))
        save(_custom.value.filter { it.modelId != Catalog.qwen.id } + info)
        return info
    }

    fun delete(v: VoiceInfo) {
        val base = v.id.removeSuffix("_q")
        File(dir, "$base.json").delete(); File(dir, "$base.spk").delete()
        save(_custom.value.filter { it.id != base && it.id != base + "_q" })
    }
}
