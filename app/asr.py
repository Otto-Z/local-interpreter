"""基于 MLX 的本地 Whisper 语音识别。"""
from __future__ import annotations

import threading
import traceback

import numpy as np

try:  # 繁体 -> 简体，Whisper 偶尔会输出繁体
    import zhconv
except Exception:  # noqa: BLE001
    zhconv = None

# 短音频里 Whisper 常见的“幻觉”输出，静音段容易冒出来
_HALLUCINATIONS = {
    "谢谢观看", "谢谢观看。", "感谢观看", "请不吝点赞 订阅 转发 打赏支持明镜与点点栏目",
    "字幕由Amara.org社区提供", "字幕志愿者 杨茜茜", "优优独播剧场——YoYo Television Series Exclusive",
    "thank you", "thanks for watching", "thank you for watching",
    "subtitles by the amara.org community", "you", "bye", "。",
}


class ASREngine:
    def __init__(self, model_name: str):
        self.model_name = model_name
        self.status = "idle"  # idle | loading | ready | error
        self.error: str | None = None
        self._lock = threading.Lock()
        self._import_error: str | None = None

    # ---- 加载 ----
    def load(self) -> None:
        self.status = "loading"
        self.error = None
        try:
            import mlx_whisper  # noqa: F401

            # 用 1 秒静音预热，触发模型下载与编译，避免第一句话延迟过高
            warm = np.zeros(16000, dtype=np.float32)
            mlx_whisper.transcribe(
                warm,
                path_or_hf_repo=self.model_name,
                language="en",
                temperature=0.0,
                condition_on_previous_text=False,
                verbose=None,
            )
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
        """audio: float32 mono 16k。返回 (文本, 语言代码)。"""
        import mlx_whisper

        audio = np.ascontiguousarray(audio, dtype=np.float32)
        kwargs = dict(
            path_or_hf_repo=self.model_name,
            language=language or None,
            task="transcribe",
            temperature=0.0,
            condition_on_previous_text=False,
            no_speech_threshold=0.6,
            verbose=None,
        )
        if initial_prompt:
            kwargs["initial_prompt"] = initial_prompt
        with self._lock:
            result = mlx_whisper.transcribe(audio, **kwargs)
        text = str(result.get("text") or "").strip()
        lang = str(result.get("language") or language or "")

        if text and zhconv is not None and lang.startswith(("zh", "yue")):
            text = zhconv.convert(text, "zh-cn")

        duration = len(audio) / 16000.0
        if text.lower().strip(" .。!！") in _HALLUCINATIONS or (
            duration < 1.2 and text.lower().strip(" .。!！") in _HALLUCINATIONS
        ):
            text = ""
        if duration < 0.3:
            text = ""
        return text, lang
