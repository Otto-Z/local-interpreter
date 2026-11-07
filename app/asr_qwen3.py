"""Qwen3-ASR（MLX）离线识别引擎。

基于 mlx-audio，支持 52 种语言/22 种中文方言，自带标点，速度接近 SenseVoice。
"""
from __future__ import annotations

import threading
import traceback

import numpy as np

try:
    import zhconv
except Exception:  # noqa: BLE001
    zhconv = None

_QWEN_LANG = {
    "zh": "Chinese",
    "en": "English",
    "yue": "Cantonese",
    "ja": "Japanese",
    "ko": "Korean",
    "fr": "French",
    "de": "German",
    "es": "Spanish",
    "ru": "Russian",
    "it": "Italian",
    "pt": "Portuguese",
    "ar": "Arabic",
    "th": "Thai",
    "vi": "Vietnamese",
}

MODEL_CHOICES = {
    "mlx-community/Qwen3-ASR-0.6B-8bit": "Qwen3-ASR-0.6B（快，约 1GB）",
    "mlx-community/Qwen3-ASR-1.7B-8bit": "Qwen3-ASR-1.7B（更准，约 2.5GB）",
}


def _lang_code(name, hint: str | None) -> str:
    if isinstance(name, list):
        name = name[0] if name else ""
    low = str(name or "").lower()
    for code, label in _QWEN_LANG.items():
        if label.lower() in low:
            return code
    return hint or "zh"


class Qwen3ASREngine:
    def __init__(self, model_name: str):
        self.repo = model_name
        self.model_name = model_name.split("/")[-1].replace("-8bit", "")
        self.status = "idle"
        self.error: str | None = None
        self._model = None
        self._lock = threading.Lock()

    # ---- 加载 ----
    def load(self) -> None:
        self.status = "loading"
        self.error = None
        try:
            from mlx_audio.stt.utils import load_model

            self._model = load_model(self.repo)
            # 预热，避免第一句话延迟过高
            self._model.generate(np.zeros(1600, dtype=np.float32), max_tokens=16)
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
        if self._model is None:
            raise RuntimeError("Qwen3-ASR 模型尚未加载完成")
        audio = np.ascontiguousarray(audio, dtype=np.float32)
        hint = _QWEN_LANG.get(language or "")
        hotwords: list[str] = []
        if initial_prompt:
            first_line = initial_prompt.split("\n", 1)[0]
            if first_line.startswith("术语："):
                for entry in first_line[3:].split(";"):
                    term = entry.split("=")[0].strip()
                    if term and len(term) <= 40:
                        hotwords.append(term)
        with self._lock:
            result = self._model.generate(
                audio,
                language=hint,
                max_tokens=512,
                hotwords=hotwords or None,
            )
        text = (result.text or "").strip()
        # 清理可能残留的语言标记
        for tag in ("<|zh|>", "<|en|>", "<|yue|>", "<|ja|>", "<|ko|>"):
            text = text.replace(tag, "")
        text = text.strip()
        lang = _lang_code(getattr(result, "language", None), language)
        if lang == "zh" and text and sum(1 for ch in text if "一" <= ch <= "鿿") < len(text) * 0.2:
            # 模型没给语言时，用文字本身判断，避免把英文锁定成中文
            lang = "en"
        if text and zhconv is not None and lang.startswith(("zh", "yue")):
            text = zhconv.convert(text, "zh-cn")
        return text, lang
