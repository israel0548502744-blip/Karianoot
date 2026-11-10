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
    data class Downloading(val done: Long, val total: Long, val paused: Boolean = false, val checking: Boolean = false) : ModelState
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

    /** In place: the right size, and for a file pinned by sha256, checked (a marker holds the hash it matched). */
    private fun installed(m: ModelInfo, f: ModelFile): Boolean {
        val t = target(m, f)
        if (t.length() != f.size) return false
        return f.sha256 == null || runCatching { File(t.path + ".sha256").readText().trim() == f.sha256 }.getOrDefault(false)
    }

    private fun stateNow(m: ModelInfo): ModelState =
        if (m.files.all { installed(m, it) }) ModelState.Ready else ModelState.Missing

    /** Files being checked against their sha256 (off the main thread), and the outcome of a failed check. */
    private val checking = java.util.Collections.synchronizedSet(HashSet<String>())
    private val badHash = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Moves a finished download into place; a file pinned by sha256 is checked first, in the background. */
    private fun install(m: ModelInfo, f: ModelFile, s: File) {
        val t = target(m, f)
        fun move() { t.parentFile?.mkdirs(); t.delete(); if (!s.renameTo(t)) { s.copyTo(t, overwrite = true); s.delete() } }
        if (f.sha256 == null) { move(); return }
        if (!checking.add(key(m, f))) return
        Thread {
            try {
                if (sha256(s) == f.sha256) { move(); File(t.path + ".sha256").writeText(f.sha256) }
                else { s.delete(); badHash[m.id] = "הקובץ שירד פגום (בדיקת תקינות נכשלה): ${f.path}. נסו שוב." }
            } catch (e: Exception) {
                badHash[m.id] = "בדיקת הקובץ נכשלה: ${e.message}"
            } finally { checking.remove(key(m, f)); refresh() }
        }.start()
    }

    fun start(m: ModelInfo) {
        badHash.remove(m.id)
        for (f in m.files) {
            if (installed(m, f) || key(m, f) in checking) continue
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
            var check = false
            for (f in m.files) {
                if (installed(m, f)) { done += f.size; continue }
                if (key(m, f) in checking) { done += f.size; anyActive = true; check = true; continue }
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
                                install(m, f, s)
                                done += f.size
                                if (f.sha256 != null) { anyActive = true; check = true }
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
                m.files.all { installed(m, it) } -> ModelState.Ready
                anyActive -> ModelState.Downloading(done, m.size, paused, check)
                failure != null -> ModelState.Failed(failure!!)
                badHash[m.id] != null -> ModelState.Failed(badHash[m.id]!!)
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
