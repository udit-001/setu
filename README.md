# Setu

Speak into your phone. It transcribes, translates, and speaks back — in seven languages, entirely on your device.

<img width="480" alt="Setu app screenshot" src="https://github.com/user-attachments/assets/4f24f6bd-4bc7-4214-99ef-7610610f4723" />

Setu is an offline voice translator for Indian languages. Two people who don't share a language each pick their own. The app transcribes each turn, translates it, and reads it aloud. No cloud, no account, no signal needed after setup.

## What you get

- **Two-way conversations** — pick a language for each speaker, take turns with the mic and the swap button, and each side hears and reads the translation in their own language.
- **Seven languages** — English, Hindi, Kannada, Tamil, Telugu, Marathi, Malayalam, with more Indic languages on the way.
- **No internet needed to translate** — after a one-time model download, speech recognition, translation, and text-to-speech all run on the phone. Made for remote areas, travel, and patchy networks.
- **Private by design** — your recordings and transcripts never leave the device. No analytics, no telemetry, no accounts: nothing leaves your phone.
- **Fits your phone** — the translation model is picked by your device's RAM: higher-accuracy on 8GB+ phones, a compact version on 6GB and below, so it doesn't crash mid-conversation.
- **Download only what you speak** — speech models (~140MB each) install per language, on first use.
- **Speed you can verify** — an opt-in stats view shows exactly how long recognition and translation took for each exchange.

## How it works

[AI4Bharat's IndicConformer](https://huggingface.co/ai4bharat/IndicConformer) does the speech-to-text, [Sarvam AI's Sarvam Translate](https://huggingface.co/sarvamai/sarvam-translate) does the translation (running on-device via llama.cpp), and Android's built-in offline text-to-speech does the speaking. Speech models run through [sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx); translation runs as a quantized GGUF.

## Building from source

You'll need Android Studio, the Android SDK (min API 34), the NDK, and CMake.

1. Clone this repository and open it in Android Studio.
2. Open in Android Studio, install NDK + CMake from the SDK Manager if prompted, and run on a physical device (6GB+ RAM recommended; emulators struggle with on-device inference).

First launch downloads the translation model (1–2.5GB). Speech models download per language as you select them.

## Contributing

Issues and feature requests are welcome. If you're planning something big — like adding support for a new Indic language — open an issue first so we can talk through it.

## License

[GPL-3.0](LICENSE)
