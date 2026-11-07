"""SenseVoice-Small（sherpa-onnx）离线识别引擎。

比 Whisper 快 4–6 倍，自带标点与数字归一化；模型首次使用时自动下载（约 155MB）。
"""
from __future__ import annotations

import tarfile
import threading
import traceback
from pathlib import Path

import httpx
import numpy as np

from .config import DATA_DIR

try:  # 繁体 -> 简体
    import zhconv
except Exception:  # noqa: BLE001
    zhconv = None

MODEL_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
)
MODEL_DIR = DATA_DIR / "models" / "sensevoice"


def ensure_model(model_dir: Path = MODEL_DIR) -> None:
    """确保模型文件存在，不存在则下载并解压。"""
    model = model_dir / "model.int8.onnx"
    tokens = model_dir / "tokens.txt"
    if model.exists() and tokens.exists():
        return
    model_dir.mkdir(parents=True, exist_ok=True)
    tmp = model_dir / "download.tar.bz2"
    try:
        with httpx.stream(
            "GET",
            MODEL_URL,
            follow_redirects=True,
            timeout=httpx.Timeout(connect=10.0, read=None, write=None, pool=None),
        ) as resp:
            resp.raise_for_status()
            with open(tmp, "wb") as fh:
                for chunk in resp.iter_bytes(1 << 20):
                    fh.write(chunk)
        with tarfile.open(tmp, "r:bz2") as tar:
            wanted = [
                m
                for m in tar.getmembers()
                if m.name.endswith(("model.int8.onnx", "tokens.txt"))
            ]
            for member in wanted:
                member.name = Path(member.name).name
                tar.extract(member, model_dir, filter="data")
    finally:
        tmp.unlink(missing_ok=True)


class SenseVoiceEngine:
    model_name = "SenseVoice-Small"

    def __init__(self):
        self.status = "idle"  # idle | loading | ready | error
        self.error: str | None = None
        self._recognizer = None
        self._lock = threading.Lock()

    # ---- 加载 ----
    def load(self) -> None:
        self.status = "loading"
        self.error = None
        try:
            ensure_model()
            import sherpa_onnx

            self._recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
                model=str(MODEL_DIR / "model.int8.onnx"),
                tokens=str(MODEL_DIR / "tokens.txt"),
                num_threads=4,
                use_itn=True,
                language="auto",
                debug=False,
            )
            stream = self._recognizer.create_stream()
            stream.accept_waveform(16000, np.zeros(1600, dtype=np.float32))
            self._recognizer.decode_stream(stream)
            self.status = "ready"
        except Exception as exc:  # noqa: BLE001
            self.status = "error"
            self.error = f"{exc}\n{traceback.format_exc(limit=3)}"

    def load_async(self) -> None:
        threading.Thread(target=self.load, daemon=True).start()

    # ---- 识别 ----
    def transcribe(
        self,
        audio: np.ndarray,
        language: str | None = None,
        initial_prompt: str | None = None,
    ) -> tuple[str, str]:
        if self._recognizer is None:
            raise RuntimeError("SenseVoice 模型尚未加载完成")
        audio = np.ascontiguousarray(audio, dtype=np.float32)
        with self._lock:
            stream = self._recognizer.create_stream()
            stream.accept_waveform(16000, audio)
            self._recognizer.decode_stream(stream)
            result = stream.result

        text = (result.text or "").strip()
        raw_lang = (getattr(result, "lang", "") or "").strip("<|> ")
        lang = raw_lang or (language or "zh")

        if text and zhconv is not None and lang.startswith(("zh", "yue")):
            text = zhconv.convert(text, "zh-cn")
        return text, lang
