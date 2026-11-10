package com.karianoot.app

/**
 * One file of a model, pinned to an exact Hugging Face revision, or to a direct [url] (a GitHub release of this app's
 * repo) checked against its [sha256] after the download.
 */
data class ModelFile(val path: String, val size: Long, val repo: String? = null, val revision: String? = null,
                     val url: String? = null, val sha256: String? = null)

enum class ModelKind { G2P, BLUE, CLONE, QWEN, MAGPIE, SOON }

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
    /** Where it comes from, for the About screen. */
    val home: String = "huggingface.co/$repo",
) {
    val size: Long get() = files.sumOf { it.size }
    fun url(f: ModelFile) = f.url ?: "https://huggingface.co/${f.repo ?: repo}/resolve/${f.revision ?: revision}/${f.path.substringAfterLast("@")}"
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
    /** Magpie: the model's baked voice (speaker index). */
    val speaker: Int = -1,
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

    /**
     * Magpie TTS Multilingual 357M (NVIDIA) fine-tuned on Hebrew by notmax123, converted to one GGUF (q8_0; q4_k broke
     * voice 10) for magpie-tts.cpp, published as the magpie-gguf prerelease of this repo (magpie-gguf.yml).
     */
    val magpie = ModelInfo(
        id = "magpie_he", kind = ModelKind.MAGPIE,
        title = "Magpie עברית",
        subtitle = "קולות עבריים טבעיים מאוד (בבדיקה: 99% מהמילים נשמעו נכון). קטן ומהיר יותר מ-Qwen.",
        license = "NVIDIA Open Model License",
        credit = "Magpie TTS Multilingual 357M (NVIDIA, NVIDIA Open Model License), Hebrew fine-tune by notmax123; " +
            "engine magpie-tts.cpp (MIT)",
        home = "huggingface.co/notmax123/magpie_tts_multilingual_357m-he · github.com/mudler/magpie-tts.cpp",
        files = listOf(ModelFile(MAGPIE_FILE, 603_088_864,
            url = "https://github.com/israel0548502744-blip/Karianoot/releases/download/magpie-gguf/$MAGPIE_FILE",
            sha256 = "5d1f2cffa97c6fbc83a53da2bf965d00cb7c4691feedc4dd0960a5e566ac51c4")),
    )
    const val MAGPIE_FILE = "magpie-he-q8_0.gguf"

    /** Shown as "coming soon" so the plan is visible inside the app. */
    val soon = listOf(
        ModelInfo("noa", "נועה (Piper)", "קול נשי מהיר מאוד.", ModelKind.SOON, license = "CC BY 4.0", credit = "thewh1teagle"),
    )

    val downloadable = listOf(g2p, magpie, qwen, blue25, blueV3, clone)
    val all = downloadable + soon

    private fun q(id: String, name: String, note: String, female: Boolean, personal: Boolean = false) =
        VoiceInfo("q_$id", "qwen_he", "", name, note, female, personalOnly = personal,
            asset = "qwen/q_$id.spk", needs = listOf("g2p", "qwen_he"))

    private fun m(speaker: Int, name: String, note: String, female: Boolean) =
        VoiceInfo("m_$speaker", "magpie_he", "", name, note, female, speaker = speaker, needs = listOf("g2p", "magpie_he"))

    val voices = listOf(
        // Magpie, named as on the picker page; the user's favourite (female5) first, then the two they loved.
        m(10, "אישה 5", "מומלץ", female = true),
        m(19, "קול 3", "", female = false),     // F0 ~140 Hz, formants with the male voices
        m(18, "קול 2", "", female = true),
        m(6, "אישה 1 (עברית)", "", female = true),
        m(9, "אישה 4", "", female = true),
        m(11, "גבר 1", "", female = false),
        m(12, "גבר 1 (עברית)", "", female = false),
        m(13, "גבר 2", "", female = false),
        m(15, "גבר 4", "", female = false),
        m(16, "גבר 5", "", female = false),
        // Designed from a text description with VoxCPM2 (Apache-2.0): synthetic voices, no real person behind them.
        q("d_commentator", "שדרן", "מעוצב", female = false),
        q("d_radio", "קריין רדיו", "מעוצב", female = false),
        q("d_stadium", "כרוז", "מעוצב", female = false),
        q("d_woman", "מגישה", "מעוצב", female = true),
        q("d_hype", "שדרן בהייפ", "מעוצב", female = false),
        q("d_veteran", "שדרן ותיק", "מעוצב", female = false),
        q("d_pundit", "פרשן רגוע", "מעוצב", female = false),
        q("d_young", "צעיר אנרגטי", "מעוצב", female = false),
        q("d_woman2", "אוהדת נלהבת", "מעוצב", female = true),
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
