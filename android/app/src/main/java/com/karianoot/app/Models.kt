package com.karianoot.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long, val paused: Boolean = false) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * Downloads models with Android's DownloadManager (keeps going in the background, with a
 * notification) into the app's external files folder. That folder survives app updates, so a
 * new APK never downloads the models again; it is removed only when the app is uninstalled.
 */
class Models(private val ctx: Context) {
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    private val prefs = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val root = File(ctx.getExternalFilesDir(null), "models")
    private val staging = "dl"

    private val _states = MutableStateFlow(Catalog.downloadable.associate { it.id to stateNow(it) })
    val states: StateFlow<Map<String, ModelState>> = _states

    fun dir(m: ModelInfo) = File(root, m.id)
    fun isReady(id: String) = _states.value[id] == ModelState.Ready

    private fun target(m: ModelInfo, f: ModelFile) = File(dir(m), f.path)
    private fun stagingFile(m: ModelInfo, f: ModelFile) = File(ctx.getExternalFilesDir(null), "$staging/${m.id}/${f.path}")
    private fun key(m: ModelInfo, f: ModelFile) = "${m.id}/${f.path}"

    private fun stateNow(m: ModelInfo): ModelState =
        if (m.files.all { target(m, it).length() == it.size }) ModelState.Ready else ModelState.Missing

    fun start(m: ModelInfo) {
        for (f in m.files) {
            if (target(m, f).length() == f.size) continue
            if (prefs.contains(key(m, f)) && statusOf(prefs.getLong(key(m, f), -1)) in RUNNING) continue
            val out = stagingFile(m, f)
            out.parentFile?.mkdirs()
            out.delete()
            val req = DownloadManager.Request(Uri.parse(m.url(f)))
                .setTitle("קריינות · ${m.title}")
                .setDescription(f.path)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setDestinationInExternalFilesDir(ctx, null, "$staging/${m.id}/${f.path}")
            prefs.edit().putLong(key(m, f), dm.enqueue(req)).apply()
        }
        refresh()
    }

    fun cancel(m: ModelInfo) {
        for (f in m.files) {
            val id = prefs.getLong(key(m, f), -1)
            if (id >= 0) dm.remove(id)
            prefs.edit().remove(key(m, f)).apply()
            stagingFile(m, f).delete()
        }
        refresh()
    }

    fun delete(m: ModelInfo) {
        cancel(m)
        dir(m).deleteRecursively()
        refresh()
    }

    private fun statusOf(id: Long): Int {
        if (id < 0) return -1
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return -1
            return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    /** Moves finished files into place and recomputes every model's state. */
    fun refresh() {
        val map = HashMap<String, ModelState>()
        for (m in Catalog.downloadable) {
            var done = 0L
            var anyActive = false
            var paused = false
            var failure: String? = null
            for (f in m.files) {
                val t = target(m, f)
                if (t.length() == f.size) { done += f.size; continue }
                val id = prefs.getLong(key(m, f), -1)
                if (id < 0) continue
                dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) { prefs.edit().remove(key(m, f)).apply(); return@use }
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val s = stagingFile(m, f)
                            if (s.length() == f.size) {
                                t.parentFile?.mkdirs(); t.delete()
                                if (!s.renameTo(t)) { s.copyTo(t, overwrite = true); s.delete() }
                                done += f.size
                            } else {
                                failure = "קובץ לא שלם: ${f.path}"
                                s.delete()
                            }
                            prefs.edit().remove(key(m, f)).apply()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            failure = "ההורדה נכשלה (${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})"
                            prefs.edit().remove(key(m, f)).apply()
                        }
                        DownloadManager.STATUS_PAUSED -> { anyActive = true; paused = true; done += got }
                        else -> { anyActive = true; done += got }
                    }
                }
            }
            map[m.id] = when {
                m.files.all { target(m, it).length() == it.size } -> ModelState.Ready
                anyActive -> ModelState.Downloading(done, m.size, paused)
                failure != null -> ModelState.Failed(failure!!)
                else -> ModelState.Missing
            }
        }
        _states.value = map
    }

    /** Polls while something is downloading. */
    suspend fun watch() = withContext(Dispatchers.IO) {
        while (true) {
            refresh()
            delay(if (_states.value.values.any { it is ModelState.Downloading }) 700 else 3000)
        }
    }

    fun freeSpace(): Long = ctx.getExternalFilesDir(null)?.usableSpace ?: Environment.getDataDirectory().usableSpace

    private companion object {
        val RUNNING = setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
    }
}
