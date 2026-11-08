package com.karianoot.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.karianoot.engine.BlueTts
import com.karianoot.engine.BlueVoice
import com.karianoot.engine.HebrewG2P
import com.karianoot.engine.Pronounce
import com.karianoot.engine.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Creation(val file: File, val text: String, val voice: String, val seconds: Float, val createdAt: Long, val genSeconds: Float)

/**
 * Keeps one loaded voice engine at a time (each Blue bundle takes a few hundred MB of memory)
 * and runs synthesis off the main thread.
 */
class Speech(private val ctx: Context, private val models: Models, private val fixes: () -> List<Pronounce.Fix> = { emptyList() }) {
    private val lock = Mutex()
    private var g2p: HebrewG2P? = null
    private var tts: BlueTts? = null
    private var ttsModel: String? = null

    val creationsDir = File(ctx.getExternalFilesDir(null), "creations").apply { mkdirs() }
    private val index = File(creationsDir, "index.json")

    suspend fun speak(text: String, voice: VoiceInfo, opt: BlueTts.Options, onStage: (String, Float) -> Unit): Creation =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val t0 = System.nanoTime()
                if (g2p == null) {
                    onStage("טוען את מנוע ההגייה…", 0f)
                    g2p = HebrewG2P(File(models.dir(Catalog.g2p), "model_int8.onnx").path)
                }
                // Voices made from a recording take their duration from Blue 2.5's z_ref predictor.
                val dpRef = File(models.dir(Catalog.clone), "duration_predictor.onnx").takeIf { voice.modelId == "blue_25" && it.length() == 2_151_232L }
                val key = voice.modelId + (if (dpRef != null) "+ref" else "")
                if (ttsModel != key) {
                    onStage("טוען את הקול…", 0f)
                    tts?.close(); tts = null; ttsModel = null
                    tts = BlueTts(models.dir(Catalog.model(voice.modelId)), g2p, dpRef)
                    ttsModel = key
                }
                val engine = tts!!
                val v = BlueVoice.load(voice.customFile ?: voice.asset?.let { assetFile(it) }
                    ?: File(models.dir(Catalog.model(voice.modelId)), voice.file))
                onStage("מקריא…", 0.02f)
                val wav = engine.synthesize(Pronounce.apply(text, fixes()), v, opt) { p -> onStage("מקריא…", p) }
                val name = "karianoot_${System.currentTimeMillis()}.wav"
                val f = File(creationsDir, name)
                Wav.write(f, wav, engine.sampleRate)
                val c = Creation(f, text, voice.name, wav.size.toFloat() / engine.sampleRate, System.currentTimeMillis(),
                    ((System.nanoTime() - t0) / 1e9).toFloat())
                save(listOf(c) + history())
                c
            }
        }

    /** Copies a voice shipped in assets/ to a file (refreshed when the app is updated). */
    private fun assetFile(path: String): File {
        val f = File(ctx.filesDir, "builtin/" + path)
        val stamp = File(f.path + ".v")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (!f.exists() || !stamp.exists() || stamp.readText() != ver) {
            f.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> f.outputStream().use { i.copyTo(it) } }
            stamp.writeText(ver)
        }
        return f
    }

    fun history(): List<Creation> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val f = File(creationsDir, o.getString("file"))
            if (!f.exists()) null else Creation(f, o.getString("text"), o.getString("voice"),
                o.getDouble("seconds").toFloat(), o.getLong("at"), o.optDouble("gen", 0.0).toFloat())
        }
    }.getOrDefault(emptyList())

    fun delete(c: Creation) { c.file.delete(); save(history().filter { it.file != c.file }) }

    private fun save(all: List<Creation>) {
        // Keep the newest [KEEP] recordings; older ones are deleted from the phone.
        val list = all.take(KEEP)
        all.drop(KEEP).forEach { it.file.delete() }
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("file", c.file.name).put("text", c.text).put("voice", c.voice)
            .put("seconds", c.seconds.toDouble()).put("at", c.createdAt).put("gen", c.genSeconds.toDouble()))
        index.writeText(a.toString())
    }

    /** Copies a creation into the phone's Music/Karianoot folder, where other apps can find it. */
    fun exportToMusic(c: Creation): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, c.file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Karianoot")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> c.file.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)

    companion object { const val KEEP = 30 }

    fun release() { tts?.close(); g2p?.close(); tts = null; g2p = null; ttsModel = null }
}
