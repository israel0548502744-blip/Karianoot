package com.karianoot.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's own order of the voices: hearted voices go first, archived ones leave the list (and can come back).
 * Kept on the phone (SharedPreferences), across app starts and updates.
 */
class VoiceOrder(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("voice_order", Context.MODE_PRIVATE)
    private val _favorites = MutableStateFlow(load(FAV))
    private val _archived = MutableStateFlow(load(ARCHIVED))
    /** Hearted voice ids, in the order they were hearted. */
    val favorites: StateFlow<List<String>> = _favorites
    val archived: StateFlow<List<String>> = _archived

    private fun load(key: String) = prefs.getString(key, "")!!.split('\n').filter { it.isNotEmpty() }
    private fun save(key: String, list: List<String>) = prefs.edit().putString(key, list.joinToString("\n")).apply()

    fun toggleFavorite(id: String) {
        val l = _favorites.value
        _favorites.value = if (id in l) l - id else l + id
        save(FAV, _favorites.value)
    }

    /** Out of the list (and out of the hearts). */
    fun archive(id: String) {
        if (id !in _archived.value) { _archived.value = _archived.value + id; save(ARCHIVED, _archived.value) }
        if (id in _favorites.value) toggleFavorite(id)
    }

    fun restore(id: String) {
        _archived.value = _archived.value - id
        save(ARCHIVED, _archived.value)
    }

    /** Hearted first (in heart order), then the rest in their usual order; archived voices left out. */
    fun <T> arrange(voices: List<T>, id: (T) -> String): List<T> {
        val fav = _favorites.value; val arch = _archived.value.toSet()
        val kept = voices.filter { id(it) !in arch }
        return kept.filter { id(it) in fav }.sortedBy { fav.indexOf(id(it)) } + kept.filter { id(it) !in fav }
    }

    private companion object {
        const val FAV = "favorites"
        const val ARCHIVED = "archived"
    }
}
