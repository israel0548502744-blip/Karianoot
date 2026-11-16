package com.karianoot.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "בדיקת עדכונים": asks GitHub for the latest release (apk-N, N = the build number = versionCode) and, when it is newer,
 * offers its APK. The browser downloads it and Android installs it over this one (same signing key, models kept).
 */
class Updates(private val ctx: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val number: Int, val apk: String, val page: String) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** This app's build number (apk-N). */
    val current: Int = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    suspend fun check() = withContext(Dispatchers.IO) {
        if (_state.value == State.Checking) return@withContext
        _state.value = State.Checking
        _state.value = runCatching {
            val c = URL(LATEST).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val o = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            parse(o, current)
        }.getOrElse { State.Failed("אין חיבור לאינטרנט, או שהשרת לא ענה. נסו שוב.") }
    }

    companion object {
        const val LATEST = "https://api.github.com/repos/israel0548502744-blip/Karianoot/releases/latest"

        /** The latest release (GitHub's JSON) against build [current]. */
        fun parse(o: JSONObject, current: Int): State {
            val n = Regex("apk-(\\d+)").matchEntire(o.getString("tag_name"))?.groupValues?.get(1)?.toInt()
                ?: return State.Failed("לא הצלחתי לקרוא את פרטי הגרסה.")
            if (n <= current) return State.UpToDate
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                ?: return State.Failed("בגרסה החדשה אין עדיין קובץ להתקנה. נסו שוב בעוד כמה דקות.")
            return State.Available(n, apk, o.optString("html_url", apk))
        }
    }
}
