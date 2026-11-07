"""内置翻译模型：Hy-MT2-1.8B-4bit（MLX，进程内推理）。

不依赖 oMLX 等服务；模型随应用提供。也支持在没有内置模型时回退到 Hugging Face。
"""
from __future__ import annotations

import asyncio
import threading
import time
import traceback
from pathlib import Path

from .config import DATA_DIR, LANG_NAMES, ROOT

BUILTIN_DIR = ROOT / "models" / "hy-mt2-1.8b-4bit"
ASSISTANT_DOWNLOAD_DIR = DATA_DIR / "models" / "assistant"
FALLBACK_REPO = "sahilchachra/hy-mt2-1.8b-4bit-mlx"
TEST_SENTENCE = "今天下午三点我们在大会议室开项目评审会，请把上周的原型带过来。"


class LocalTranslator:
    model_name = "Hy-MT2-1.8B-4bit（内置）"

    def __init__(self):
        self.status = "idle"  # idle | loading | ready | error
        self.error: str | None = None
        self._model = None
        self._tokenizer = None
        self._lock = threading.Lock()

    # ---- 加载 ----
    def load(self) -> None:
        self.status = "loading"
        self.error = None
        try:
            from mlx_lm import load

            path = str(BUILTIN_DIR) if (BUILTIN_DIR / "config.json").exists() else FALLBACK_REPO
            self._model, self._tokenizer = load(path)
            # 预热
            prompt = self._build_prompt("你好", "en")
            for _ in self._generate(prompt, max_tokens=8):
                pass
            self.status = "ready"
        except Exception as exc:  # noqa: BLE001
            self.status = "error"
            self.error = f"{exc}\n{traceback.format_exc(limit=3)}"

    def load_async(self) -> None:
        threading.Thread(target=self.load, daemon=True).start()

    # ---- 提示词 ----
    def _build_prompt(self, text: str, target_lang: str, glossary: str = "") -> str:
        target = LANG_NAMES.get(target_lang, target_lang)
        message = ""
        if glossary:
            message += f"术语译名（保持前后一致）：{glossary}\n\n"
        message += (
            f"将以下文本翻译成{target},注意只需要输出翻译后的结果,不要额外解释:\n\n{text}"
        )
        return self._tokenizer.apply_chat_template(
            [{"role": "user", "content": message}],
            tokenize=False,
            add_generation_prompt=True,
        )

    def _generate(self, prompt: str, max_tokens: int = 512):
        from mlx_lm import stream_generate
        from mlx_lm.sample_utils import make_logits_processors, make_sampler

        sampler = make_sampler(temp=0.3, top_p=0.6, top_k=20)
        logits = make_logits_processors(repetition_penalty=1.05)
        with self._lock:
            for response in stream_generate(
                self._model,
                self._tokenizer,
                prompt,
                max_tokens=max_tokens,
                sampler=sampler,
                logits_processors=logits,
            ):
                yield response.text

    # ---- 翻译（流式） ----
    async def translate_stream(
        self,
        text: str,
        source_lang: str,
        target_lang: str,
        context: list[str] | None = None,
        max_tokens: int = 512,
        glossary: str = "",
    ):
        if self.status != "ready":
            raise RuntimeError("内置翻译模型尚未加载完成")
        prompt = self._build_prompt(text, target_lang, glossary)
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue = asyncio.Queue()

        def worker() -> None:
            try:
                for chunk in self._generate(prompt, max_tokens=max_tokens):
                    loop.call_soon_threadsafe(queue.put_nowait, chunk)
                loop.call_soon_threadsafe(queue.put_nowait, None)
            except Exception as exc:  # noqa: BLE001
                loop.call_soon_threadsafe(queue.put_nowait, exc)

        threading.Thread(target=worker, daemon=True).start()
        started = False
        while True:
            item = await queue.get()
            if item is None:
                return
            if isinstance(item, Exception):
                raise item
            from .llm import clean_text

            item = clean_text(str(item))
            if not started and not item.strip():
                continue
            started = True
            yield item

    # ---- 其它接口 ----
    async def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        system: str = "",
        temperature: float = 0.3,
    ) -> str:
        if self.status != "ready":
            raise RuntimeError("内置翻译模型尚未加载完成")
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        full = self._tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True
        )

        def run() -> str:
            return "".join(self._generate(full, max_tokens=max_tokens))

        return (await asyncio.to_thread(run)).strip()

    async def correct_text(self, text: str, context: list[str] | None = None) -> str:
        # 翻译专用模型不适合做改写，直接保留原文
        return text

    async def test_translate(self, target_lang: str = "en") -> dict:
        started = time.perf_counter()
        first_ms = None
        parts: list[str] = []
        async for chunk in self.translate_stream(TEST_SENTENCE, "zh", target_lang):
            if first_ms is None:
                first_ms = int((time.perf_counter() - started) * 1000)
            parts.append(chunk)
        total_ms = int((time.perf_counter() - started) * 1000)
        output = "".join(parts).strip()
        if not output:
            raise RuntimeError("内置模型没有返回内容")
        return {
            "ok": True,
            "first_ms": first_ms,
            "total_ms": total_ms,
            "source": TEST_SENTENCE,
            "translation": output,
        }

    async def ping(self) -> bool:
        return self.status == "ready"

    async def list_models(self) -> list[str]:
        return [self.model_name]


class LocalAssistant:
    """内置助教模型：Qwen3-4B-Instruct-2507（与翻译模型完全独立）。"""

    model_name = "Qwen3-4B-Instruct-2507（内置助教）"
    BUNDLED = ROOT / "models" / "assistant"
    REPO = "mlx-community/Qwen3-4B-Instruct-2507-4bit"

    def __init__(self):
        self.status = "idle"
        self.error: str | None = None
        self._model = None
        self._tokenizer = None
        self._lock = threading.Lock()

    def load(self) -> None:
        self.status = "loading"
        self.error = None
        try:
            from mlx_lm import load

            local = next(
                (
                    p
                    for p in (ASSISTANT_DOWNLOAD_DIR, self.BUNDLED)
                    if (p / "config.json").exists()
                ),
                None,
            )
            if local is None and (ROOT / "models" / "lite").exists():
                self.status = "missing"
                self.error = "轻量版未附带助教模型，请在「设置 → 模型管理」中下载"
                return
            path = str(local) if local else self.REPO
            self._model, self._tokenizer = load(path)
            prompt = self._build_prompt("直接回答：1+1 等于几？", "")
            for _ in self._generate(prompt, max_tokens=8):
                pass
            self.status = "ready"
        except Exception as exc:  # noqa: BLE001
            self.status = "error"
            self.error = f"{exc}\n{traceback.format_exc(limit=3)}"

    def load_async(self) -> None:
        threading.Thread(target=self.load, daemon=True).start()

    def _build_prompt(self, prompt: str, system: str) -> str:
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        return self._tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True
        )

    def _generate(self, prompt: str, max_tokens: int = 512):
        from mlx_lm import stream_generate
        from mlx_lm.sample_utils import make_sampler

        sampler = make_sampler(temp=0.3, top_p=0.9)
        with self._lock:
            for response in stream_generate(
                self._model,
                self._tokenizer,
                prompt,
                max_tokens=max_tokens,
                sampler=sampler,
            ):
                yield response.text

    async def generate(
        self, prompt: str, max_tokens: int = 512, system: str = "", temperature: float = 0.3
    ) -> str:
        if self.status != "ready":
            raise RuntimeError("助教模型尚未加载完成")
        full = self._build_prompt(prompt, system)

        def run() -> str:
            return "".join(self._generate(full, max_tokens=max_tokens))

        from .llm import clean_text

        return clean_text((await asyncio.to_thread(run)).strip())

    async def ping(self) -> bool:
        return self.status == "ready"
