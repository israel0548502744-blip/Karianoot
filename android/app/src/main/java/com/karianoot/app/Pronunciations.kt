package com.karianoot.app

import android.content.Context
import com.karianoot.engine.Pronounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The user's "say this word like that" list, kept next to the models (survives app updates). */
class Pronunciations(ctx: Context) {
    private val file = File(ctx.getExternalFilesDir(null), "pronounce.json")
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Pronounce.Fix>> = _list

    private fun load(): List<Pronounce.Fix> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Pronounce.Fix(it.getString("word"), it.getString("say")) }
    }.getOrDefault(emptyList())

    private fun save(l: List<Pronounce.Fix>) {
        val a = JSONArray()
        for (f in l) a.put(JSONObject().put("word", f.word).put("say", f.sayAs))
        file.writeText(a.toString())
        _list.value = l
    }

    fun add(word: String, sayAs: String) {
        val w = word.trim(); val s = sayAs.trim()
        if (w.isEmpty() || s.isEmpty()) return
        save(_list.value.filter { it.word != w } + Pronounce.Fix(w, s))
    }

    fun remove(f: Pronounce.Fix) = save(_list.value - f)
}
