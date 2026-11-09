package com.karianoot.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.karianoot.app.ui.App
import com.karianoot.app.ui.Tab
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile

/**
 * Renders the screens on the JVM so the design can be reviewed from CI (pushed to the
 * ui-screenshots branch). Phone size ≈ Galaxy S25 Ultra.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Sparse files of the exact size make a model count as installed. */
    private fun install(vararg ms: ModelInfo) {
        for (m in ms) for (f in m.files) {
            val file = File(ctx.getExternalFilesDir(null), "models/${m.id}/${f.path}")
            file.parentFile!!.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(f.size) }
        }
    }

    private fun shot(name: String, tab: Tab, text: String? = null) {
        val app = ApplicationProvider.getApplicationContext<KarianootApp>()
        app.models.refresh()
        captureRoboImage("build/screenshots/$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                App(app, text, startTab = tab, motion = false)
            }
        }
    }

    @Test fun firstRun() = shot("1_studio_first_run", Tab.Studio)

    @Test fun studioReady() {
        install(Catalog.g2p, Catalog.blueV3, Catalog.blue25)
        shot("2_studio_ready", Tab.Studio, "ערב טוב לכולם! היום יש לנו שלושה משחקים מעולים, ואני הולך להסביר לכם בדיוק למה. תישארו עד הסוף.")
    }

    @Test fun models() {
        install(Catalog.g2p)
        shot("3_models", Tab.Models)
    }

    @Test fun library() = shot("4_library", Tab.Library)

    @Test fun about() = shot("5_about", Tab.About)

    @Test fun studioWithQwen() {
        install(Catalog.g2p, Catalog.qwen, Catalog.blue25, Catalog.clone)
        shot("7_studio_qwen", Tab.Studio, "ערב טוב לכל האוהדים! הערב יש לנו משחק ענק, והיחס פשוט מטורף.")
    }

    @Test fun libraryWithRecording() {
        val dir = File(ctx.getExternalFilesDir(null), "creations").apply { mkdirs() }
        com.karianoot.engine.Wav.write(File(dir, "demo.wav"), FloatArray(44100 * 3), 44100)
        File(dir, "index.json").writeText(org.json.JSONArray().put(org.json.JSONObject()
            .put("file", "demo.wav").put("text", "ערב טוב לכל האוהדים! תסתכלו על היחס, והיחס פה פשוט מטורף.")
            .put("voice", "נמר").put("seconds", 3.0).put("at", 0L).put("gen", 4.2).put("vid", "namer")
            .put("parts", org.json.JSONArray()
                .put(org.json.JSONObject().put("t", "ערב טוב לכל האוהדים!").put("s", 0).put("e", 40000))
                .put(org.json.JSONObject().put("t", "תסתכלו על היחס, והיחס פה פשוט מטורף.").put("s", 50000).put("e", 132300)))).toString())
        shot("6_library_recording", Tab.Library)
    }
}
