"""Qwen accent probe: texts -> IPA variants (how ר is written), plan.json, and Blue 2.5 clips (Israeli control / ICL reference).

Usage: python prep.py <voice|none> <out_dir>     (run where renikud/ and blue25/ were downloaded)
"""
import json, os, re, sys

voice, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True); os.makedirs("ipa", exist_ok=True); os.makedirs("refs", exist_ok=True)
from renikud_onnx import G2P

g = G2P("renikud/model_int8.onnx")
fix = lambda s: g.phonemize(s).replace("hajˈaχas", "hajjˈaχas").replace("baʁtslˈona", "baʁtselˈona")
PIECES = {
    "P0": "ערב טוב לכל האוהדים! הערב יש לנו משחק ענק בין ריאל מדריד לברצלונה. תסתכלו על היחס, והיחס פה פשוט מטורף.",
    "P1": "אני ממליץ לשים על ניצחון של ריאל, ושני שערים לפחות. מי שעוד לא הצטרף לקבוצה, זה הזמן. בהצלחה לכולם, ונתראה במשחק הבא!",
    "R": "ראיתי את ריאל מדריד וברצלונה רצות במגרש, הרבה רגש ויחס מטורף בערב הזה.",
}
REFS = {   # reference clips: Blue (qwen-voices.yml), designed voices 1 and 2 (voice-design*.yml)
    "REFB": "ערב טוב לכולם, והיום יש לנו שלושה משחקים מעולים. אני הולך להסביר לכם בדיוק על מה כדאי לשים, ולמה היחס הזה פשוט מטורף.",
    "REFD1": "ערב טוב לכולם, והיום יש לנו שלושה משחקים מעולים! אני הולך להסביר לכם בדיוק על מה כדאי לשים, ולמה היחס הזה פשוט מטורף.",
    "REFD2": "ערב טוב לכולם, והיום יש לנו שלושה משחקים מעולים, ואני הולך להסביר לכם בדיוק על מה כדאי לשים!",
}
SUBS = {   # how ר (ʁ in the G2P) is written for the model
    "base": lambda s: s,
    "gh": lambda s: s.replace("ʁ", "ɣ"),
    "rr": lambda s: s.replace("ʁ", "ʀ"),
    "x": lambda s: s.replace("ʁ", "χ"),
    "xcoda": lambda s: re.sub(r"ʁ(?!ˈ?[aeiou])", "χ", s),   # ʁ kept before a vowel only
    "r": lambda s: s.replace("ʁ", "r"),                      # control: should come out rolled
}
plan = {"pieces": {}, "variants": {k: {} for k in SUBS}}
for pid, text in {**PIECES, **REFS}.items():
    ipa = fix(text)
    plan["pieces"][pid] = {"text": text, "ipa": ipa}
    if pid in REFS:
        open(f"ipa/{pid}.txt", "w", encoding="utf8").write(ipa)
        continue
    for k, f in SUBS.items():
        plan["variants"][k][pid] = f(ipa)
        open(f"ipa/{k}_{pid}.txt", "w", encoding="utf8").write(f(ipa))
json.dump(plan, open("plan.json", "w", encoding="utf8"), ensure_ascii=False, indent=1)
print(json.dumps(plan, ensure_ascii=False, indent=1))

if voice != "none":
    import numpy as np, soundfile as sf, librosa
    from blue_onnx import load_text_to_speech, load_voice_style, limit_peak
    t = load_text_to_speech("blue25")
    BV = {"male2": "blue25/voices/libri_male_8088.json", "female": "blue25/voices/female.json"}

    def blue(vjson, ipa, path):
        np.random.seed(0)
        w, _ = t(ipa, lang="he", style=load_voice_style([vjson]), total_step=10, text_is_phonemes=True)
        sf.write(path, librosa.resample(limit_peak(w[0]), orig_sr=44100, target_sr=24000), 24000)

    blue(BV["male2"], plan["pieces"]["REFB"]["ipa"], "refs/blue_male2.wav")
    if voice in BV:
        blue(BV[voice], plan["pieces"]["REFB"]["ipa"], f"refs/blue_{voice}.wav")
        for pid in PIECES:   # the same texts by Blue: the Israeli-sounding control for the ר measure
            blue(BV[voice], plan["pieces"][pid]["ipa"], f"{out}/blue_{voice}__ctl__{pid}.wav")
