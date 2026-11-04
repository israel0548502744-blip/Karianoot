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
        val models = Models(ctx)
        val speech = Speech(ctx, models)
        captureRoboImage("build/screenshots/$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                App(models, speech, text, startTab = tab, motion = false)
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
}
