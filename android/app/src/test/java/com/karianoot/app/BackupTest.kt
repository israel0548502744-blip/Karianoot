package com.karianoot.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {
    @Test fun roundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ext = ctx.getExternalFilesDir(null)!!
        File(ext, "voices").mkdirs(); File(ext, "voices/custom_1.json").writeText("{\"v\":1}")
        File(ext, "pronounce.json").writeText("[{\"word\":\"היחס\",\"say\":\"הַיַּחַס\"}]")
        VoiceOrder(ctx).apply { toggleFavorite("m_19"); rename("m_13", "השדרן") }
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("autoplay", false).commit()
        val zip = File(ctx.cacheDir, "b.zip")
        val n = zip.outputStream().use { Backup(ctx).export(it) }
        assertTrue("files in the backup: $n", n >= 6)

        // A "new phone": everything gone, then restored.
        File(ext, "voices").deleteRecursively(); File(ext, "pronounce.json").delete()
        for (p in listOf("voice_order", "settings")) ctx.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        zip.inputStream().use { Backup(ctx).restore(it) }
        assertEquals("{\"v\":1}", File(ext, "voices/custom_1.json").readText())
        assertTrue(File(ext, "pronounce.json").readText().contains("היחס"))
        assertEquals(false, ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("autoplay", true))
        VoiceOrder(ctx).let { assertEquals(listOf("m_19"), it.favorites.value); assertEquals("השדרן", it.names.value["m_13"]) }
    }
}
