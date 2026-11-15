# Wake word models

> **Last reviewed:** 2026-11-15.

Used by `voice/OpenWakeWordDetector.kt`. All three files must be here, or the wake word falls back
to the no-op stub (logcat tag `WakeWordDetector`).

| File | What | Source / license |
|---|---|---|
| `melspectrogram.onnx` | PCM to mel frames | openWakeWord v0.5.1 release asset |
| `embedding_model.onnx` | mel to 96-d speech embedding (frozen) | openWakeWord v0.5.1 release asset, a re-implementation of Google's Apache-2.0 `speech_embedding` |
| `hey_aura.onnx` | embeddings to "Hey AURA" score | **trained by us**, see below |

The openWakeWord code is Apache-2.0. Its README puts its *bundled pre-trained wake-word classifiers*
(hey_jarvis, alexa, ...) under CC BY-NC-SA 4.0. Do not ship one of those here.

## Training `hey_aura.onnx`

1. Open the patched 2026 notebook in Colab:
   https://colab.research.google.com/github/alfiedennen/openwakeword-colab-2026/blob/main/train_wakeword.ipynb
   (openWakeWord's official notebook no longer runs on current Colab).
2. Runtime → Change runtime type → T4 GPU (free) or L4 (Colab Pro, about twice as fast).
3. In cell 10 set `TARGET_PHRASE = ['hey aura', 'hey ora', 'hey awra']` and
   `MODEL_NAME = 'hey_aura'`. The respellings steer the TTS toward how people say "aura".
4. Runtime → Run all, wait about 75 to 90 minutes. The last cell downloads `hey_aura.onnx`.
5. Put it here. The detector reads the window length from the model's input `[1, N, 96]`, and the
   notebook bakes the sigmoid in, so scores are 0..1.
6. Tune `threshold` in `OpenWakeWordDetector` (default 0.5) on-device: raise it if it fires on
   TV or "or a", lower it if it misses you across the room. Logcat tag `OpenWakeWord`.

**Licensing:** the notebook validates against ACAV100M audio (CC BY-NC-SA). For a commercial
build, retrain with permissively licensed negatives (Common Voice CC0, MUSAN, own recordings).

Why "Hey AURA" and not "AURA": bare "aura" is two open vowels and collides with everyday "or a" /
"our a". The "hey" adds a consonant onset and a third syllable for the model to anchor on.
