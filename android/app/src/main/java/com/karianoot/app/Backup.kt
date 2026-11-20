package com.karianoot.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * "גיבוי": one zip with what the user made (voices from recordings, pronunciation fixes, recordings, hearts/archive/names,
 * settings), to keep or to move to a new phone. Models are not in it: they download again by themselves.
 */
class Backup(private val ctx: Context) {
    private val ext get() = ctx.getExternalFilesDir(null)!!
    private val dirs = listOf("voices", "creations")
    private val files = listOf("pronounce.json")
    private val prefs = listOf("voice_order", "settings", "studio")

    /** Writes the backup to [uri]; returns how many files went in. */
    fun export(uri: Uri): Int = ctx.contentResolver.openOutputStream(uri)!!.use { export(it) }

    fun export(out: java.io.OutputStream): Int {
        var n = 0
        out.let { os ->
            ZipOutputStream(os.buffered()).use { z ->
                fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry(); n++ }
                for (d in dirs) File(ext, d).walkTopDown().filter { it.isFile }.forEach { f ->
                    put("$d/" + f.relativeTo(File(ext, d)).invariantSeparatorsPath, f.readBytes())
                }
                for (f in files) File(ext, f).takeIf { it.exists() }?.let { put(f, it.readBytes()) }
                for (p in prefs) put("prefs/$p.json", prefsToJson(p).toString().toByteArray())
                put("karianoot-backup.txt", "גיבוי של קריינות\n".toByteArray())
            }
        }
        return n
    }

    /** Restores a backup from [uri] (files of the same name are replaced); returns how many files came back. */
    fun restore(uri: Uri): Int = ctx.contentResolver.openInputStream(uri)!!.use { restore(it) }

    fun restore(input: java.io.InputStream): Int {
        var n = 0; var valid = false
        val root = ext.canonicalFile
        input.let { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name
                    when {
                        e.isDirectory -> {}
                        name == "karianoot-backup.txt" -> valid = true
                        name.startsWith("prefs/") && name.endsWith(".json") -> {
                            val p = name.removePrefix("prefs/").removeSuffix(".json")
                            if (p in prefs) { jsonToPrefs(p, JSONObject(z.readBytes().decodeToString())); n++ }
                        }
                        dirs.any { name.startsWith("$it/") } || name in files -> {
                            val out = File(root, name).canonicalFile
                            if (!out.path.startsWith(root.path + File.separator)) continue   // no "../" tricks
                            out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) }; n++
                        }
                    }
                }
            }
        }
        require(valid) { "זה לא קובץ גיבוי של קריינות" }
        return n
    }

    private fun prefsToJson(name: String): JSONObject {
        val o = JSONObject()
        for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            val t = when (v) { is Boolean -> "b"; is Float -> "f"; is Int -> "i"; is Long -> "l"; is String -> "s"; is Set<*> -> "set"; else -> continue }
            o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) org.json.JSONArray(v.toList()) else v))
        }
        return o
    }

    private fun jsonToPrefs(name: String, o: JSONObject) {
        val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        for (k in o.keys()) {
            val x = o.getJSONObject(k)
            when (x.getString("t")) {
                "b" -> e.putBoolean(k, x.getBoolean("v")); "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                "i" -> e.putInt(k, x.getInt("v")); "l" -> e.putLong(k, x.getLong("v")); "s" -> e.putString(k, x.getString("v"))
                "set" -> { val a = x.getJSONArray("v"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        e.commit()
    }
}
