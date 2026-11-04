package com.karianoot.app

/** One file of a model, pinned to an exact Hugging Face revision. */
data class ModelFile(val path: String, val size: Long)

enum class ModelKind { G2P, BLUE, SOON }

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
    fun url(f: ModelFile) = "https://huggingface.co/$repo/resolve/$revision/${f.path}"
}

data class VoiceInfo(val id: String, val modelId: String, val file: String, val name: String, val note: String, val female: Boolean)

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
        subtitle = "הקולות העבריים הכי טובים שיש היום בטלפון: רועי ורותם. איכות 44.1kHz.",
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
        subtitle = "דור קודם, קל ומהיר יותר. חמישה קולות נוספים.",
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

    /** Shown as "coming soon" so the plan is visible inside the app. */
    val soon = listOf(
        ModelInfo("noa", "נועה (Piper)", "קול נשי מהיר מאוד.", ModelKind.SOON, license = "CC BY 4.0", credit = "thewh1teagle"),
        ModelInfo("clone", "הקול שלי", "מקליטים 15-30 שניות, והאפליקציה מדברת בקול שלך.", ModelKind.SOON, license = "MIT", credit = "BlueTTS / Chatterbox"),
        ModelInfo("chatterbox", "Chatterbox", "שכפול קול רב-לשוני, כבד יותר.", ModelKind.SOON, license = "MIT", credit = "Resemble AI"),
        ModelInfo("omnivoice", "OmniVoice", "600+ שפות ושכפול קול. נבדוק עברית.", ModelKind.SOON, license = "Apache-2.0", credit = "k2-fsa"),
        ModelInfo("orpheus", "Orpheus עברית", "מודל גדול (3B), ניסיוני, ירוץ על ה-NPU.", ModelKind.SOON, license = "לבדיקה", credit = "Canopy Labs"),
    )

    val downloadable = listOf(g2p, blueV3, blue25)
    val all = downloadable + soon

    val voices = listOf(
        VoiceInfo("v3_roi", "blue_v3", "voices/Roi.json", "רועי", "גברי · V3", female = false),
        VoiceInfo("v3_rotem", "blue_v3", "voices/Rotem.json", "רותם", "נשי · V3", female = true),
        VoiceInfo("25_female", "blue_25", "voices/female.json", "נשי", "2.5", female = true),
        VoiceInfo("25_m6209", "blue_25", "voices/libri_male_6209.json", "גברי 1", "2.5", female = false),
        VoiceInfo("25_m8088", "blue_25", "voices/libri_male_8088.json", "גברי 2", "2.5", female = false),
        VoiceInfo("25_f1088", "blue_25", "voices/libri_female_1088.json", "נשי 2", "2.5", female = true),
        VoiceInfo("25_f6147", "blue_25", "voices/libri_female_6147.json", "נשי 3", "2.5", female = true),
    )

    fun model(id: String) = all.first { it.id == id }
}
