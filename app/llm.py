"""通过任意 OpenAI 兼容接口调用本地大模型做流式翻译。

只依赖最小公共子集：
    GET  {base_url}/models
    POST {base_url}/chat/completions  (SSE 流式)
"""
from __future__ import annotations

import json
import re
import time
from collections.abc import AsyncGenerator

import httpx

from .config import LANG_NAMES

SYSTEM_PROMPT = """你是一名专业的同声传译员。把下面的语音识别文本翻译成{target}。

只输出自然的译文，不要解释、不要复述原文、不要输出思考过程。人名、数字、专有名词要准确；识别有错字时按上下文合理理解。"""

_THINK_BLOCK = re.compile(r"<think[^>]*>.*?(?:<｜end▁of▁thinking｜>|$)", re.S)
_THINK_TAG = re.compile(r"</?think[^>]*>|<｜end▁of▁thinking｜>")
_BAD_CHARS = re.compile(r"[\uE000-\uF8FF\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFD]")


def clean_text(chunk: str) -> str:
    return _BAD_CHARS.sub("", chunk)

TEST_SENTENCE = "今天下午三点我们在大会议室开项目评审会，请把上周的原型带过来。"

CORRECT_PROMPT = """你是语音识别结果的后处理编辑。请根据上下文修正识别文本中的同音字、错别字、漏字、标点和专有名词错误。

规则：
1. 只修正明显错误，不改变原意，不扩写、不缩写、不解释。
2. 人名、产品名、术语参考上下文保持一致。
3. 如果原文没有明显错误，原样返回。
4. 只输出修正后的文本，不要加引号或任何说明。"""


try:
    from pypinyin import lazy_pinyin
except Exception:  # noqa: BLE001
    lazy_pinyin = None

_PUNCT = set("，。！？、；：“”‘’（）《》【】…—,.!?;:\"'()[]-~～ ")


def _cjk_ratio(s: str) -> float:
    if not s:
        return 0.0
    count = sum(1 for ch in s if "一" <= ch <= "鿿")
    return count / len(s)


def _homophone_only_change(text: str, fixed: str) -> bool:
    """只接受“同音字替换”的中文修正，避免模型改写原意。"""
    if lazy_pinyin is None:
        return False
    if len(text) != len(fixed) or _cjk_ratio(text) < 0.4:
        return False
    changed = False
    for a, b in zip(text, fixed):
        if a == b:
            continue
        if a in _PUNCT and b in _PUNCT:
            changed = True
            continue
        if not ("一" <= a <= "鿿" and "一" <= b <= "鿿"):
            return False
        if lazy_pinyin(a) != lazy_pinyin(b):
            return False
        changed = True
    return changed


class Translator:
    def __init__(self, base_url: str, model: str, api_key: str = ""):
        self.base_url = base_url.rstrip("/")
        self.model = (model or "").strip()
        self.api_key = (api_key or "").strip()

    @property
    def _headers(self) -> dict:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    def _client(self, timeout: float = 120.0) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=httpx.Timeout(timeout, connect=3.0))

    # ---------- 服务探测 ----------
    async def list_models(self, timeout: float = 4.0) -> list[str]:
        async with self._client(timeout) as client:
            resp = await client.get(f"{self.base_url}/models", headers=self._headers)
        if resp.status_code in (401, 403):
            raise RuntimeError("API Key 无效或缺失（401）")
        if resp.status_code >= 400:
            raise RuntimeError(f"读取模型列表失败（{resp.status_code}）")
        data = resp.json()
        return [m.get("id", "") for m in data.get("data", []) if m.get("id")]

    async def probe(self, timeout: float = 1.0) -> tuple[str, list[str]]:
        """返回 ("ok"|"auth"|"error"|"down", 模型列表)。"""
        if not self.base_url:
            return "down", []
        try:
            async with self._client(timeout) as client:
                resp = await client.get(
                    f"{self.base_url}/models", headers=self._headers
                )
        except Exception:
            return "down", []
        if resp.status_code == 200:
            try:
                data = resp.json()
            except Exception:
                return "ok", []
            return "ok", [
                m.get("id", "") for m in data.get("data", []) if m.get("id")
            ]
        if resp.status_code in (401, 403):
            return "auth", []
        return "error", []

    async def ping(self, timeout: float = 2.0) -> bool:
        status, _ = await self.probe(timeout)
        return status == "ok"

    # ---------- 翻译 ----------
    def _messages(
        self, text: str, target_lang: str, context: list[str], glossary: str = ""
    ) -> list[dict]:
        target_name = LANG_NAMES.get(target_lang, target_lang)
        system = SYSTEM_PROMPT.format(target=target_name)
        if glossary:
            system += f"\n术语译名（保持前后一致）：{glossary}"

        user_parts = []
        # 不把上文放进翻译请求：部分模型会把上文一起翻译出来，导致重复字幕
        user_parts.append(f"待翻译：\n{text}")
        return [
            {"role": "system", "content": system},
            {"role": "user", "content": "\n".join(user_parts)},
        ]

    def _payload(
        self,
        text: str,
        target_lang: str,
        context: list[str],
        max_tokens: int = 512,
        glossary: str = "",
    ) -> dict:
        messages = self._messages(text, target_lang, context, glossary)
        # Qwen3 系列在部分后端会默认输出思考内容，用 /no_think 兜底（仅针对该系列）
        if "qwen3" in self.model.lower():
            messages[-1]["content"] += " /no_think"
        return {
            "model": self.model,
            "stream": True,
            "temperature": 0.2,
            "top_p": 0.9,
            "max_tokens": max_tokens,
            "messages": messages,
        }

    async def translate_stream(
        self,
        text: str,
        source_lang: str,
        target_lang: str,
        context: list[str] | None = None,
        max_tokens: int = 512,
        glossary: str = "",
    ) -> AsyncGenerator[str, None]:
        if not self.model:
            raise RuntimeError("尚未配置翻译模型：请在设置里填写模型或从列表选择")
        payload = self._payload(text, target_lang, context or [], max_tokens, glossary)
        try:
            async with self._client() as client:
                async with client.stream(
                    "POST",
                    f"{self.base_url}/chat/completions",
                    headers=self._headers,
                    json=payload,
                ) as resp:
                    if resp.status_code >= 400:
                        body = (await resp.aread()).decode("utf-8", "replace")[:500]
                        if resp.status_code in (401, 403):
                            raise RuntimeError(
                                "API Key 无效或缺失（401）：请在设置里填写模型服务的 Key"
                            )
                        raise RuntimeError(
                            f"翻译服务返回 {resp.status_code}：{body}"
                        )
                    async for line in resp.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if not data or data == "[DONE]":
                            continue
                        try:
                            obj = json.loads(data)
                        except json.JSONDecodeError:
                            continue
                        choices = obj.get("choices") or []
                        if not choices:
                            continue
                        delta = choices[0].get("delta") or {}
                        chunk = delta.get("content") or ""
                        chunk = _strip_think(chunk)
                        if chunk:
                            yield chunk
        except httpx.ConnectError as exc:
            raise RuntimeError(
                f"无法连接翻译服务 {self.base_url}，请确认服务已启动"
            ) from exc
        except httpx.ReadTimeout as exc:
            raise RuntimeError(f"翻译服务响应超时（{self.base_url}）") from exc

    async def correct_text(
        self, text: str, context: list[str] | None = None
    ) -> str:
        """根据上下文校正识别文本；失败或无明显变化时返回原文。"""
        if not self.model or not text:
            return text
        user = ""
        if context:
            user += "上文（仅供参考）：\n" + "\n".join(context[-3:]) + "\n\n"
        user += "待校正文本：\n" + text
        payload = {
            "model": self.model,
            "stream": False,
            "temperature": 0.0,
            "max_tokens": 512,
            "messages": [
                {"role": "system", "content": CORRECT_PROMPT},
                {"role": "user", "content": user},
            ],
        }
        async with self._client(30.0) as client:
            resp = await client.post(
                f"{self.base_url}/chat/completions",
                headers=self._headers,
                json=payload,
            )
            if resp.status_code >= 400:
                return text
            data = resp.json()
        choices = data.get("choices") or []
        if not choices:
            return text
        content = (choices[0].get("message") or {}).get("content") or ""
        fixed = content.strip().strip("“”\"'「」")
        if not fixed:
            return text
        if len(fixed) < len(text) * 0.5 or len(fixed) > len(text) * 1.6:
            return text
        # 中文只接受同音字级别的修正；其它语言暂不做自动改写
        if not _homophone_only_change(text, fixed):
            return text
        return fixed

    async def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        system: str = "",
        temperature: float = 0.3,
    ) -> str:
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        payload = {
            "model": self.model,
            "stream": False,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "messages": messages,
        }
        async with self._client(90.0) as client:
            resp = await client.post(
                f"{self.base_url}/chat/completions",
                headers=self._headers,
                json=payload,
            )
            if resp.status_code >= 400:
                body = (await resp.aread()).decode("utf-8", "replace")[:300]
                raise RuntimeError(f"分析服务返回 {resp.status_code}：{body}")
            data = resp.json()
        choices = data.get("choices") or []
        if not choices:
            return ""
        return ((choices[0].get("message") or {}).get("content") or "").strip()

    async def test_translate(self, target_lang: str = "en") -> dict:
        """跑一句真实翻译，返回首字与总耗时，用于设置页的连接测试。"""
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
            raise RuntimeError("服务已连接，但模型没有返回内容")
        return {
            "ok": True,
            "first_ms": first_ms,
            "total_ms": total_ms,
            "source": TEST_SENTENCE,
            "translation": output,
        }


def _strip_think(chunk: str) -> str:
    """过滤模型偶尔漏出的思考标记和特殊控制字符。"""
    if "<think" in chunk or "<｜end▁of▁thinking｜>" in chunk:
        chunk = _THINK_BLOCK.sub("", chunk)
    return clean_text(_THINK_TAG.sub("", chunk))


def resolve_target(detected_lang: str, target: str) -> str:
    """target 为 auto 时：中文翻英文，其它语言翻简体中文。"""
    if target != "auto":
        return target
    return "en" if (detected_lang or "").startswith("zh") else "zh"
