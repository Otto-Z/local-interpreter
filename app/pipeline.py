"""实时音频分句 + 识别 + 流式翻译的会话处理。"""
from __future__ import annotations

import asyncio
import json
import os
import subprocess
import threading
import time
import wave
from collections import deque

import numpy as np

from .asr import ASREngine
from .config import VAD_MODEL_PATH
from .db import AUDIO_DIR
from .diarize import Diarizer
from .llm import Translator, resolve_target
from .vad import FRAME, load_vad

SR = 16000
PRE_ROLL_FRAMES = 8          # 说话前保留 256ms，避免吞掉首字
TRIGGER_FRAMES = 3           # 连续 3 帧（96ms）判为开始说话
MIN_SEGMENT_S = 0.45         # 太短的片段丢弃
TAIL_KEEP_FRAMES = 6         # 句尾保留约 200ms
PARTIAL_INTERVAL_S = 0.9     # 实时预览的最小间隔
PRE_SILENCE_MS = 150
TIMING = os.environ.get("LI_TIMING") == "1"

def _ts() -> float:
    return time.monotonic()


def _norm_text(value: str) -> str:
    return "".join(ch for ch in value.lower() if ch.isalnum())
          # 停顿到这个程度就先跑一次识别，抢在正式分句前


class Segmenter:
    """把连续音频流切成一句话一句话。"""

    def __init__(self, vad, end_silence_ms: int = 550, max_segment_s: float = 6.0):
        self.vad = vad
        self.end_silence_frames = max(1, int(end_silence_ms / 1000 * SR / FRAME))
        self.max_segment_frames = int(max_segment_s * SR / FRAME)
        self.hard_max_frames = int((max_segment_s + 1.5) * SR / FRAME)
        self.pre_silence_frames = max(1, int(PRE_SILENCE_MS / 1000 * SR / FRAME))
        self._pending = np.zeros(0, dtype=np.float32)
        self.audio_time = 0.0
        self.reset()

    def reset(self) -> None:
        self.vad.reset()
        self._pending = np.zeros(0, dtype=np.float32)
        self.speaking = False
        self.ring: deque[np.ndarray] = deque(maxlen=PRE_ROLL_FRAMES)
        self.buf: list[np.ndarray] = []
        self.trigger = 0
        self.silence_frames = 0
        self.start_time = 0.0
        self.pre_sent = False
        self.probs: list[float] = []

    @property
    def speaking_duration(self) -> float:
        return len(self.buf) * FRAME / SR

    def current_audio(self) -> np.ndarray | None:
        if not self.speaking or not self.buf:
            return None
        return np.concatenate(self.buf)

    def process(self, samples: np.ndarray) -> list[dict]:
        events: list[dict] = []
        if len(samples):
            self._pending = np.concatenate([self._pending, samples.astype(np.float32)])
        while len(self._pending) >= FRAME:
            frame = self._pending[:FRAME]
            self._pending = self._pending[FRAME:]
            frame_start = self.audio_time
            self.audio_time += FRAME / SR
            prob = self.vad.prob(frame)

            if not self.speaking:
                self.ring.append(frame)
                self.trigger = self.trigger + 1 if prob > 0.5 else 0
                if self.trigger >= TRIGGER_FRAMES:
                    self.speaking = True
                    self.buf = list(self.ring)
                    self.ring.clear()
                    self.silence_frames = 0
                    self.start_time = frame_start - (len(self.buf) - 1) * FRAME / SR
                    self.probs = [1.0] * len(self.buf)
                    events.append({"kind": "speech_start", "t": self.start_time})
                continue

            self.buf.append(frame)
            self.probs.append(prob)
            if prob > 0.5:
                self.silence_frames = 0
            else:
                self.silence_frames += 1

            if prob > 0.5:
                self.pre_sent = False

            if (
                not self.pre_sent
                and self.silence_frames >= self.pre_silence_frames
                and self.silence_frames < self.end_silence_frames
            ):
                # 还在等句尾判定时，先把当前音频送去识别
                self.pre_sent = True
                events.append(
                    {"kind": "pre_end", "audio": self.current_audio(), "t": self.audio_time}
                )

            if self.silence_frames >= self.end_silence_frames:
                events.append(self._finalize(keep_tail=True))
            elif len(self.buf) >= self.max_segment_frames:
                # 到最长片段先不硬切：等一个低语音概率的停顿点再切，
                # 最多再等 1.5 秒，避免把词切成两半
                if prob < 0.4:
                    events.append(self._finalize(keep_tail=False))
                elif len(self.buf) >= self.hard_max_frames:
                    events.append(self._finalize(keep_tail=False, smart=True))
        return events

    def _finalize(self, keep_tail: bool, smart: bool = False) -> dict:
        all_frames = list(self.buf)
        all_probs = list(self.probs)
        cut_index = len(all_frames)
        keep_leftover = False
        if keep_tail:
            cut_index = max(
                1, len(all_frames) - max(0, self.silence_frames - TAIL_KEEP_FRAMES)
            )
        elif smart and len(all_probs) == len(all_frames):
            # 强制切分：在最近 3.5 秒内找最后一个语音概率低谷，作为切点
            window = min(len(all_frames), 110)
            tail = all_probs[-window:]
            found = None
            for i in range(len(tail) - 1, -1, -1):
                if tail[i] < 0.4:
                    found = len(all_frames) - window + i + 1
                    break
            if found is not None and found > len(all_frames) // 2:
                cut_index = found
                keep_leftover = True

        frames = all_frames[:cut_index]
        leftover = all_frames[cut_index:] if keep_leftover else []
        leftover_probs = all_probs[cut_index:] if keep_leftover else []

        audio = np.concatenate(frames) if frames else np.zeros(0, dtype=np.float32)
        duration = len(audio) / SR
        t0 = self.start_time
        t1 = t0 + duration

        if leftover:
            # 切点之后还有语音：直接作为下一段的开头，不丢音频
            self.speaking = True
            self.buf = leftover
            self.probs = leftover_probs
            self.start_time = t1
            self.silence_frames = 0
        else:
            self.speaking = False
            self.buf = []
            self.probs = []
            self.start_time = t1
        self.ring.clear()
        self.trigger = 0
        self.silence_frames = 0
        self.pre_sent = False
        return {
            "kind": "final",
            "audio": audio,
            "t0": t0,
            "t1": t1,
            "duration": duration,
            "too_short": duration < MIN_SEGMENT_S,
        }

    def flush(self) -> dict | None:
        if self.speaking and len(self.buf) * FRAME / SR >= MIN_SEGMENT_S:
            return self._finalize(keep_tail=False)
        return None


class Session:
    """一次音频会话：分句 -> 识别 -> 流式翻译 -> 推送事件。"""

    def __init__(self, send, asr: ASREngine, translator: Translator, cfg: dict, store=None, assistant_model=None):
        self._send = send
        self.assistant_model = assistant_model
        try:
            self.course_id = int(cfg.get("course_id") or 0)
        except Exception:
            self.course_id = 0
        self.store = store
        self.save_audio = bool(cfg.get("save_audio", True))
        self.diarizer = None
        if cfg.get("diarization_enabled", True):
            try:
                self.diarizer = Diarizer()
            except Exception:
                self.diarizer = None
        self.db_id: int | None = None
        self._db_segments: dict[int, int] = {}
        if store is not None:
            try:
                self.db_id = store.start_session(
                    audio_source=cfg.get("audio_source", "mic"),
                    source_lang=cfg.get("source_lang", "auto"),
                    target_lang=cfg.get("target_lang", "auto"),
                    llm_backend=cfg.get("llm_backend", "builtin"),
                    llm_model=cfg.get("llm_model", ""),
                    asr_engine=cfg.get("asr_engine", "whisper"),
                    asr_model=cfg.get("asr_model_name", ""),
                    course_id=self.course_id or None,
                )
            except Exception:
                self.db_id = None
        self.asr = asr
        self.translator = translator
        self.cfg = cfg
        self.glossary_text = ""
        if self.store is not None and self.course_id:
            try:
                self.glossary_text = self.store.glossary_prompt(self.course_id, 30)
            except Exception:
                self.glossary_text = ""
        self.source_lang = cfg.get("source_lang", "auto")
        self.target_lang = cfg.get("target_lang", "auto")
        self.partials_enabled = bool(cfg.get("partials", True))
        self.translate_mode = cfg.get("translate_mode", "sentence")
        self.partial_translate = bool(cfg.get("partial_translate", True)) or self.translate_mode == "append"
        self.context_fix = bool(cfg.get("context_fix", True))
        self.assistant_enabled = bool(cfg.get("assistant_enabled", True))
        self.assistant_summary = bool(cfg.get("assistant_summary", True))
        self.assistant_qa = bool(cfg.get("assistant_qa", True))
        self.assistant_interval = float(cfg.get("assistant_interval_s", 60))
        self.only_teacher = bool(cfg.get("assistant_only_teacher", False))
        self.target_speaker = str(cfg.get("assistant_target_speaker") or "").strip()
        self._pending_q: dict[int, dict] = {}
        self._speaker_sid: dict[int, str] = {}
        self._speaker_totals: dict[str, float] = {}
        if cfg.get("assistant_model") and cfg.get("assistant_base_url"):
            self.assistant_llm = Translator(
                cfg["assistant_base_url"],
                cfg["assistant_model"],
                cfg.get("assistant_api_key") or "",
            )
        else:
            self.assistant_llm = None
        self.history: list[dict] = []
        self.last_summary_at = 0.0
        self.history_since_summary = 0
        self.last_summary_text = ""
        self.analysis_queue: asyncio.Queue = asyncio.Queue(maxsize=8)
        self._asked: list[tuple[str, float]] = []
        self.segmenter = Segmenter(
            load_vad(str(VAD_MODEL_PATH)),
            end_silence_ms=int(cfg.get("end_silence_ms", 550)),
            max_segment_s=float(cfg.get("max_segment_s", 6.0)),
        )
        self.queue: asyncio.Queue = asyncio.Queue()
        self.partial_queue: asyncio.Queue = asyncio.Queue(maxsize=1)
        self.fix_queue: asyncio.Queue = asyncio.Queue(maxsize=8)
        self.context: list[str] = []
        self.lang_hint: str | None = None
        self._locked_lang: str | None = None
        self._lock_remaining = 0
        self.segment_id = 0
        self._processing = asyncio.Lock()
        self._last_partial_at = 0.0
        self.translating = 0
        self._trans_sem = asyncio.Semaphore(1)
        self._translation_tasks: set[asyncio.Task] = set()
        self.draft_task: asyncio.Task | None = None
        self.draft_id = 0
        self._pre_task: asyncio.Task | None = None
        self._pre_result: dict | None = None
        self._draft_result: dict | None = None
        self._closed = False
        self._tasks = [
            asyncio.create_task(self._worker()),
            asyncio.create_task(self._partial_worker()),
            asyncio.create_task(self._fix_worker()),
            asyncio.create_task(self._analysis_worker()),
        ]

    async def _send_json(self, obj: dict) -> None:
        try:
            await self._send(obj)
        except Exception:
            pass

    async def feed(self, samples: np.ndarray) -> None:
        for event in self.segmenter.process(samples):
            if event["kind"] == "pre_end":
                self._schedule_pre_asr(event["audio"])
                continue
            if event["kind"] != "final":
                continue
            if event["too_short"]:
                continue
            self.segment_id += 1
            if TIMING:
                print(f"[t] finalize id={self.segment_id} {_ts():.3f}", flush=True)
            await self.queue.put(
                {
                    "id": self.segment_id,
                    "audio": event["audio"],
                    "t0": event["t0"],
                    "t1": event["t1"],
                }
            )
        self._schedule_partial()

    def _schedule_partial(self) -> None:
        if not self.partials_enabled or self._processing.locked():
            return
        if not self.queue.empty():
            return
        # 已经进入句尾停顿或预识别阶段时，把识别资源让给句尾
        if self.segmenter.silence_frames > 0:
            return
        if self._pre_task and not self._pre_task.done():
            return
        now = time.monotonic()
        if now - self._last_partial_at < PARTIAL_INTERVAL_S:
            return
        audio = self.segmenter.current_audio()
        if audio is None or len(audio) < int(1.0 * SR):
            return
        if self.partial_queue.full():
            return
        self._last_partial_at = now
        self.partial_queue.put_nowait((self.segment_id + 1, audio))

    def _schedule_pre_asr(self, audio: np.ndarray | None) -> None:
        if audio is None or len(audio) < int(0.3 * SR):
            return
        if self._pre_task and not self._pre_task.done():
            return
        self._pre_task = asyncio.create_task(self._pre_asr(audio))

    async def _pre_asr(self, audio: np.ndarray) -> None:
        try:
            hint = None if self.source_lang == "auto" else self.source_lang
            if hint is None:
                hint = self.lang_hint
            text, lang = await asyncio.to_thread(self.asr.transcribe, audio, hint)
            self._pre_result = {
                "len": len(audio),
                "text": text,
                "lang": lang,
                "at": time.monotonic(),
            }
        except Exception:
            pass

    async def _partial_worker(self) -> None:
        while True:
            sid, audio = await self.partial_queue.get()
            if self._processing.locked():
                continue
            async with self._processing:
                try:
                    # 预览用已识别出的语言提示，省去每次语言检测的耗时
                    hint = None if self.source_lang == "auto" else self.source_lang
                    if hint is None:
                        hint = self.lang_hint
                    text, detected = await asyncio.to_thread(
                        self.asr.transcribe, audio, hint
                    )
                except Exception:
                    continue
            # 草稿方向用预览识别出的语言；不要写回 lang_hint，
            # 否则短句预览的误判会把正式识别的语言锁定错
            if text and self.segmenter.speaking and sid == self.segment_id + 1:
                await self._send_json({"type": "partial", "id": sid, "text": text})
                if self.partial_translate:
                    self._start_draft(sid, text, detected)

    async def _worker(self) -> None:
        while True:
            item = await self.queue.get()
            try:
                if item is None:
                    return
                await self._handle_segment(item)
            finally:
                self.queue.task_done()

    async def _handle_segment(self, item: dict) -> None:
        sid = item["id"]
        if TIMING:
            print(f"[t] handle_start id={sid} {_ts():.3f}", flush=True)
        if self.draft_task and not self.draft_task.done():
            self.draft_task.cancel()
            self.draft_task = None
        hint: str | None = None
        if self.source_lang != "auto":
            hint = self.source_lang
        elif self._locked_lang and self._lock_remaining > 0:
            # 语言锁定：连续几段沿用上一次检测结果，避免逐句乱跳
            hint = self._locked_lang

        # 若句尾前的预识别已经覆盖了这段音频，直接用它的结果，省掉 ASR 等待
        if self._pre_task and not self._pre_task.done():
            try:
                await asyncio.wait_for(self._pre_task, timeout=1.0)
            except Exception:
                pass
        if TIMING:
            print(f"[t] pre_ready id={sid} {_ts():.3f}", flush=True)
        pre = self._pre_result
        self._pre_result = None
        use_pre = (
            pre is not None
            and abs(len(item["audio"]) - pre["len"]) <= int(0.25 * SR)
            and (time.monotonic() - pre["at"]) < 2.0
        )
        if use_pre:
            text, lang = pre["text"], pre["lang"]
        else:
            async with self._processing:
                try:
                    text, lang = await asyncio.to_thread(
                        self.asr.transcribe,
                        item["audio"],
                        hint,
                        self._asr_prompt(),
                    )
                except Exception as exc:  # noqa: BLE001
                    await self._send_json({"type": "error", "message": f"语音识别失败：{exc}"})
                    return

        if self.source_lang == "auto":
            if hint is None:
                self._locked_lang = lang
                self._lock_remaining = 4
            else:
                self._lock_remaining -= 1

        if not text:
            return
        if lang:
            self.lang_hint = lang
        print(
            f"[asr] dur={item['t1'] - item['t0']:.2f}s lang={lang} text={text}",
            flush=True,
        )
        if TIMING:
            print(f"[t] asr_done id={sid} {_ts():.3f}", flush=True)
        target = resolve_target(lang, self.target_lang)
        await self._send_json(
            {
                "type": "segment",
                "id": sid,
                "text": text,
                "lang": lang,
                "t0": item["t0"],
                "t1": item["t1"],
            }
        )

        self.history.append(
            {"id": sid, "src": text, "dst": "", "t1": item["t1"], "lang": lang}
        )
        self.history = self.history[-40:]
        self.history_since_summary += 1
        if self.store is not None and self.db_id is not None:
            try:
                db_seg = self.store.add_segment(
                    self.db_id,
                    item["t0"],
                    item["t1"],
                    lang,
                    text,
                    target,
                    "",
                )
                self.store.update_segment_confidence(db_seg, "medium")
                self._db_segments[sid] = db_seg
                if self.save_audio:
                    self._save_audio(db_seg, item["audio"])
                if self.diarizer is not None:
                    asyncio.create_task(
                        self._assign_speaker(
                            sid, db_seg, item["audio"], item["t1"] - item["t0"]
                        )
                    )
            except Exception:
                pass

        # 识别与翻译解耦：翻译在独立任务里跑，下一句的识别不用等上一句译完
        ctx_snapshot = list(self.context)
        self.context.append(text)
        self.context = self.context[-2:]

        draft = self._draft_result
        self._draft_result = None
        if (
            draft
            and draft["dst"]
            and _norm_text(draft["src"]) == _norm_text(text)
            and (time.monotonic() - draft["at"]) < 10.0
        ):
            # 草稿与最终识别完全一致：直接复用草稿译文，不再重译
            await self._send_json(
                {"type": "translation_reuse", "id": sid, "text": draft["dst"]}
            )
            await self._send_json(
                {"type": "translation_done", "id": sid, "tgt": target}
            )
            if self.store is not None and self._db_segments.get(sid):
                try:
                    self.store.update_segment_translation(
                        self._db_segments[sid], target, draft["dst"]
                    )
                except Exception:
                    pass
            await self._after_translation(sid, text, draft["dst"], lang)
            if self.context_fix and len(text) >= 4:
                try:
                    self.fix_queue.put_nowait((sid, text, ctx_snapshot))
                except asyncio.QueueFull:
                    pass
            return

        task = asyncio.create_task(
            self._translate_segment(sid, text, lang, target, ctx_snapshot)
        )
        self._translation_tasks.add(task)
        task.add_done_callback(self._translation_tasks.discard)

    def _glossary_for(self, text: str) -> str:
        """只把当前句真正涉及的术语送进翻译提示，避免无关术语拖慢首字。"""
        if not self.glossary_text:
            return ""
        low = text.lower()
        hits = []
        for entry in self.glossary_text.split(";"):
            entry = entry.strip()
            if not entry:
                continue
            term = entry.split("=")[0].strip()
            if term and term.lower() in low:
                hits.append(entry)
        return "; ".join(hits)[:200]

    def _asr_prompt(self) -> str | None:
        # 第一行是术语表（Qwen3 会解析成 hotwords），第二行是上一句上下文（Whisper 用）
        lines: list[str] = []
        if self.glossary_text:
            lines.append("术语：" + self.glossary_text[:180])
        if self.context:
            lines.append(self.context[-1][:120])
        text = "\n".join(lines).strip()
        return text or None

    async def _translate_segment(
        self,
        sid: int,
        text: str,
        lang: str,
        target: str,
        ctx_snapshot: list[str],
    ) -> None:
        async with self._trans_sem:
            self.translating += 1
            first = True
            parts: list[str] = []
            try:
                async for delta in self.translator.translate_stream(
                    text,
                    lang,
                    target,
                    ctx_snapshot,
                    glossary=self._glossary_for(text),
                ):
                    if first and TIMING:
                        print(f"[t] first_delta id={sid} {_ts():.3f}", flush=True)
                    first = False
                    parts.append(delta)
                    await self._send_json(
                        {"type": "translation_delta", "id": sid, "delta": delta}
                    )
                await self._send_json(
                    {"type": "translation_done", "id": sid, "tgt": target}
                )
            except Exception as exc:  # noqa: BLE001
                await self._send_json(
                    {"type": "error", "id": sid, "message": f"翻译失败：{exc}"}
                )
            finally:
                self.translating -= 1

        if self.store is not None and self._db_segments.get(sid):
            try:
                self.store.update_segment_translation(
                    self._db_segments[sid], target, "".join(parts)
                )
            except Exception:
                pass
        await self._after_translation(sid, text, "".join(parts), lang)
        if self.context_fix and len(text) >= 4:
            try:
                self.fix_queue.put_nowait((sid, text, ctx_snapshot))
            except asyncio.QueueFull:
                pass

    # ---------------- 课堂助教 ----------------

    def _analysis_llm(self):
        if self.assistant_llm is not None:
            return self.assistant_llm
        return self.assistant_model



    async def _assign_speaker(
        self, sid: int, db_seg_id: int, audio: np.ndarray, duration: float = 0.0
    ) -> None:
        if self._closed:
            return
        try:
            result = await asyncio.to_thread(self.diarizer.assign, audio)
        except Exception:
            result = None
        if not result:
            self._flush_question(sid)
            return
        _index, label, color = result
        self._speaker_sid[sid] = label
        self._speaker_totals[label] = self._speaker_totals.get(label, 0.0) + max(duration, 0.0)
        speaker_db = None
        if self.store is not None and self.db_id is not None:
            try:
                speaker_db = self.store.ensure_speaker(self.db_id, label, color)
                self.store.assign_speaker(db_seg_id, speaker_db)
            except Exception:
                speaker_db = None
        await self._send_json(
            {
                "type": "speaker",
                "id": sid,
                "speaker_id": speaker_db,
                "label": label,
                "color": color,
            }
        )
        self._flush_question(sid)

    def _save_audio(self, db_seg_id: int, audio: np.ndarray) -> None:
        if self.store is None or self.db_id is None:
            return

        def work() -> None:
            try:
                folder = AUDIO_DIR / str(self.db_id)
                folder.mkdir(parents=True, exist_ok=True)
                try:
                    folder.chmod(0o700)
                except OSError:
                    pass
                wav = folder / f"{db_seg_id}.wav"
                pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
                with wave.open(str(wav), "wb") as fh:
                    fh.setnchannels(1)
                    fh.setsampwidth(2)
                    fh.setframerate(16000)
                    fh.writeframes(pcm.tobytes())
                rel = f"audio/{self.db_id}/{db_seg_id}.wav"
                m4a = folder / f"{db_seg_id}.m4a"
                try:
                    subprocess.run(
                        [
                            "afconvert", "-f", "m4af", "-d", "aac",
                            "-b", "32000", "-q", "127", str(wav), str(m4a),
                        ],
                        check=True,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        timeout=20,
                    )
                    wav.unlink(missing_ok=True)
                    rel = f"audio/{self.db_id}/{db_seg_id}.m4a"
                except Exception:
                    pass
                self.store.update_segment_audio(db_seg_id, rel)
            except Exception:
                pass

        threading.Thread(target=work, daemon=True).start()

    async def _finish_session(self) -> None:
        if self.store is None or self.db_id is None:
            return
        try:
            self.store.finish_session(self.db_id, self.segmenter.audio_time)
        except Exception:
            pass
        await self._tag_session()

    async def _tag_session(self) -> None:
        if self.store is None or self.db_id is None:
            return
        lines = [
            f"- {h['src']} / {h.get('dst') or ''}" for h in self.history[-40:]
        ]
        body = "\n".join(lines)
        fallback_title = (self.history[0]["src"][:24] if self.history else "未命名会话")
        fallback_summary = "".join(h["src"] for h in self.history[:3])[:160]
        if not body:
            self.store.set_tags(self.db_id, fallback_title, fallback_summary, [], "其它", "failed")
            return
        system = (
            "你是课堂记录助手。根据字幕生成一个 JSON，对象包含："
            'title（一句话标题）、topics（3-6 个中文主题标签的数组）、'
            'summary（80-150 字中文摘要）、kind（课堂/会议/访谈/视频/其它）。'
            "只输出 JSON，不要解释。"
        )
        llm = self._analysis_llm()
        if llm is None:
            self.store.set_tags(self.db_id, fallback_title, fallback_summary, [], "其它", "failed")
            return
        try:
            resp = await asyncio.wait_for(
                llm.generate(f"字幕：\n{body}", max_tokens=400, system=system),
                timeout=25,
            )
            import re as _re

            match = _re.search(r"\{.*\}", resp, _re.S)
            data = json.loads(match.group(0)) if match else {}
            title = str(data.get("title") or fallback_title)[:80]
            topics = [str(t).strip() for t in (data.get("topics") or []) if str(t).strip()][:6]
            summary = str(data.get("summary") or fallback_summary)[:400]
            kind = str(data.get("kind") or "课堂")
            self.store.set_tags(self.db_id, title, summary, topics, kind, "ready")
        except Exception:
            self.store.set_tags(
                self.db_id, fallback_title, fallback_summary, [], "其它", "failed"
            )

    async def _after_translation(
        self, sid: int, src: str, dst: str, lang: str
    ) -> None:
        for item in self.history:
            if item["id"] == sid:
                item["dst"] = dst
                break
        level = self._confidence(src, dst)
        await self._send_json(
            {"type": "confidence", "id": sid, "level": level}
        )
        db_seg = self._db_segments.get(sid)
        if db_seg and self.store is not None:
            try:
                self.store.update_segment_confidence(db_seg, level)
            except Exception:
                pass
        if not self.assistant_enabled:
            return
        self._maybe_schedule_summary()
        if self.assistant_qa and self._is_question_candidate(src + " " + dst):
            self._queue_question(sid, src, dst, lang)

    @staticmethod
    def _confidence(src: str, dst: str) -> str:
        text = (src or "").strip()
        if not text or not dst:
            return "low"
        if len(text) < 4:
            return "low"
        if len(set(text)) <= max(2, len(text) // 6):
            return "low"
        import re as _re

        if _re.search(r"(.)\1{4,}", text):
            return "low"
        if len(text) >= 12 and not _re.search(r"[。．.!?！？,，;；:：]", text):
            return "medium"
        return "high"

    def _maybe_schedule_summary(self) -> None:
        if not self.assistant_summary:
            return
        now = time.monotonic()
        if self.history_since_summary < 3:
            return
        if now - self.last_summary_at < self.assistant_interval:
            return
        if self.analysis_queue.full():
            return
        self.last_summary_at = now
        self.history_since_summary = 0
        snapshot = [dict(x) for x in self.history[-20:]]
        self.analysis_queue.put_nowait(
            ("summary", snapshot, self.last_summary_text)
        )

    @staticmethod
    def _is_question_candidate(text: str) -> bool:
        import re

        t = text.strip()
        if "?" in t or "？" in t:
            return True
        if t.endswith(("吗", "呢", "么")):
            return True
        zh = (
            "为什么", "怎么", "怎样", "如何", "哪", "谁", "多少", "是否",
            "能不能", "可不可以", "请问", "是不是", "多长时间", "什么时候",
        )
        if any(k in t for k in zh):
            return True
        low = t.lower()
        pattern = (
            r"^\s*(so |and |but )?(what|why|how|when|where|who|which|"
            r"can|could|should|do|does|did|is|are|will|would|may|might)\b"
        )
        return re.match(pattern, low) is not None

    def _queue_question(self, sid: int, src: str, dst: str, lang: str) -> None:
        self._pending_q[sid] = {"src": src, "dst": dst, "lang": lang}
        self._flush_question(sid)

    def _flush_question(self, sid: int) -> None:
        job = self._pending_q.get(sid)
        if not job:
            return
        if self.diarizer is not None and sid not in self._speaker_sid:
            if not job.get("_waiting"):
                job["_waiting"] = True
                asyncio.create_task(self._flush_question_later(sid))
            return
        self._pending_q.pop(sid, None)
        label = self._speaker_sid.get(sid)
        if self.target_speaker and label != self.target_speaker:
            return
        if self.only_teacher:
            dominant = self._dominant_speaker()
            if not label or (dominant and label != dominant):
                return
        self._maybe_schedule_question(
            sid, job["src"], job["dst"], job["lang"], label or ""
        )

    async def _flush_question_later(self, sid: int) -> None:
        await asyncio.sleep(2.0)
        if self._closed:
            return
        self._flush_question(sid)

    def _dominant_speaker(self) -> str:
        if not self._speaker_totals:
            return ""
        return max(self._speaker_totals.items(), key=lambda kv: kv[1])[0]

    async def bookmark(self, note: str = "") -> None:
        if self.store is None or self.db_id is None:
            return
        sid = self.history[-1]["id"] if self.history else None
        db_seg = self._db_segments.get(sid) if sid else None
        try:
            bookmark_id = self.store.add_bookmark(
                self.db_id, db_seg, str(note or "")
            )
        except Exception:
            return
        await self._send_json(
            {
                "type": "bookmark_saved",
                "id": bookmark_id,
                "segment_id": db_seg,
                "note": str(note or ""),
            }
        )

    async def recap(self, minutes: int = 5) -> None:
        if self.analysis_queue.full():
            return
        self.analysis_queue.put_nowait(("recap", minutes))

    def _maybe_schedule_question(
        self, sid: int, src: str, dst: str, lang: str, speaker: str = ""
    ) -> None:
        now = time.monotonic()
        self._asked = [(q, t) for q, t in self._asked if now - t < 120]
        key = _norm_text(src)
        if not key or any(q == key for q, _ in self._asked):
            return
        if self.analysis_queue.full():
            return
        self._asked.append((key, now))
        self.analysis_queue.put_nowait(
            (
                "question",
                {
                    "sid": sid,
                    "src": src,
                    "dst": dst,
                    "lang": lang,
                    "history": [dict(x) for x in self.history[-8:]],
                    "speaker": speaker,
                },
            )
        )

    async def ask(self, text: str) -> None:
        if not text.strip() or self.analysis_queue.full():
            return
        self.analysis_queue.put_nowait(
            (
                "question",
                {
                    "sid": 0,
                    "src": text.strip(),
                    "dst": "",
                    "lang": self.lang_hint or "",
                    "history": [dict(x) for x in self.history[-8:]],
                    "manual": True,
                },
            )
        )

    async def _analysis_worker(self) -> None:
        while True:
            job = await self.analysis_queue.get()
            try:
                # 分析优先级最低：尽量等翻译和说话都停下来
                for _ in range(12):
                    if (
                        self.segmenter.speaking
                        or self.translating
                        or not self.queue.empty()
                    ):
                        await asyncio.sleep(0.3)
                    else:
                        break
                if (
                    self.assistant_llm is None
                    and self.assistant_model is not None
                    and self.assistant_model.status == "loading"
                ):
                    for _ in range(40):
                        if self.assistant_model.status != "loading":
                            break
                        await asyncio.sleep(0.5)
                if job[0] == "summary":
                    await self._run_summary(job[1], job[2])
                elif job[0] == "recap":
                    await self._run_recap(job[1])
                else:
                    await self._run_question(job[1])
            except Exception:  # noqa: BLE001
                pass
            finally:
                self.analysis_queue.task_done()

    async def _run_summary(self, snapshot: list[dict], previous: str) -> None:
        lines = [f"- 原文：{h['src']}\n  译文：{h.get('dst') or ''}" for h in snapshot]
        body = "\n".join(lines)
        prompt = f"课堂字幕（按时间顺序）：\n{body}"
        if previous:
            prompt = f"上一版要点：\n{previous}\n\n最近的字幕：\n{body}"
        system = (
            "你是课堂助教。请用中文输出 3–5 条课堂要点，每行以「- 」开头；"
            "最后一行以「主题：」开头概括当前话题。只输出这些内容，不要解释。"
        )
        llm = self._analysis_llm()
        if llm is None:
            return
        text = await llm.generate(prompt, max_tokens=260, system=system)
        if not text:
            return
        self.last_summary_text = text
        if self.store is not None and self.db_id is not None:
            try:
                self.store.add_summary(self.db_id, text)
            except Exception:
                pass
        await self._send_json(
            {"type": "summary", "text": text, "at": time.time()}
        )

    async def _run_recap(self, minutes: int) -> None:
        cutoff = self.segmenter.audio_time - minutes * 60
        items = [h for h in self.history if h.get("t1", 0) >= cutoff][-12:]
        if not items:
            items = self.history[-10:]
        if not items:
            return
        body = "\n".join(f"- {h['src']} / {h.get('dst') or ''}" for h in items)
        system = (
            "你是课堂助教。用更容易理解的中文总结最近几分钟的课堂内容。"
            "输出 3–5 条要点，每行以「- 」开头；如果出现关键概念，用一句大白话解释。"
            "不要提「字幕」「记录」这些词。"
        )
        llm = self._analysis_llm()
        if llm is None:
            return
        try:
            text = await asyncio.wait_for(
                llm.generate(f"课堂内容：\n{body}", max_tokens=320, system=system),
                timeout=25,
            )
        except Exception:
            return
        if text:
            await self._send_json(
                {"type": "recap", "minutes": minutes, "text": text, "at": time.time()}
            )

    async def _run_question(self, job: dict) -> None:
        ctx = "\n".join(
            f"- {h['src']} / {h.get('dst') or ''}" for h in job["history"]
        )
        course = []
        if self.store is not None:
            try:
                course = self.store.search_materials(
                    job["src"], 4, self.course_id or None
                )
            except Exception:
                course = []
        course_text = "\n".join(
            f"[第{c.get('page', '?')}页] {c.get('text', '')}" for c in course
        )
        course_ctx = ""
        if self.store is not None and self.course_id:
            try:
                course_ctx = self.store.course_context(self.course_id)
            except Exception:
                course_ctx = ""
        prompt = (
            f"课堂记录：\n{ctx or '（暂无）'}\n\n"
            f"本课程往次课次摘要：\n{course_ctx or '（无）'}\n\n"
            f"课件片段：\n{course_text or '（无）'}\n\n"
            f"问题：{job['src']}"
        )
        system = (
            "你是课堂助教。根据课堂记录和课件片段回答学生的问题。"
            "优先使用课堂记录；课堂记录没有提到时查课件片段，并标注「第几页」；"
            "两者都没有时再用常识回答。"
            "第一行只写来源（课堂内容/课件/模型知识/综合），"
            "换行后给出 3–5 句简洁中文答案。"
        )
        llm = self._analysis_llm()
        if llm is None:
            return
        if not job.get("manual"):
            try:
                check = await asyncio.wait_for(
                    llm.generate(
                        f"判断下面这句话是不是课堂上需要回答的问题。只输出 YES 或 NO。\n{job['src']}",
                        max_tokens=4,
                        system="你只输出 YES 或 NO。",
                    ),
                    timeout=10,
                )
                if not check.strip().upper().startswith("YES"):
                    return
            except Exception:
                pass
        try:
            resp = await asyncio.wait_for(
                llm.generate(prompt, max_tokens=360, system=system), timeout=25
            )
        except asyncio.TimeoutError:
            print("[assistant] q timeout", flush=True)
            return
        if not resp:
            return
        lines = resp.splitlines()
        source = "综合"
        first = lines[0].strip() if lines else ""
        if len(first) <= 24 and any(
            k in first for k in ("来源", "课堂", "模型", "综合", "记录")
        ):
            if "课堂" in first or "记录" in first:
                source = "课堂内容"
            elif "课件" in first:
                source = "课件"
            elif "模型" in first:
                source = "模型知识"
            else:
                source = "综合"
            lines = lines[1:]
        answer = "\n".join(line for line in lines if line.strip()).strip()
        if not answer:
            return
        if answer.strip().upper() == "SKIP":
            return
        if source == "课件" and course and "第" not in answer:
            page = course[0].get("page")
            if page:
                answer = answer.rstrip() + f"（第{page}页）"
        alt = ""
        cjk = sum(1 for ch in answer if "一" <= ch <= "鿿")
        if cjk > len(answer) * 0.2:
            try:
                parts: list[str] = []
                async for delta in (self.assistant_llm or self.translator).translate_stream(
                    answer, "zh", "en", []
                ):
                    parts.append(delta)
                alt = "".join(parts).strip()
            except Exception:
                alt = ""
        if self.store is not None and self.db_id is not None:
            try:
                self.store.add_question(
                    self.db_id,
                    self._db_segments.get(job.get("sid", 0), 0),
                    question=job["src"],
                    question_tgt=job.get("dst") or "",
                    answer=answer,
                    answer_alt=alt,
                    source=source,
                )
            except Exception:
                pass
        await self._send_json(
            {
                "type": "question",
                "id": job.get("sid", 0),
                "question": job["src"],
                "question_tgt": job.get("dst") or "",
                "answer": answer,
                "answer_alt": alt,
                "source": source,
                "speaker": job.get("speaker", ""),
                "manual": bool(job.get("manual")),
                "at": time.time(),
            }
        )

    def _start_draft(self, sid: int, text: str, lang: str = "") -> None:
        """边说边翻译：用当前预览文本先翻一版草稿。"""
        if len(text) < 6:
            return
        # 正式译文优先：正在生成正式译文时不发新的草稿请求
        if self.translating:
            return
        # 上一版草稿还在生成时不打断它，等下一轮预览再更新
        if self.draft_task and not self.draft_task.done():
            return
        self.draft_id += 1
        self.draft_task = asyncio.create_task(
            self._draft_worker(self.draft_id, sid, text, lang)
        )

    async def _draft_worker(
        self, draft_id: int, sid: int, text: str, lang: str = ""
    ) -> None:
        try:
            target = resolve_target(lang or self.lang_hint or "", self.target_lang)
            parts: list[str] = []
            first = True
            async for delta in self.translator.translate_stream(
                text, "", target, [], max_tokens=160
            ):
                if draft_id != self.draft_id:
                    return
                if first:
                    # 新草稿真正出字时才替换旧草稿，避免每个预览都清空闪烁
                    await self._send_json({"type": "draft_reset", "id": sid})
                    first = False
                parts.append(delta)
                await self._send_json(
                    {"type": "draft_delta", "id": sid, "delta": delta}
                )
            if draft_id == self.draft_id:
                self._draft_result = {
                    "src": text,
                    "dst": "".join(parts).strip(),
                    "at": time.monotonic(),
                }
                await self._send_json({"type": "draft_done", "id": sid})
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            pass

    async def _fix_worker(self) -> None:
        """用上下文对识别文本做二次校正；失败时静默保留原文。"""
        while True:
            sid, raw, ctx = await self.fix_queue.get()
            # 校正优先级最低：等翻译和说话都停下来再做；一直忙就放弃这次校正
            idle = False
            for _ in range(10):
                if self.translating or self.segmenter.speaking or not self.queue.empty():
                    await asyncio.sleep(0.4)
                else:
                    idle = True
                    break
            if not idle:
                self.fix_queue.task_done()
                continue
            try:
                fixed = await self.translator.correct_text(raw, ctx)
            except Exception:  # noqa: BLE001
                fixed = raw
            if fixed and fixed != raw:
                await self._send_json(
                    {
                        "type": "correction",
                        "id": sid,
                        "text": fixed,
                        "original": raw,
                    }
                )
            self.fix_queue.task_done()

    async def pause(self) -> None:
        """暂停采集：把当前半句收尾，但会话、课次和 WebSocket 都保留。"""
        if self._closed:
            return
        if self.draft_task and not self.draft_task.done():
            self.draft_task.cancel()
            self.draft_task = None
        event = self.segmenter.flush()
        if event and not event["too_short"]:
            self.segment_id += 1
            await self.queue.put(
                {
                    "id": self.segment_id,
                    "audio": event["audio"],
                    "t0": event["t0"],
                    "t1": event["t1"],
                }
            )

    async def stop(self) -> None:
        self._closed = True
        if self.draft_task and not self.draft_task.done():
            self.draft_task.cancel()
        event = self.segmenter.flush()
        if event and not event["too_short"]:
            self.segment_id += 1
            await self.queue.put(
                {
                    "id": self.segment_id,
                    "audio": event["audio"],
                    "t0": event["t0"],
                    "t1": event["t1"],
                }
            )
        await self.queue.put(None)
        # 等剩余片段识别完，再等翻译任务收尾
        try:
            await asyncio.wait_for(self.queue.join(), timeout=20)
        except asyncio.TimeoutError:
            pass
        pending = [t for t in self._translation_tasks if not t.done()]
        if pending:
            try:
                await asyncio.wait_for(asyncio.gather(*pending), timeout=20)
            except Exception:
                pass
        await self._finish_session()
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
