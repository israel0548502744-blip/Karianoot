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
- `BlueTts.Options.perSentence` (default on): pieces of 2-3 sentences (`CHUNK_CHARS` 130); as accurate as
  one sentence at a time (ASR), fewer audible joins.
- `Joiner.kt` joins pieces (user: joins sounded "stop and go"): trims silent edges (4 ms fades; the old 25 ms fades clipped
  Blue, which starts/ends right on the speech), sets every sentence pause (inside pieces and at joins) to one length =
  the voice's own median (Blue ~0.1 s, Qwen ~0.3-0.45 s) or the slider ("טבעית" = 0), evens loudness ±3 dB. Same ASR. `Options.temperature` = liveliness (Namer 1.1, picked by ear).
- `TextPrep.kt`: football text cleanup before G2P (1X2, handicap minus, scores vs times, ת"א etc., Latin acronyms
  -> Hebrew letter names with niqqud). Runs after the user's pronunciation fixes.
- Voice picker page (artifact FxyMdtTTTA4sxxSno8SZtU, db collection `picks`): the user's choices per round.
- `Lexicon.kt`: ~80 football names/terms the G2P misreads (תיקו "teko", ארסנל, מבאפה, נתניה, גווארדיולה...) -> stressed IPA,
  substituted just before the G2P (it copies non-Hebrew through; GoldenTest checks), Hebrew prefixes read too ("ולמבאפה").
  Found by phonemizing ~200 club/player/term names and checking each by hand. Add new misreads there.
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
- Rejected: Chatterbox (bad Hebrew), Zonos-Hebrew / MamreTTS / robo_shaul (NC), Fish-Audio-S2-Pro-He (Fish Audio research
  license = NC), voxtream2-he (GPL), Kokoro-he (NC data),
  OmniVoice (Apache, only ~13 h Hebrew: many word errors, very slow), VoxCPM2 (Apache, good Hebrew but 2B / 5 GB,
  RTF ~17 on CPU: too heavy for the phone; useful offline for designing voices from a text description).
- Qwen measurements (Nov 9): whole passage in one pass drops the end -> keep 130-char pieces; sampling (0.3-0.9) is as
  accurate as greedy (ASR ~1% CER on number-free sentences), so retakes sample at 0.6 (greedy repeats itself).

- Magpie Hebrew (notmax123/magpie_tts_multilingual_357m-he, NVIDIA Open Model License = commercial OK; 357M, 15 baked
  Hebrew voices, IPA in, no cloning). Probed with NeMo on CPU (`magpie-probe.yml`, CUDA->CPU shim): CER 0.6% on 13/15
  voices (Qwen ~1%), male voices lively (pitch movement 4.6-5.9 st). NeMo itself is slow (RTF ~11, no KV cache).
  Samples in picker round r12; waiting for the user's ear before building it in.
  Phone path if good: github.com/mudler/magpie-tts.cpp (MIT, ggml, C API `magpie_tts.h`, ~realtime on aarch64 CPU per its
  benchmarks); the Hebrew ckpt would need converting (extra char tokenizer + 20 baked speakers).
- Qwen + punctuation (`qwen-energy.yml`, Nov 9): "!!" at sentence ends sometimes raises pitch movement/range (one piece
  4.2 -> 5.8 st), often no change; "," before key words no effect; CER unchanged. Not built in; samples in picker round r11.

## User picks (Nov 9 noon, picker db `picks`)
- Magpie (r12): best female5 (speaker 10, "almost fully realistic"); loved voice3 (19) and voice2 (18, "a bit robotic");
  good 6, 9, 11, 12, 13, 15, 16; rejected 7, 8, 14, 17 (5 unmarked). -> building Magpie into the app.
- Qwen: most voices rejected for an "Argentinian accent" = a strong/rolled ר (r6, r8, r11). Keep d_commentator, d_hype,
  d_veteran. The VoxCPM2 reference of d_commentator was the favourite of r8 (too heavy for the phone). Older r5: full-precision
  Python Qwen male2/tet "good", the phone engine (qwentts.cpp Q4_K_M) "bad" -> quantization may hurt.
- Joins (r9): picked the OLD Blue joins (0.2 s between pieces) over every sentence at 0.11 s -> Joiner floor, Blue MIN_PAUSE 0.2.
- Names (r10, Qwen commentator): "not accurate, mistakes in words, still robotic".

## Qwen in the app (Nov 9 2026)
- `android/qwen/`: `qwen_jni.cpp` (JNI over the qwentts.cpp C API), `build_android.sh` (NDK arm64, run by build-apk.yml),
  `patch_qwentts.sh` (QT_N_THREADS override, qwenjni target). Kotlin: `engine/QwenNative.kt` (`QwenTts`).
- `qwen-voices.yml`: host build, preset voice prints (`app/src/main/assets/qwen/*.spk`, 2048 f32), and `QwenTest`
  (Kotlin -> JNI output equals the CLI, corr 1.0). Qwen uses greedy decoding (0 word errors in tests).
- Studio groups voices: Qwen / Blue 2.5 / Blue V3 / mine, with a men/women filter; "השמע דוגמה" (cached sample per voice),
  time estimate (measured work/audio per model), cancel, last voice remembered.
- `TakeCheck.kt`: a Qwen piece far from 18.5 IPA chars/s of speech (outside 11-30) or with a pause >= 1.2 s inside is
  broken (ran on / dropped words / stalled) and is retaken sampled (up to 2x). 165/165 good takes pass, 5 ICL runaways caught.
- Qwen speed = `TimeStretch.kt` (WSOLA, pitch kept; ASR clean at 0.9-1.3x). Retakes sample at temperature 0.6.
- Designed voices (`voice-design.yml`): VoxCPM2 voice design from an English description -> reference clip -> Qwen
  voice print (`assets/qwen/q_d_*.spk`). Synthetic, commercial-safe. VoxCPM2 sometimes adds gibberish in long refs:
  keep reference text to one sentence. Qwen ICL ("full clone": style codes + transcript) is wired in the bridge
  (`speakStyled`, tested equal to the CLI) but NOT used: with designed refs 4/5 voices ran away (77-96 s, CER up to 79%).

## Magpie in the app (Nov 10 2026)
- `magpie-cpp.yml` -> prerelease `magpie-gguf`: `tools/magpie/convert_he.py` (ckpt d0c5e96, codec fc00890) + `android/magpie/magpie_he.patch`
  (Hebrew char tokenizer) on magpie-tts.cpp 3008ff7. q8_0 (603 MB) CER 0.9% (NeMo 1.0%); q4_k breaks speaker 10 (CER 15%);
  attention prior hurts (7.8%). 22.05 kHz, temp 0.7, topk 80, cfg 2.5. Work/audio 2.2 on 4 CI cores (phone not measured).
- App (apk-63): q8_0 pinned by sha256, 10 voices (10 default, 18, 19, 6, 9, 11, 12, 13, 15, 16); `magpie-jni.yml` checks JNI = CLI.
  Joiner floor per engine (Blue 0.2, Qwen 0.3, Magpie 0.25).
- Lexicon round 2: ~940 names (England / Spain-Portugal-Italy / rest of world / Israel+terms) checked by phonemizing; prefixed forms
  that are other words are in PROTECT (שמאלי, מואר, כפפה, מקוון, לטאה...). TextPrep: וי.איי.אר -> VAR, ליגה א'/ב'/ג' -> letter names.
- Qwen rolled r (`qwen-accent.yml`, prerelease `qwen-accent`): no fix found. Quantization (q4/q8/bf16), language tag, temperature
  and ICL change nothing; ʁ->χ makes it a fricative but CER 16%. The trill detector also calls Blue (control) 100% rolled, so the
  numbers are unreliable: the user's ear decides.

## New models probe (Nov 10-11 2026, `new-models-probe.yml`, prerelease `new-models-probe`)
- MOSS-TTS-Nano (Apache-2.0): Hebrew unusable (CER 52-58% on every voice). Rejected.
- Pocket TTS (thewh1teagle/pocket-tts-onnx, english-ipa graph 232 MB, CC BY 4.0, cloning): with RenikudPlus IPA CER 1-7%
  (omer / javert 1.1%), work/audio 0.31 on 4 CI cores (Magpie 2.2). Its own G2P (conikud) gives CER ~50%: use RenikudPlus.
  Samples in picker round r14_pocket (8 voices + a clone of Magpie speaker 10); waiting for the user's ear. Not in the app.
- Automatic ear (`tools/ear/`, `ear.yml`): UTMOS agrees with the user's picks only weakly (AUC 0.62); use it as a floor
  (< 2.6 = bad), never as proof of quality. DNSMOS is near chance.
- Lexicon round 3 (apk-65): 51 Saudi / MLS / South America names. Odds "3.40" -> "שלוש ארבעים" is the intended reading.

## Plan
1. Blue V3 + Blue 2.5 + RenikudPlus (done in v1).
2. Hebrew number/date normalization (done: HebrewNumbers.kt).
3. "My voice": Blue zero-shot from a recording (blue-onnx-v2 `codec_encoder`/`style_encoder`), then Chatterbox.
4. Noa (Piper, CC BY) with Phonikud; OmniVoice (check Hebrew); Orpheus Hebrew (check license) on the NPU.
5. NPU (QNN) for the heavy graphs; background music mixing; templates for the football videos.
