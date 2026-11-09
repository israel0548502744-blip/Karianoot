# Built-in voices made from recordings

`namer.json` ("נמר"): a football-commentator style made from three ElevenLabs clips the app owner generated.
**Personal use only.** The rights to the voice are unclear; it must be removed before the app is sold or
distributed. It is flagged `personalOnly` in `Catalog.kt` and listed as such in the About screen.

# Qwen voice prints (`../qwen/*.spk`)

Speaker embeddings for QwenTTS-he (2048 float32 each), made in `.github/workflows/qwen-voices.yml` from reference clips
synthesized with Blue 2.5 / LightBlue LibriTTS voices (CC BY 4.0). `q_namer.spk` comes from the Blue Namer voice above:
**personal use only**, same as `namer.json`.
