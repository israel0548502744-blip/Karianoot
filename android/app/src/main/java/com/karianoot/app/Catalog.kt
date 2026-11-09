package com.karianoot.app

/** One file of a model, pinned to an exact Hugging Face revision. */
data class ModelFile(val path: String, val size: Long, val repo: String? = null, val revision: String? = null)

enum class ModelKind { G2P, BLUE, CLONE, QWEN, SOON }

data class ModelInfo(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: ModelKind,
    val repo: String = "",
    val revision: String = "",
    val license: String,
    val credit: String,
    val files: List<ModelFile> = emptyList(),
) {
    val size: Long get() = files.sumOf { it.size }
    fun url(f: ModelFile) = "https://huggingface.co/${f.repo ?: repo}/resolve/${f.revision ?: revision}/${f.path.substringAfterLast("@")}"
}

data class VoiceInfo(
    val id: String, val modelId: String, val file: String, val name: String, val note: String, val female: Boolean,
    /** A voice the user made from a recording: its style file on the phone. */
    val customFile: java.io.File? = null,
    /** Made from a recording whose rights are unclear: fine for personal use, not for an app that is sold. */
    val personalOnly: Boolean = false,
    /** A ready-made voice shipped inside the app (assets/), not in a model download. */
    val asset: String? = null,
    /** Every model this voice needs on the phone. */
    val needs: List<String> = listOf(modelId),
    /** Default liveliness (start-noise scale, [com.karianoot.engine.BlueTts.Options.temperature]). */
    val liveliness: Float = 1.0f,
) {
    /** Denoising steps for "fast / good / best". Blue V3 needs more (measured: 8 steps 5% errors, 16 steps 0.6%). */
    val steps: IntArray get() = if (modelId == "blue_v3") intArrayOf(12, 16, 24) else intArrayOf(5, 10, 16)
}

/**
 * Every model the app can use. Only licenses that allow commercial use (MIT / Apache / CC BY).
 * Sizes come from the Hugging Face API (see .github/workflows/probe-models.yml); a download only
 * counts as finished when the file on disk has exactly this size.
 */
object Catalog {
    val g2p = ModelInfo(
        id = "g2p", kind = ModelKind.G2P,
        title = "מנוע ההגייה העברית",
        subtitle = "קורא עברית בלי ניקוד ומבין איך להגות כל מילה. חובה לכל הקולות העבריים.",
        repo = "notmax123/RenikudPlus", revision = "679c56ca449d41873fb8ff7711ddf7563d28198f",
        license = "Apache-2.0", credit = "RenikudPlus — Maxim Melichov, Yakov Kolani, Morris Alper",
        files = listOf(ModelFile("model_int8.onnx", 311_605_741)),
    )

    val blueV3 = ModelInfo(
        id = "blue_v3", kind = ModelKind.BLUE,
        title = "Blue V3",
        subtitle = "הקולות רועי ורותם, הדור החדש. בבדיקה: 99.4% מהמילים נשמעו נכון.",
        repo = "notmax123/BlueV3-onnx", revision = "b26faaa63255d2991998c7f28c10aa4e6026f3c9",
        license = "MIT", credit = "BlueTTS — Maxim Melichov (notmax123)",
        files = listOf(
            ModelFile("tts.json", 8_313), ModelFile("stats.npz", 1_920), ModelFile("uncond.npz", 52_732),
            ModelFile("duration_predictor.onnx", 3_700_147), ModelFile("text_encoder.onnx", 28_117_514),
            ModelFile("vector_estimator.onnx", 256_515_586), ModelFile("vocoder.onnx", 101_414_687),
            ModelFile("voices/Roi.json", 296_585), ModelFile("voices/Rotem.json", 298_072),
        ),
    )

    val blue25 = ModelInfo(
        id = "blue_25", kind = ModelKind.BLUE,
        title = "Blue 2.5",
        subtitle = "חמישה קולות ברורים מאוד (בבדיקה: 99% מהמילים נשמעו נכון). מומלץ.",
        repo = "notmax123/BlueTTS2.5-onnx", revision = "95feb5d0cbfc8e5a95bffb067d03537cbf35be3e",
        license = "MIT", credit = "BlueTTS — Maxim Melichov (notmax123); LibriTTS voices CC BY 4.0",
        files = listOf(
            ModelFile("tts.json", 9_260), ModelFile("vocab.json", 3_157), ModelFile("stats.npz", 1_920),
            ModelFile("uncond.npz", 52_732), ModelFile("duration_predictor_style.onnx", 1_508_749),
            ModelFile("text_encoder.onnx", 27_419_694), ModelFile("vector_estimator.onnx", 132_475_789),
            ModelFile("vocoder.onnx", 101_411_919),
            ModelFile("voices/female.json", 288_787), ModelFile("voices/libri_female_1088.json", 288_994),
            ModelFile("voices/libri_female_6147.json", 289_185), ModelFile("voices/libri_male_6209.json", 289_115),
            ModelFile("voices/libri_male_8088.json", 288_870),
        ),
    )

    /**
     * What it takes to make a voice from a recording (tools/clone_probe.py): Blue v2's audio encoder,
     * and Blue 2.5's reference encoder and duration predictor. Works with the Blue 2.5 voices.
     */
    val clone = ModelInfo(
        id = "clone", kind = ModelKind.CLONE,
        title = "ערכת \"קול מהקלטה\"",
        subtitle = "מאפשרת ליצור קול חדש מקובץ הקלטה (10 שניות ומעלה). עובד יחד עם Blue 2.5.",
        repo = "notmax123/BlueTTS2.5-onnx", revision = "95feb5d0cbfc8e5a95bffb067d03537cbf35be3e",
        license = "MIT", credit = "BlueTTS — Maxim Melichov (notmax123)",
        files = listOf(
            ModelFile("codec_encoder.onnx", 102_246_772, repo = "notmax123/blue-onnx-v2", revision = "cd1093eaa920136e7a727dfb92d5f7879039e587"),
            ModelFile("reference_encoder.onnx", 15_024_793),
            ModelFile("duration_predictor.onnx", 2_151_232),
        ),
    )

    /**
     * QwenTTS-he: a large language-model voice (Qwen3-TTS + a Hebrew adapter), cloned from short reference clips.
     * The most natural voices, slower than Blue. Runs on qwentts.cpp (MIT) through libqwenjni.
     */
    val qwen = ModelInfo(
        id = "qwen_he", kind = ModelKind.QWEN,
        title = "Qwen עברית",
        subtitle = "הקולות הכי טבעיים וחיים. איטי יותר מ-Blue (בערך דקה לקריינות של 20 שניות).",
        repo = "notmax123/QwenTTS-he-1.7B-GGUF", revision = "2b01f8797400c04cb4bc9b62a94a0e6f8866b1b9",
        license = "Apache-2.0",
        credit = "QwenTTS-he — Max Melichov (notmax123); Qwen3-TTS — Alibaba Qwen; qwentts.cpp (MIT); " +
            "voices from LibriTTS speakers (CC BY 4.0) via Blue 2.5 and LightBlue (MIT); designed voices made with VoxCPM2 (Apache-2.0)",
        files = listOf(
            ModelFile("qwen-talker-1.7b-base-Q4_K_M.gguf", 1_219_245_248),
            ModelFile("qwen-tokenizer-12hz-Q4_K_M.gguf", 254_974_752),
        ),
    )

    /** Shown as "coming soon" so the plan is visible inside the app. */
    val soon = listOf(
        ModelInfo("noa", "נועה (Piper)", "קול נשי מהיר מאוד.", ModelKind.SOON, license = "CC BY 4.0", credit = "thewh1teagle"),
    )

    val downloadable = listOf(g2p, qwen, blue25, blueV3, clone)
    val all = downloadable + soon

    private fun q(id: String, name: String, note: String, female: Boolean, personal: Boolean = false) =
        VoiceInfo("q_$id", "qwen_he", "", name, note, female, personalOnly = personal,
            asset = "qwen/q_$id.spk", needs = listOf("g2p", "qwen_he"))

    val voices = listOf(
        // Designed from a text description with VoxCPM2 (Apache-2.0): synthetic voices, no real person behind them.
        q("d_commentator", "שדרן", "מעוצב", female = false),
        q("d_radio", "קריין רדיו", "מעוצב", female = false),
        q("d_stadium", "כרוז", "מעוצב", female = false),
        q("d_woman", "מגישה", "מעוצב", female = true),
        q("male2", "גברי 2", "", female = false),
        q("tet", "גברי ט", "", female = false),
        q("het", "גברי ח", "", female = false),
        q("male1", "גברי 1", "", female = false),
        q("female", "נשי", "", female = true),
        q("gimel", "נשי ג", "", female = true),
        q("namer", "נמר", "שדרן", female = false, personal = true),
        VoiceInfo("25_female", "blue_25", "voices/female.json", "נשי", "2.5", female = true),
        VoiceInfo("25_m6209", "blue_25", "voices/libri_male_6209.json", "גברי 1", "2.5", female = false),
        VoiceInfo("25_m8088", "blue_25", "voices/libri_male_8088.json", "גברי 2", "2.5", female = false),
        VoiceInfo("25_f1088", "blue_25", "voices/libri_female_1088.json", "נשי 2", "2.5", female = true),
        VoiceInfo("25_f6147", "blue_25", "voices/libri_female_6147.json", "נשי 3", "2.5", female = true),
        VoiceInfo("v3_roi", "blue_v3", "voices/Roi.json", "רועי", "גברי · V3", female = false),
        VoiceInfo("v3_rotem", "blue_v3", "voices/Rotem.json", "רותם", "נשי · V3", female = true),
        // Made from the owner's ElevenLabs clips (tools/namer.py): personal use only, see assets/voices/README.md.
        VoiceInfo("namer", "blue_25", "", "נמר", "שדרן · 2.5", female = false, personalOnly = true,
            asset = "voices/namer.json", needs = listOf("blue_25", "clone"), liveliness = 1.1f),
    )

    fun model(id: String) = all.first { it.id == id }
}
