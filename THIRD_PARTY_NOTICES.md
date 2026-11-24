# Third-Party Notices & Licenses

Local Interpreter incorporates or downloads the following third-party open-source software and models. Each component remains governed by its respective license.

## Bundled Source Code & Prebuilt Libraries / Models

| Component | Location | License | Upstream |
|---|---|---|---|
| **llama.cpp / ggml** (with PR #19357 Q2_0C & PR #22836 STQ1_0 patches) | `android/vendor/llama.cpp` | MIT | https://github.com/ggml-org/llama.cpp |
| **sherpa-onnx** (JNI bindings & ARM64 runtime) | `android/app/src/main/java/com/k2fsa/sherpa/onnx`, `android/app/src/main/jniLibs/arm64-v8a` | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| **Silero VAD** (`silero_vad.onnx`) | `android/app/src/main/assets/silero_vad.onnx` | MIT | https://github.com/snakers4/silero-vad |
| **3D-Speaker CAM++** (`speaker_campplus.onnx`) | `android/app/src/main/assets/speaker_campplus.onnx` | Apache-2.0 | https://github.com/modelscope/3D-Speaker |
| **PDFBox-Android** | Gradle dependency (`com.tom-roush:pdfbox-android`) | Apache-2.0 | https://github.com/TomRoush/PdfBox-Android |
| **OkHttp / AndroidX / Jetpack Compose / Kotlin Coroutines** | Gradle dependencies | Apache-2.0 | https://github.com/square/okhttp |
| **Rust Core & Cloud crates** (`axum`, `tokio`, `rusqlite`, `uniffi`, `reqwest`, `chacha20poly1305`, etc.) | `Cargo.toml` / `Cargo.lock` | MIT / Apache-2.0 / MPL-2.0 (UniFFI) | https://crates.io |
| **pypdf** (legacy Python backend) | `requirements.txt` | BSD-3-Clause | https://github.com/py-pdf/pypdf |

## Runtime-Downloaded Models (Not Bundled in Repository)

Models below are downloaded directly by users from Hugging Face / HF-Mirror at runtime:

| Model | License | Upstream |
|---|---|---|
| **Tencent Hy-MT2-1.8B (Q4_K_M & 1.25Bit STQ1_0 QAT)** | Apache-2.0 | https://huggingface.co/tencent/Hy-MT2-1.8B |
| **Qwen3-4B-Instruct-2507 / Qwen2.5-1.5B-Instruct** | Apache-2.0 | https://huggingface.co/Qwen |
| **FunAudioLLM SenseVoiceSmall** | Apache-2.0 | https://github.com/FunAudioLLM/SenseVoice |
| **Whisper Large-v3-Turbo (`whisper.cpp` GGML)** | MIT | https://huggingface.co/ggerganov/whisper.cpp |

## Android Signing Key Notice

`android/app/localinterpreter.jks` is a **public community/development keystore** provided solely so open-source contributors and CI can produce installable APKs with consistent upgrade signatures. For any independent or production distribution, supply your own private keystore via environment variables (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
