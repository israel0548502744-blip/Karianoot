package com.karianoot.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceOrderTest {
    @Test fun heartsFirstArchivedOutAndBack() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val o = VoiceOrder(ctx)
        val all = listOf("a", "b", "c", "d")
        o.toggleFavorite("c"); o.toggleFavorite("a")
        assertEquals(listOf("c", "a", "b", "d"), o.arrange(all) { it })
        o.archive("b")
        assertEquals(listOf("c", "a", "d"), o.arrange(all) { it })
        o.archive("c")                      // archiving drops the heart too
        assertEquals(listOf("a", "d"), o.arrange(all) { it })
        o.restore("c"); o.toggleFavorite("a")
        assertEquals(listOf("a", "c", "d"), o.arrange(all) { it })
        // Kept across app starts.
        assertEquals(listOf("b"), VoiceOrder(ctx).archived.value)
    }
}
