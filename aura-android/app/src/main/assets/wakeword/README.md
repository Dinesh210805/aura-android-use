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

1. Open openWakeWord's `notebooks/automatic_model_training.ipynb` in Google Colab (free GPU).
2. Set `target_phrase: ["hey aura"]` and `model_name: hey_aura`. Use the synthetic Piper TTS
   positives it generates, at least 5,000 samples.
3. **Licensing:** the notebook's default negative features (ACAV100M) are CC BY-NC-SA. For a
   commercial build, swap them for permissively licensed negatives (for example Common Voice
   CC0, MUSAN, or your own recordings).
4. Export ONNX and drop it here as `hey_aura.onnx`. The detector reads the window length from the
   model's input shape `[1, N, 96]`, so N need not be 16.
5. Tune `threshold` in `OpenWakeWordDetector` (default 0.5) on-device: raise it if it fires on
   TV or "or a", lower it if it misses you across the room.

Why "Hey AURA" and not "AURA": bare "aura" is two open vowels and collides with everyday "or a" /
"our a". The "hey" adds a consonant onset and a third syllable for the model to anchor on.
