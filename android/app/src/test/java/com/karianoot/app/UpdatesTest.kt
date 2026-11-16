package com.karianoot.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatesTest {
    private fun release(tag: String, apk: Boolean = true) = JSONObject().put("tag_name", tag).put("html_url", "https://x/$tag")
        .put("assets", org.json.JSONArray().apply {
            if (apk) put(JSONObject().put("name", "Karianoot.apk").put("browser_download_url", "https://x/$tag/Karianoot.apk"))
        })

    @Test fun newerOlderAndBroken() {
        assertEquals(Updates.State.Available(80, "https://x/apk-80/Karianoot.apk", "https://x/apk-80"), Updates.parse(release("apk-80"), 79))
        assertEquals(Updates.State.UpToDate, Updates.parse(release("apk-79"), 79))
        assertEquals(Updates.State.UpToDate, Updates.parse(release("apk-12"), 79))
        assert(Updates.parse(release("apk-80", apk = false), 79) is Updates.State.Failed)
        assert(Updates.parse(release("magpie-gguf"), 79) is Updates.State.Failed)
    }
}
