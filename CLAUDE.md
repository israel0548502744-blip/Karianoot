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

## Plan
1. Blue V3 + Blue 2.5 + RenikudPlus (done in v1).
2. Hebrew number/date normalization (port `hebrew_num2words` / blue `text_norm`).
3. "My voice": Blue zero-shot from a recording (blue-onnx-v2 `codec_encoder`/`style_encoder`), then Chatterbox.
4. Noa (Piper, CC BY) with Phonikud; OmniVoice (check Hebrew); Orpheus Hebrew (check license) on the NPU.
5. NPU (QNN) for the heavy graphs; background music mixing; templates for the football videos.
