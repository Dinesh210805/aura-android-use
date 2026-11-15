# Wake word models

> **Last reviewed:** 2026-11-15.

Used by `voice/OpenWakeWordDetector.kt`. All three files must be here, or the wake word falls back
to the no-op stub (logcat tag `WakeWordDetector`).

| File | What | Source / license |
|---|---|---|
| `melspectrogram.onnx` | PCM to mel frames | openWakeWord v0.5.1 release asset |
| `embedding_model.onnx` | mel to 96-d speech embedding (frozen) | openWakeWord v0.5.1 release asset, a re-implementation of Google's Apache-2.0 `speech_embedding` |
| `hey_aura.onnx` | embeddings to "Hey AURA" score | **trained by us**, see below |

`hey_aura.onnx` is the accent run (7 positive spellings incl. "hey ohra", 15 sound-alike negatives,
5,000 clips). The first model is kept, outside the APK, at `aura-android/wakeword-models/hey_aura_v1.onnx`;
copy it over `hey_aura.onnx` to switch back. On 180 Windows-TTS clips (5 voices, 3 speeds) at
threshold 0.5, the accent run versus v1: "hi aura" 10/15 vs 5/15, "hey ohra" 13/15 both, false
wakes on "hey dora" 3/15 vs 9/15. Both still fire on "hey laura" (12/15, 14/15) and on "aura"
inside a sentence (8/15).

The openWakeWord code is Apache-2.0. Its README puts its *bundled pre-trained wake-word classifiers*
(hey_jarvis, alexa, ...) under CC BY-NC-SA 4.0. Do not ship one of those here.

## Training `hey_aura.onnx`

1. Open the patched 2026 notebook in Colab:
   https://colab.research.google.com/github/Dinesh210805/aura-android-use/blob/feat/agentcore/train_wakeword.ipynb
   (patched from alfiedennen/openwakeword-colab-2026; openWakeWord's official notebook no longer
   runs on current Colab).
2. Runtime → Change runtime type → T4 GPU (free) or L4 (Colab Pro, about twice as fast).
3. Use the repo's own copy, `train_wakeword.ipynb` at the repo root (open it in Colab from GitHub).
   Its cell 10 is already set: ten spellings of the phrase (`hey ora`, `hay aura`, `hey ahra`...)
   so it wakes across accents, near-miss names as `custom_negative_phrases` (`hey laura`,
   `hey nora`, `hey alexa`...), 4,000 clips, two augmentation rounds, 30,000 steps. Keep the
   negatives to different words: a variant of "aura" itself there makes it strict about accents.
4. Runtime → Run all, wait about 75 to 90 minutes. The last cell downloads `hey_aura.onnx`.
5. Put it here. The detector reads the window length from the model's input `[1, N, 96]`, and the
   notebook bakes the sigmoid in, so scores are 0..1.
6. Tune `threshold` in `OpenWakeWordDetector` (default 0.5) on-device: raise it if it fires on
   TV or "or a", lower it if it misses you across the room. Logcat tag `OpenWakeWord`.

**Licensing:** the notebook validates against ACAV100M audio (CC BY-NC-SA). For a commercial
build, retrain with permissively licensed negatives (Common Voice CC0, MUSAN, own recordings).

Why "Hey AURA" and not "AURA": bare "aura" is two open vowels and collides with everyday "or a" /
"our a". The "hey" adds a consonant onset and a third syllable for the model to anchor on.
