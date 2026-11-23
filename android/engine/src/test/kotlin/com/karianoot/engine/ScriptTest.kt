package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptTest {
    @Test fun splitsSpeechPausesAndEffects() {
        val s = Script.parse("{שריקה} ערב טוב לכולם! {הפסקה} המשחק הראשון {הפסקה 2.5} ארסנל {קהל}")
        assertEquals(listOf(Script.Effect("שריקה"), Script.Speech("ערב טוב לכולם!"), Script.Pause(1f), Script.Speech("המשחק הראשון"),
            Script.Pause(2.5f), Script.Speech("ארסנל"), Script.Effect("קהל")), s)
        assertEquals(listOf(Script.Pause(0.5f)), Script.parse("{ הפסקה 0,5 }"))
        assertEquals(listOf(Script.Pause(3f)), Script.parse("{הפסקה 3 שניות}"))
        assertEquals(listOf("{משהו}"), Script.unknown("שלום {משהו} {שריקה}"))
        assertEquals(listOf(Script.Speech("שלום"), Script.Speech("עולם")), Script.parse("שלום {משהו} עולם"))
        // Template placeholders are square brackets, not markers.
        assertEquals(listOf(Script.Speech("[קבוצה] נגד [קבוצה]")), Script.parse("[קבוצה] נגד [קבוצה]"))
    }
}
