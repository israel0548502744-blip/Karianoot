package com.karianoot.app

import android.content.Context
import com.karianoot.engine.Lexicon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * New football names without a new app version: lexicon.json (release "lexicon" of this repo, made from
 * tools/lexicon/lexicon.json by .github/workflows/lexicon.yml) is fetched at most once a day and added to [Lexicon].
 * Only names and their IPA travel; nothing is sent.
 */
class LexiconUpdates(private val ctx: Context) {
    private val file = File(ctx.filesDir, "lexicon_extra.json")
    private val prefs = ctx.getSharedPreferences("lexicon", Context.MODE_PRIVATE)

    /** (version, how many names) of what is in use; version 0 = none downloaded yet. */
    val status = MutableStateFlow(0 to 0)

    fun loadCached() {
        runCatching { apply(JSONObject(file.readText())) }
    }

    private fun apply(o: JSONObject): Int {
        val w = o.getJSONObject("words")
        val n = Lexicon.setExtra(w.keys().asSequence().associateWith { w.getString(it) })
        status.value = o.optInt("version", 0) to n
        return n
    }

    /** Fetches the list when a day has passed since the last try (or [force]); false when it could not. */
    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("tried", 0) < 24 * 3600_000L) return@withContext true
        prefs.edit().putLong("tried", now).apply()
        runCatching {
            val c = URL(URL_LIST).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            if (o.optInt("version", 0) >= status.value.first) { apply(o); file.writeText(text) }
            true
        }.getOrDefault(false)
    }

    companion object {
        const val URL_LIST = "https://github.com/israel0548502744-blip/Karianoot/releases/download/lexicon/lexicon.json"
    }
}
