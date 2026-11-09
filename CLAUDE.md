# Karianoot (קריינות)

## ⚠️ חובה: לענות למשתמש בעברית בלבד
- **כל הודעה למשתמש בעברית בלבד**, כולל עדכוני ביניים, הודעות "ממתין", סיכומים והודעת הסיום. בלי יוצא מן הכלל.
- המשתמש לא מבין אנגלית, וכבר ביקש את זה הרבה פעמים. לפני כל הודעה: לבדוק שהיא בעברית.
- קוד, שמות קבצים ופקודות נשארים כמו שהם, אבל ההסבר סביבם בעברית.

Android app (Galaxy S25 Ultra, Snapdragon 8 Elite): Hebrew text → narration, fully on-device.
The user makes football-tips videos; this replaces ElevenLabs for their Hebrew voiceovers.

## Rules
- The user is not a developer: explain simply (in Hebrew, see above).
- **Only commercially usable licenses** (MIT / Apache / BSD / CC BY). No non-commercial (NC) models or code.
  CC BY needs credit in the app's About screen (`Catalog.credit`).
- The APK stays small. Models are downloaded inside the app (`Models.kt`, Android DownloadManager) into
  external files storage, which survives app updates. Every model file is pinned to a Hugging Face revision.
- Never claim something works without a real test (CI run, golden test, or the user's report).
- Work on branch `ccr-f3f510dd-kd4y2y`; no PR unless asked.
- After an app change, wait for CI "Build Karianoot APK" and send the user the release link.

## Layout
- `android/engine/` — plain-Kotlin speech engine (no Android classes) so CI tests it on the JVM:
  `HebrewG2P.kt` (port of RenikudPlus), `BlueTts.kt` (port of blue_onnx), `Npy.kt`, `Wav.kt`.
- `android/app/` — Compose UI (`ui/Theme.kt` black + gold design, `ui/Screens.kt`), `Catalog.kt` (models, voices),
  `Models.kt` (downloads), `Speech.kt` (loads one engine at a time, saves creations).
- `tools/reference.py` + `.github/workflows/reference.yml` — runs the original Python packages, then checks the
  Kotlin engine gives the same phonemes and audio (golden test).
- `.github/workflows/probe-models.yml` — lists files/sizes/licenses of model repos (this sandbox can't reach HF).
- No local Android SDK here: CI builds the APK (`build-apk.yml`, release `apk-N`).

## Notes (Nov 2026)
- Numbers: `HebrewNumbers.kt` is a port of `hebrew-num2words` (golden: `tools/numbers_golden.py` → test resources);
  `HebrewG2P.phonemize` runs it first, like RenikudPlus.
- `BlueTts.Options.perSentence` (default on): pieces of 2-3 sentences (`CHUNK_CHARS` 130) with 25 ms fades; as accurate as
  one sentence at a time (ASR), fewer audible joins. `Options.temperature` = liveliness (Namer 1.1, picked by ear).
- `TextPrep.kt`: football text cleanup before G2P (1X2, handicap minus, scores vs times, ת"א etc., Latin acronyms
  -> Hebrew letter names with niqqud). Runs after the user's pronunciation fixes.
- Voice picker page (artifact FxyMdtTTTA4sxxSno8SZtU, db collection `picks`): the user's choices per round.
- `BlueTts.IPA_FIXES`: "היחס" → doubled y, "ברצלונה" → baʁtselˈona (the user picked both by ear). User fixes: `Pronounce.kt` + "תיקוני הגייה" dialog.
- Namer ("נמר"): `app/src/main/assets/voices/namer.json`, made from the user's ElevenLabs clips (the mp3s are NOT in
  git). Personal use only; needs Blue 2.5 + the clone kit (its z_ref duration predictor).
- Offline quality loop: release `asr-mirror` (ivrit-ai whisper ct2) + `models-mirror`; scripts in the session scratchpad
  synthesize, transcribe and score (CER) settings. Whisper "corrects" words, so the user's ear decides close calls.

## Research (Nov 8 2026)
- LightBlue (notmax123, MIT): its own ONNX bundle is broken (no stats/vocab), but its ~2,456 LibriTTS voices (CC BY) work
  on Blue 2.5. User liked male spk_03977 ("ט", distorts some words; blending 70/30 with libri_male_8088 helps).
- QwenTTS-he-1.7B (Apache-2.0, LoRA on Qwen3-TTS) clones a voice from a clip; 0 word errors in ASR, livelier. Phone path:
  notmax123/QwenTTS-he-1.7B-GGUF (Q4_K_M, 1.47 GB) on qwentts.cpp (MIT, C API, has a Termux build); CPU RTF ~2.9 on a
  4-core CI runner (`qwen-cpp-probe.yml`). Input is stressed IPA (RenikudPlus).
- Rejected: Chatterbox (bad Hebrew), Zonos-Hebrew / MamreTTS / robo_shaul (NC), voxtream2-he (GPL), Kokoro-he (NC data),
  OmniVoice (Apache, only ~13 h Hebrew: many word errors, very slow), VoxCPM2 (Apache, good Hebrew but 2B / 5 GB,
  RTF ~17 on CPU: too heavy for the phone; useful offline for designing voices from a text description).
- Qwen measurements (Nov 9): whole passage in one pass drops the end -> keep 130-char pieces; sampling (0.3-0.9) is as
  accurate as greedy (ASR ~1% CER on number-free sentences), so retakes sample at 0.6 (greedy repeats itself).

## Qwen in the app (Nov 9 2026)
- `android/qwen/`: `qwen_jni.cpp` (JNI over the qwentts.cpp C API), `build_android.sh` (NDK arm64, run by build-apk.yml),
  `patch_qwentts.sh` (QT_N_THREADS override, qwenjni target). Kotlin: `engine/QwenNative.kt` (`QwenTts`).
- `qwen-voices.yml`: host build, preset voice prints (`app/src/main/assets/qwen/*.spk`, 2048 f32), and `QwenTest`
  (Kotlin -> JNI output equals the CLI, corr 1.0). Qwen uses greedy decoding (0 word errors in tests).
- Studio groups voices: Qwen / Blue 2.5 / Blue V3 / mine, with a men/women filter; "השמע דוגמה" (cached sample per voice),
  time estimate (measured work/audio per model), cancel, last voice remembered.
- Qwen speed = `TimeStretch.kt` (WSOLA, pitch kept; ASR clean at 0.9-1.3x). Retakes sample at temperature 0.6.
- Designed voices (`voice-design.yml`): VoxCPM2 voice design from an English description -> reference clip -> Qwen
  voice print (`assets/qwen/q_d_*.spk`). Synthetic, commercial-safe. VoxCPM2 sometimes adds gibberish in long refs:
  keep reference text to one sentence.

## Plan
1. Blue V3 + Blue 2.5 + RenikudPlus (done in v1).
2. Hebrew number/date normalization (done: HebrewNumbers.kt).
3. "My voice": Blue zero-shot from a recording (blue-onnx-v2 `codec_encoder`/`style_encoder`), then Chatterbox.
4. Noa (Piper, CC BY) with Phonikud; OmniVoice (check Hebrew); Orpheus Hebrew (check license) on the NPU.
5. NPU (QNN) for the heavy graphs; background music mixing; templates for the football videos.
