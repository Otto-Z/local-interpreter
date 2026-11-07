"""本地同传：FastAPI 服务入口。

启动方式（在项目根目录）：
    .venv/bin/python -m app.server
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import threading
import time
import uuid
import webbrowser
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
import uvicorn
from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .asr import ASREngine
from .asr_qwen3 import Qwen3ASREngine
from .asr_sensevoice import SenseVoiceEngine
from .db import DATA_DIR, Store
from .materials import extract_chunks
from .config import (
    LANG_NAMES,
    TARGET_LANG_CHOICES,
    SOURCE_LANG_CHOICES,
    VAD_MODEL_PATH,
    load_settings,
    save_settings,
)
from .llm import Translator
from .llm_local import ASSISTANT_DOWNLOAD_DIR, LocalAssistant, LocalTranslator
from .pipeline import Session

STATIC_DIR = Path(__file__).resolve().parent / "static"
SETTING_KEYS = {
    "asr_model",
    "source_lang",
    "target_lang",
    "end_silence_ms",
    "max_segment_s",
    "translate_mode",
    "partials",
    "show_source",
}

# 常见本地 OpenAI 兼容服务的端口
BACKEND_PROBES = [
    (8000, "oMLX"),
    (1234, "LM Studio"),
    (8080, "vLLM"),
    (8001, "本地服务"),
]


async def _probe_models(
    base_url: str, api_key: str = "", timeout: float = 0.7
) -> tuple[str, list[str]]:
    return await Translator(base_url, "", api_key).probe(timeout)


def build_asr(settings: dict):
    engine = settings.get("asr_engine", "whisper")
    if engine == "sensevoice":
        return SenseVoiceEngine()
    if engine == "qwen3":
        model = settings.get("asr_qwen_model") or "mlx-community/Qwen3-ASR-0.6B-8bit"
        return Qwen3ASREngine(model)
    return ASREngine(settings["asr_model"])


def _preload_ml_stack() -> None:
    """在主线程里先初始化 transformers / mlx_audio 的懒加载，避免两个
    模型加载线程同时导入时触发 partially initialized module 错误。"""
    try:
        import transformers

        _ = transformers.AutoTokenizer
        _ = transformers.AutoConfig
        _ = transformers.AutoFeatureExtractor
        _ = transformers.PreTrainedModel
    except Exception:
        pass
    try:
        import mlx_audio.stt.utils  # noqa: F401
    except Exception:
        pass


def _analysis_llm():
    """助教分析用的模型：配置了外部服务就用外部，否则用内置 4B。"""
    settings = app.state.settings
    if settings.get("assistant_base_url") and settings.get("assistant_model"):
        return Translator(
            settings.get("assistant_base_url"),
            settings.get("assistant_model"),
            settings.get("assistant_api_key") or "",
        )
    return app.state.assistant_model


async def _assistant_text(
    prompt: str, max_tokens: int = 500, system: str = "", timeout: float = 60.0
) -> str:
    llm = _analysis_llm()
    if llm is None:
        return ""
    try:
        return await asyncio.wait_for(
            llm.generate(prompt, max_tokens=max_tokens, system=system), timeout=timeout
        )
    except Exception:
        return ""


def _dir_size(path: Path) -> int:
    total = 0
    try:
        for root, _dirs, files in os.walk(path):
            for name in files:
                try:
                    total += os.path.getsize(os.path.join(root, name))
                except OSError:
                    pass
    except Exception:
        pass
    return total


def _parse_json_block(text: str):
    if not text:
        return None
    match = re.search(r"\[.*\]|\{.*\}", text, re.S)
    if not match:
        return None
    try:
        return json.loads(match.group(0))
    except Exception:
        return None


@asynccontextmanager
async def lifespan(app: FastAPI):
    _preload_ml_stack()
    settings = load_settings()
    app.state.settings = settings
    app.state.store = Store()
    try:
        days = int(settings.get("retention_days", 0) or 0)
        if days > 0:
            app.state.store.purge_older_than(days)
    except Exception:
        pass
    app.state.asr = build_asr(settings)
    app.state.asr.load_async()
    app.state.local_llm = None
    if settings.get("llm_backend", "builtin") == "builtin":
        app.state.local_llm = LocalTranslator()
        app.state.local_llm.load_async()
    app.state.assistant_model = None
    if settings.get("assistant_enabled", True) and not (
        settings.get("assistant_base_url") and settings.get("assistant_model")
    ):
        app.state.assistant_model = LocalAssistant()
        app.state.assistant_model.load_async()
    app.state.active_sessions = 0
    app.state.downloads = {}
    yield


app = FastAPI(title="本地同传", lifespan=lifespan)


@app.middleware("http")
async def no_cache_ui(request, call_next):
    """界面资源禁止缓存，避免 WKWebView 一直用旧脚本/旧样式。"""
    response = await call_next(request)
    path = request.url.path
    if path == "/" or path.startswith("/static/"):
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


@app.get("/")
async def index():
    return FileResponse(str(STATIC_DIR / "index.html"))


@app.get("/api/settings")
async def get_settings():
    return {
        "settings": app.state.settings,
        "options": {
            "source_langs": SOURCE_LANG_CHOICES,
            "target_langs": TARGET_LANG_CHOICES,
        },
    }


@app.post("/api/settings")
async def post_settings(patch: dict):
    old = dict(app.state.settings)
    app.state.settings = save_settings(patch)
    if (
        app.state.settings["asr_model"] != old["asr_model"]
        or app.state.settings.get("asr_engine") != old.get("asr_engine")
        or app.state.settings.get("asr_qwen_model") != old.get("asr_qwen_model")
    ):
        app.state.asr = build_asr(app.state.settings)
        app.state.asr.load_async()

    backend = app.state.settings.get("llm_backend", "builtin")
    if backend == "builtin":
        if app.state.local_llm is None or old.get("llm_backend") != "builtin":
            app.state.local_llm = LocalTranslator()
            app.state.local_llm.load_async()
    else:
        app.state.local_llm = None

    use_api_assistant = bool(
        app.state.settings.get("assistant_base_url")
        and app.state.settings.get("assistant_model")
    )
    if (
        app.state.settings.get("assistant_enabled", True)
        and not use_api_assistant
        and app.state.assistant_model is None
    ):
        app.state.assistant_model = LocalAssistant()
        app.state.assistant_model.load_async()
    if use_api_assistant or not app.state.settings.get("assistant_enabled", True):
        app.state.assistant_model = None
    return {"settings": app.state.settings}


@app.get("/api/health")
async def health():
    settings = app.state.settings
    backend = settings.get("llm_backend", "builtin")
    model = (settings.get("llm_model") or "").strip()
    base_url = settings.get("llm_base_url") or ""
    if backend == "builtin":
        local = app.state.local_llm
        status = local.status if local else "idle"
        llm = {
            "ok": status == "ready",
            "configured": True,
            "backend": "builtin",
            "model": local.model_name if local else "Hy-MT2-1.8B-4bit（内置）",
            "base_url": "",
            "error": None if status == "ready" else (
                local.error if (local and status == "error") else "内置模型加载中"
            ),
        }
    elif not model:
        llm = {
            "ok": False,
            "configured": False,
            "backend": backend,
            "model": "",
            "base_url": base_url,
            "error": "未配置翻译模型",
        }
    else:
        translator = Translator(base_url, model, settings.get("llm_api_key") or "")
        status, _models = await translator.probe()
        error = None
        if status == "auth":
            error = "需要 API Key"
        elif status == "down":
            error = "服务未连接"
        elif status == "error":
            error = "服务异常"
        llm = {
            "ok": status == "ok",
            "configured": True,
            "backend": "api",
            "model": model,
            "base_url": base_url,
            "error": error,
        }
    return {
        "asr": {
            "status": app.state.asr.status,
            "model": app.state.asr.model_name,
            "engine": settings.get("asr_engine", "whisper"),
            "error": app.state.asr.error,
        },
        "llm": llm,
    }


@app.get("/api/backends/detect")
async def detect_backends(api_key: str = ""):
    """扫描本机常见端口上的 OpenAI 兼容服务。"""

    async def one(port: int, name: str) -> dict | None:
        base_url = f"http://127.0.0.1:{port}/v1"
        status, models = await _probe_models(base_url, api_key)
        if status == "auth":
            return {
                "name": name,
                "base_url": base_url,
                "models": [],
                "requires_key": True,
            }
        if status != "ok":
            return None
        return {"name": name, "base_url": base_url, "models": models}

    results = await asyncio.gather(
        *(one(port, name) for port, name in BACKEND_PROBES)
    )
    services = [item for item in results if item]
    return {"services": services}


@app.post("/api/backends/models")
async def backend_models(body: dict):
    base_url = str(body.get("base_url") or "").strip()
    api_key = str(body.get("api_key") or "")
    if not base_url:
        return {"ok": False, "models": [], "error": "请先填写接口地址"}
    try:
        models = await Translator(base_url, "", api_key).list_models(timeout=5.0)
        return {"ok": True, "models": models}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "models": [], "error": f"读取模型列表失败：{exc}"}


@app.get("/api/backends/omlx-key")
async def omlx_key():
    """从 oMLX 的本地配置里读取 API Key（用户主动点击时调用）。"""
    path = Path.home() / ".omlx" / "settings.json"
    key = ""
    try:
        data = json.loads(path.read_text("utf-8"))
        key = str((data.get("server") or {}).get("api_key") or "")
    except Exception:
        key = ""
    return {"ok": bool(key), "api_key": key}


@app.post("/api/backends/test")
async def backend_test(body: dict):
    base_url = str(body.get("base_url") or "").strip()
    model = str(body.get("model") or "").strip()
    api_key = str(body.get("api_key") or "")
    if not base_url:
        return {"ok": False, "error": "请先填写接口地址"}
    if not model:
        return {"ok": False, "error": "请先填写或选择模型"}
    translator = Translator(base_url, model, api_key)
    try:
        models = await translator.list_models(timeout=4.0)
    except Exception:
        models = []
    try:
        result = await translator.test_translate("en")
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc), "models": models}
    return {"ok": True, "models": models, **result}


@app.get("/api/courses")
async def api_courses():
    return {"courses": app.state.store.list_courses()}


@app.post("/api/courses")
async def api_create_course(body: dict):
    name = str(body.get("name") or "").strip()
    if not name:
        return JSONResponse({"error": "name required"}, status_code=400)
    course_id = app.state.store.create_course(name, str(body.get("kind") or "课堂"))
    return {"ok": True, "id": course_id}


@app.get("/api/courses/{course_id}")
async def api_course(course_id: int):
    data = app.state.store.get_course(course_id)
    if not data:
        return JSONResponse({"error": "not found"}, status_code=404)
    return data


@app.patch("/api/courses/{course_id}")
async def api_patch_course(course_id: int, body: dict):
    app.state.store.update_course(course_id, **body)
    return {"ok": True}


@app.delete("/api/courses/{course_id}")
async def api_delete_course(course_id: int):
    app.state.store.delete_course(course_id)
    return {"ok": True}


@app.get("/api/courses/{course_id}/glossary")
async def api_glossary(course_id: int):
    return {"items": app.state.store.list_glossary(course_id)}


@app.post("/api/courses/{course_id}/glossary")
async def api_add_glossary(course_id: int, body: dict):
    if not app.state.store.course_exists(course_id):
        return JSONResponse({"ok": False, "error": "课程不存在"}, status_code=404)
    items = body.get("items")
    if items is None:
        items = [body]
    elif isinstance(items, dict):
        items = [items]
    elif not isinstance(items, list):
        items = []
    added = app.state.store.add_glossary(course_id, items)
    return {"ok": True, "added": added, "items": app.state.store.list_glossary(course_id)}


@app.delete("/api/glossary/{item_id}")
async def api_delete_glossary(item_id: int):
    app.state.store.delete_glossary(item_id)
    return {"ok": True}


@app.post("/api/courses/{course_id}/glossary/generate")
async def api_generate_glossary(course_id: int):
    store = app.state.store
    course = store.get_course(course_id)
    if not course:
        return JSONResponse({"error": "课程不存在"}, status_code=404)
    target = app.state.settings.get("target_lang", "auto")
    target_name = "英文" if target == "auto" else LANG_NAMES.get(target, target)
    material = store.course_material_text(course_id)
    transcript = store.course_transcript(course_id, 120)
    existing = store.list_glossary(course_id)
    existing_terms = ", ".join(item["term"] for item in existing[:40])
    prompt = (
        f"课程名称：{course.get('name') or ''}\n"
        f"已有术语：{existing_terms or '无'}\n\n"
        f"课程资料：\n{material or '（无）'}\n\n"
        f"课堂记录：\n{transcript or '（无）'}"
    )
    system = (
        "你是课程术语助手。请从资料和课堂记录中提取 8–15 个值得记住的专业术语，"
        f"给出简短的{target_name}译名（没有通用译名就意译）和一句中文解释。"
        '输出 JSON 数组，每项形如 {"term":"...","translation":"...","note":"...","source":"资料/课堂"}。'
        "只输出 JSON，不要解释。"
    )
    text = await _assistant_text(prompt, max_tokens=700, system=system, timeout=90)
    data = _parse_json_block(text)
    if isinstance(data, dict):
        data = data.get("items") or data.get("terms") or []
    if not isinstance(data, list):
        return JSONResponse({"ok": False, "error": "助教模型未就绪或没有返回术语"}, status_code=503)
    items = []
    for row in data:
        if not isinstance(row, dict):
            continue
        term = str(row.get("term") or "").strip()
        if not term:
            continue
        items.append(
            {
                "term": term,
                "translation": str(row.get("translation") or "").strip(),
                "note": str(row.get("note") or "").strip(),
                "source": str(row.get("source") or "").strip(),
            }
        )
    added = store.add_glossary(course_id, items)
    return {"ok": True, "added": added, "items": store.list_glossary(course_id)}


@app.post("/api/courses/{course_id}/ask")
async def api_course_ask(course_id: int, body: dict):
    question = str(body.get("question") or "").strip()[:2000]
    if not question:
        return JSONResponse({"error": "question required"}, status_code=400)
    store = app.state.store
    course = store.get_course(course_id)
    if not course:
        return JSONResponse({"error": "课程不存在"}, status_code=404)
    materials = await asyncio.to_thread(
        store.search_materials, question, 4, course_id
    )
    segments = await asyncio.to_thread(
        store.search_course_segments, course_id, question, 5
    )
    lessons = store.course_context(course_id, 8)
    glossary = store.glossary_prompt(course_id, 20)
    history = body.get("history") or []
    if not isinstance(history, list):
        history = []
    history_text = ""
    for turn in history[-4:]:
        if not isinstance(turn, dict):
            continue
        q = str(turn.get("q") or "").strip()
        a = str(turn.get("a") or "").strip()
        if q and a:
            history_text += f"学生：{q[:200]}\n助教：{a[:400]}\n"
    mat_text = "\n".join(
        f"[{m.get('name')} 第{m.get('page')}页] {(m.get('text') or '')[:500]}"
        for m in materials
    )
    seg_text = "\n".join(
        f"[{(s.get('started_at') or '')[:10]} {(s.get('title') or '')}] "
        f"{(s.get('src_text') or '')} / {(s.get('tgt_text') or '')}"
        for s in segments
    )
    prompt = (
        f"课程：{course.get('name')}\n课程笔记：{(course.get('notes') or '')[:800]}\n"
        f"往次课次摘要：\n{lessons or '（无）'}\n\n"
        f"术语表：{glossary or '（无）'}\n\n"
        f"相关资料：\n{mat_text or '（无）'}\n\n"
        f"相关课堂原话：\n{seg_text or '（无）'}\n\n"
        f"{('之前的对话：\n' + history_text + '\n') if history_text else ''}"
        f"问题：{question}"
    )
    system = (
        "你是这门课的学习助教。优先依据课程资料和课堂原话回答；"
        "资料没有提及时再用通用知识，并明确说明。"
        "先给出直接答案，再补充 1–2 句解释或例子。不要编造页码。"
    )
    answer = await _assistant_text(prompt, max_tokens=520, system=system, timeout=90)
    if not answer:
        return JSONResponse(
            {"ok": False, "error": "助教模型未就绪，请在设置里配置分析模型"},
            status_code=503,
        )
    sources = []
    for item in materials:
        sources.append(f"资料·{item.get('name')} 第{item.get('page')}页")
    for item in segments:
        title = item.get("title") or "课次"
        date = (item.get("started_at") or "")[:10]
        sources.append(f"课堂·{date} {title}")
    return {"ok": True, "answer": answer, "sources": sources[:6]}


@app.post("/api/courses/{course_id}/outline")
async def api_course_outline(course_id: int):
    store = app.state.store
    course = store.get_course(course_id)
    if not course:
        return JSONResponse({"error": "课程不存在"}, status_code=404)
    material = store.course_material_text(course_id, 5000)
    lessons = store.course_context(course_id, 6)
    prompt = (
        f"课程：{course.get('name')}\n"
        f"资料：\n{material or '（无）'}\n\n"
        f"往次课次：\n{lessons or '（无）'}"
    )
    system = (
        "你是课程规划助手。根据资料生成一份课前预习提纲："
        "先列出 4–6 个本节要点（每行以「- 」开头），"
        "再列出 3 个听课时值得留意的问题（每行以「? 」开头）。"
        "只输出提纲，不要解释。"
    )
    text = await _assistant_text(prompt, max_tokens=520, system=system, timeout=90)
    if not text:
        return JSONResponse({"ok": False, "error": "助教模型未就绪"}, status_code=503)
    store.update_course(course_id, outline=text)
    return {"ok": True, "outline": text}


@app.get("/api/courses/{course_id}/export")
async def api_course_export(course_id: int):
    store = app.state.store
    course = store.get_course(course_id)
    if not course:
        return JSONResponse({"error": "课程不存在"}, status_code=404)
    lines = [f"# {course.get('name') or '课程'}", ""]
    if course.get("kind"):
        lines.append(f"类型：{course['kind']}")
    if course.get("notes"):
        lines += ["", "## 课程笔记", "", course["notes"]]
    if course.get("outline"):
        lines += ["", "## 课前提纲", "", course["outline"]]
    glossary = store.list_glossary(course_id)
    if glossary:
        lines += ["", "## 术语表", ""]
        for item in glossary:
            note = f"：{item['note']}" if item.get("note") else ""
            lines.append(f"- **{item['term']}** — {item.get('translation') or ''}{note}")
    materials = course.get("materials") or []
    if materials:
        lines += ["", "## 课程资料", ""]
        for item in materials:
            lines.append(f"- {item.get('name')}（{item.get('pages') or 0} 页）")
    sessions = course.get("sessions") or []
    lines += ["", "## 课次", ""]
    for item in sessions:
        date = (item.get("started_at") or "")[:16].replace("T", " ")
        title = item.get("title") or "未命名课次"
        lines.append(f"### {title}")
        lines.append(f"- 时间：{date}")
        if item.get("duration_s"):
            seconds = int(item["duration_s"])
            if seconds < 60:
                lines.append(f"- 时长：{seconds} 秒")
            else:
                lines.append(f"- 时长：{seconds // 60} 分 {seconds % 60} 秒")
        if item.get("summary"):
            lines.append(f"- 摘要：{item['summary']}")
        if item.get("user_note"):
            lines.append(f"- 笔记：{item['user_note']}")
        questions = store.list_questions(item["id"])
        if questions:
            lines += ["", "**问答**", ""]
            for qa in questions:
                lines.append(f"- 问：{qa.get('question')}")
                lines.append(f"  答：{qa.get('answer')}")
        bookmarks = store.list_bookmarks(item["id"])
        if bookmarks:
            lines += ["", "**书签**", ""]
            for bm in bookmarks:
                stamp = int(bm.get("t0") or 0)
                text = bm.get("note") or bm.get("src_text") or ""
                lines.append(f"- {stamp // 60:02d}:{stamp % 60:02d} {text}")
        try:
            quiz = json.loads(item.get("quiz") or "[]")
        except Exception:
            quiz = []
        if quiz:
            lines += ["", "**复习题**", ""]
            for card in quiz:
                lines.append(f"- 问：{card.get('q')}")
                lines.append(f"  答：{card.get('a')}")
        lines.append("")
    markdown = "\n".join(lines).strip() + "\n"
    return JSONResponse(
        {
            "ok": True,
            "filename": f"{(course.get('name') or '课程')}.md",
            "markdown": markdown,
            "json": course,
        }
    )


@app.get("/api/courses/{course_id}/search")
async def api_course_search(course_id: int, q: str = ""):
    query = q.strip()
    if len(query) < 2:
        return {"items": []}
    rows = await asyncio.to_thread(
        app.state.store.search_course_segments, course_id, query, 30
    )
    items = [
        {
            "session_id": row.get("session_id"),
            "title": row.get("title") or "未命名课次",
            "started_at": row.get("started_at") or "",
            "t0": row.get("t0") or 0,
            "src": row.get("src_text") or "",
            "dst": row.get("tgt_text") or "",
        }
        for row in rows
    ]
    return {"items": items}


@app.get("/api/sessions/{session_id}/bookmarks")
async def api_bookmarks(session_id: int):
    return {"items": app.state.store.list_bookmarks(session_id)}


@app.post("/api/sessions/{session_id}/bookmarks")
async def api_add_bookmark(session_id: int, body: dict):
    bookmark_id = app.state.store.add_bookmark(
        session_id,
        int(body.get("segment_id") or 0) or None,
        str(body.get("note") or ""),
    )
    return {"ok": True, "id": bookmark_id}


@app.delete("/api/bookmarks/{bookmark_id}")
async def api_delete_bookmark(bookmark_id: int):
    app.state.store.delete_bookmark(bookmark_id)
    return {"ok": True}


@app.get("/api/sessions/{session_id}/quiz")
async def api_get_quiz(session_id: int):
    if not app.state.store.session_exists(session_id):
        return JSONResponse({"error": "not found"}, status_code=404)
    try:
        items = json.loads(app.state.store.get_quiz(session_id) or "[]")
    except Exception:
        items = []
    return {"items": items}


@app.post("/api/sessions/{session_id}/quiz")
async def api_make_quiz(session_id: int):
    store = app.state.store
    data = store.get_session(session_id)
    if not data:
        return JSONResponse({"error": "not found"}, status_code=404)
    segs = data.get("segments") or []
    if len(segs) < 4:
        return JSONResponse({"ok": False, "error": "这节课的内容太少，无法出题"}, status_code=400)
    body = "\n".join(
        f"- {s.get('src_text')} / {s.get('tgt_text') or ''}" for s in segs[-160:]
    )
    prompt = f"课程内容：\n{body}"
    system = (
        "你是课程助教。根据课堂内容出 5 道复习题，覆盖关键概念。"
        '输出 JSON 数组，每项形如 {"q":"问题","a":"答案","type":"选择/简答"}。'
        "答案要简短准确。只输出 JSON，不要解释。"
    )
    text = await _assistant_text(prompt, max_tokens=900, system=system, timeout=90)
    items = _parse_json_block(text)
    if isinstance(items, dict):
        items = items.get("items") or items.get("questions") or []
    if not isinstance(items, list) or not items:
        return JSONResponse({"ok": False, "error": "助教模型未就绪或没有返回题目"}, status_code=503)
    clean = []
    for row in items[:8]:
        if isinstance(row, dict) and str(row.get("q") or "").strip():
            clean.append(
                {
                    "q": str(row.get("q")).strip(),
                    "a": str(row.get("a") or "").strip(),
                    "type": str(row.get("type") or "简答").strip(),
                }
            )
    store.set_quiz(session_id, json.dumps(clean, ensure_ascii=False))
    return {"ok": True, "items": clean}


@app.get("/api/storage")
async def api_storage():
    data_dir = Path(DATA_DIR)

    def collect() -> dict:
        db_path = data_dir / "history.db"
        usage = None
        try:
            usage = shutil.disk_usage(str(data_dir))
        except Exception:
            pass
        return {
            "data_dir": str(data_dir),
            "db_mb": round(
                db_path.stat().st_size / (1024 ** 2), 1
            ) if db_path.exists() else 0,
            "audio_mb": round(_dir_size(data_dir / "audio") / (1024 ** 2), 1),
            "materials_mb": round(
                _dir_size(data_dir / "materials") / (1024 ** 2), 1
            ),
            "models_mb": round(_dir_size(data_dir / "models") / (1024 ** 2), 1),
            "total_mb": round(_dir_size(data_dir) / (1024 ** 2), 1),
            "free_gb": round(usage.free / (1024 ** 3), 1) if usage else 0,
            "disk_gb": round(usage.total / (1024 ** 3), 1) if usage else 0,
            "retention_days": app.state.settings.get("retention_days", 0),
        }

    return await asyncio.to_thread(collect)


@app.post("/api/storage/cleanup")
async def api_cleanup(body: dict):
    try:
        days = int(body.get("days") or 0)
    except (TypeError, ValueError):
        days = 0
    if days <= 0:
        return JSONResponse({"ok": False, "error": "保留天数必须大于 0"}, status_code=400)
    removed = await asyncio.to_thread(app.state.store.purge_older_than, days)

    def vacuum() -> None:
        try:
            app.state.store.conn.execute("VACUUM")
        except Exception:
            pass

    await asyncio.to_thread(vacuum)
    return {"ok": True, "removed": len(removed), "ids": removed}


@app.get("/api/models/status")
async def api_models_status():
    root = Path(__file__).resolve().parent.parent
    assistant_local = next(
        (
            p
            for p in (ASSISTANT_DOWNLOAD_DIR, root / "models" / "assistant")
            if (p / "config.json").exists()
        ),
        None,
    )
    local = app.state.local_llm
    assistant = app.state.assistant_model
    return {
        "translator": {
            "bundled": (root / "models" / "hy-mt2-1.8b-4bit" / "config.json").exists(),
            "status": local.status if local else "api",
            "path": str(root / "models" / "hy-mt2-1.8b-4bit"),
        },
        "assistant": {
            "bundled": bool(assistant_local),
            "path": str(assistant_local or ASSISTANT_DOWNLOAD_DIR),
            "status": assistant.status if assistant else "api",
            "error": assistant.error if assistant else None,
            "download": getattr(app.state, "downloads", {}).get("assistant"),
        },
        "asr": {
            "engine": app.state.settings.get("asr_engine", "whisper"),
            "status": app.state.asr.status,
        },
        "vad": Path(str(VAD_MODEL_PATH)).exists(),
    }


@app.post("/api/models/download")
async def api_models_download(body: dict):
    which = str(body.get("which") or "assistant")
    if which != "assistant":
        return JSONResponse({"ok": False, "error": "该模型随安装包提供，无需单独下载"}, status_code=400)
    downloads = getattr(app.state, "downloads", {})
    root = Path(__file__).resolve().parent.parent
    if (root / "models" / "assistant" / "config.json").exists() or (
        ASSISTANT_DOWNLOAD_DIR / "config.json"
    ).exists():
        return {"ok": True, "state": "done", "message": "助教模型已就绪，无需下载"}
    if downloads.get("assistant", {}).get("state") == "running":
        return {"ok": True, "state": "running"}
    app.state.downloads = downloads
    downloads["assistant"] = {"state": "running", "progress": 0, "message": "开始下载…"}

    def work() -> None:
        try:
            from huggingface_hub import snapshot_download

            ASSISTANT_DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
            downloads["assistant"] = {
                "state": "running",
                "progress": 0,
                "message": "正在下载助教模型（约 2.3GB）…",
            }
            snapshot_download(
                repo_id=LocalAssistant.REPO,
                local_dir=str(ASSISTANT_DOWNLOAD_DIR),
            )
            downloads["assistant"] = {
                "state": "done",
                "progress": 100,
                "message": "下载完成，正在加载…",
            }
            model = LocalAssistant()
            app.state.assistant_model = model
            model.load_async()
        except Exception as exc:  # noqa: BLE001
            downloads["assistant"] = {
                "state": "error",
                "progress": 0,
                "message": f"下载失败：{exc}",
            }

    threading.Thread(target=work, daemon=True).start()
    return {"ok": True, "state": "running"}


@app.post("/api/eval/run")
async def api_eval():
    if getattr(app.state, "active_sessions", 0) > 0:
        return JSONResponse(
            {"ok": False, "error": "正在上课，评测会占用翻译模型，请结束后再运行"},
            status_code=409,
        )
    settings = app.state.settings
    if settings.get("llm_backend", "builtin") == "builtin" and app.state.local_llm:
        translator = app.state.local_llm
        if translator.status != "ready":
            return JSONResponse({"ok": False, "error": "翻译模型还在加载"}, status_code=503)
    else:
        translator = Translator(
            settings.get("llm_base_url", ""),
            settings.get("llm_model", ""),
            settings.get("llm_api_key", ""),
        )
        if not settings.get("llm_model"):
            return JSONResponse({"ok": False, "error": "未配置翻译模型"}, status_code=400)
    target = settings.get("target_lang", "auto")
    if target == "auto":
        target = "en"
    cases = [
        ("今天下午三点我们在大会议室开项目评审会。", "zh"),
        ("The experiment failed because the sample was contaminated.", "en"),
        ("请把上周的原型带过来，并准备十分钟的演示。", "zh"),
        ("Machine translation still struggles with ambiguous pronouns.", "en"),
        ("这家公司计划明年进入东南亚市场。", "zh"),
        ("We need to double-check the results before publishing.", "en"),
    ]
    results = []
    for text, lang in cases:
        started = time.perf_counter()
        first_ms = None
        parts: list[str] = []
        error = ""
        try:
            async for chunk in translator.translate_stream(text, lang, target, []):
                if first_ms is None:
                    first_ms = int((time.perf_counter() - started) * 1000)
                parts.append(chunk)
        except Exception as exc:  # noqa: BLE001
            error = str(exc)
        total_ms = int((time.perf_counter() - started) * 1000)
        output = "".join(parts).strip()
        ratio = len(output) / max(len(text), 1)
        ok = bool(output) and 0.08 <= ratio <= 8 and not re.search(r"(.)\1{5,}", output)
        results.append(
            {
                "source": text,
                "output": output,
                "lang": lang,
                "first_ms": first_ms,
                "total_ms": total_ms,
                "ratio": round(ratio, 2),
                "ok": ok,
                "error": error,
            }
        )
    good = [r for r in results if r["ok"]]
    firsts = [r["first_ms"] for r in good if r["first_ms"] is not None]
    totals = [r["total_ms"] for r in good]
    return {
        "ok": True,
        "results": results,
        "pass_rate": round(len(good) / len(results), 2),
        "avg_first_ms": int(sum(firsts) / len(firsts)) if firsts else None,
        "avg_total_ms": int(sum(totals) / len(totals)) if totals else None,
        "target": target,
    }


@app.post("/api/materials")
async def upload_material(file: UploadFile = File(...), course_id: int = Form(0)):
    name = file.filename or "material"
    suffix = Path(name).suffix.lower()
    if suffix not in (".pdf", ".docx", ".pptx", ".txt", ".md", ".markdown"):
        return JSONResponse(
            {"ok": False, "error": "支持 PDF / DOCX / PPTX / TXT / Markdown"},
            status_code=400,
        )
    if course_id and not app.state.store.course_exists(int(course_id)):
        return JSONResponse({"ok": False, "error": "课程不存在"}, status_code=400)
    data = await file.read()
    if len(data) > 80 * 1024 * 1024:
        return JSONResponse({"ok": False, "error": "文件超过 80MB"}, status_code=400)
    folder = DATA_DIR / "materials"
    folder.mkdir(parents=True, exist_ok=True)
    try:
        folder.chmod(0o700)
    except OSError:
        pass
    safe_name = Path(name).name or "material"
    dest = folder / f"{int(time.time())}_{uuid.uuid4().hex[:6]}_{safe_name}"
    try:
        dest.write_bytes(data)
    except OSError as exc:
        return JSONResponse({"ok": False, "error": f"写入失败：{exc}"}, status_code=500)
    try:
        # 扫描件 OCR 可能耗时数十秒，放到线程里，避免阻塞实时翻译
        chunks = await asyncio.to_thread(extract_chunks, dest)
    except Exception as exc:  # noqa: BLE001
        dest.unlink(missing_ok=True)
        return JSONResponse({"ok": False, "error": f"解析失败：{exc}"}, status_code=400)
    if not chunks:
        dest.unlink(missing_ok=True)
        return JSONResponse(
            {"ok": False, "error": "没有提取到文字（已尝试本机 OCR，可能是纯图片或手写内容）"},
            status_code=400,
        )
    try:
        material_id = app.state.store.add_material(
            name, str(dest), chunks, course_id or None
        )
    except Exception:
        dest.unlink(missing_ok=True)
        raise
    return {
        "ok": True,
        "id": material_id,
        "chunks": len(chunks),
        "pages": max(p for p, _ in chunks),
    }


@app.get("/api/materials")
async def list_materials(course_id: int = 0):
    return {"materials": app.state.store.list_materials(course_id or None)}


@app.delete("/api/materials/{material_id}")
async def delete_material(material_id: int):
    material = app.state.store.get_material(material_id)
    app.state.store.delete_material(material_id)
    if material and material.get("path"):
        try:
            Path(material["path"]).unlink(missing_ok=True)
        except OSError:
            pass
    return {"ok": True}


@app.get("/api/sessions")
async def api_sessions(q: str = "", tag: str = "", limit: int = 100, course_id: int = 0):
    store = app.state.store
    return {
        "sessions": store.list_sessions(q, tag, limit, course_id or None),
        "tags": store.all_tags(),
    }


@app.get("/api/sessions/{session_id}")
async def api_session(session_id: int):
    data = app.state.store.get_session(session_id)
    if not data:
        return JSONResponse({"error": "not found"}, status_code=404)
    return data


@app.delete("/api/sessions/{session_id}")
async def api_delete_session(session_id: int):
    app.state.store.delete_session(session_id)
    return {"ok": True}


@app.patch("/api/sessions/{session_id}")
async def api_patch_session(session_id: int, body: dict):
    store = app.state.store
    data = store.get_session(session_id)
    if not data:
        return JSONResponse({"error": "not found"}, status_code=404)
    title = body.get("title", data.get("title"))
    summary = body.get("summary", data.get("summary"))
    topics = body.get("topics", data.get("topics"))
    kind = body.get("kind", data.get("kind"))
    store.set_tags(session_id, title, summary, topics, kind, "ready")
    if "user_note" in body:
        app.state.store.set_session_note(session_id, str(body.get("user_note") or ""))
    if "starred" in body:
        with store._lock:
            app.state.store.conn.execute(
                "UPDATE sessions SET starred=? WHERE id=?",
                (1 if body["starred"] else 0, session_id),
            )
            store.conn.commit()
    return {"ok": True}


@app.patch("/api/sessions/{session_id}/speakers/{speaker_id}")
async def rename_speaker(session_id: int, speaker_id: int, body: dict):
    label = str(body.get("label") or "").strip()
    if not label:
        return JSONResponse({"error": "label required"}, status_code=400)
    app.state.store.rename_speaker(session_id, speaker_id, label[:24])
    return {"ok": True}


@app.get("/api/segments/{segment_id}/audio")
async def api_segment_audio(segment_id: int):
    rel = app.state.store.get_segment_audio(segment_id)
    if not rel:
        return JSONResponse({"error": "no audio"}, status_code=404)
    path = (DATA_DIR / rel).resolve()
    if not str(path).startswith(str(DATA_DIR.resolve())) or not path.exists():
        return JSONResponse({"error": "no audio"}, status_code=404)
    return FileResponse(str(path))


@app.get("/api/assistant/test")
async def assistant_test():
    settings = app.state.settings
    if not (settings.get("assistant_model") and settings.get("assistant_base_url")):
        return {"ok": False, "error": "未配置分析模型"}
    tr = Translator(
        settings["assistant_base_url"],
        settings["assistant_model"],
        settings.get("assistant_api_key") or "",
    )
    started = time.monotonic()
    try:
        out = await asyncio.wait_for(
            tr.generate("用一个词回答：1+1等于几？", max_tokens=16, system="直接回答。"),
            timeout=20,
        )
        return {"ok": True, "ms": int((time.monotonic() - started) * 1000), "text": out}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "ms": int((time.monotonic() - started) * 1000), "error": str(exc)}


@app.post("/api/builtin/test")
async def builtin_test():
    local = app.state.local_llm
    if local is None:
        return {"ok": False, "error": "内置模型未启用"}
    if local.status != "ready":
        return {"ok": False, "error": local.error or "模型加载中，请稍候"}
    try:
        return {"ok": True, **await local.test_translate("en")}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)}


@app.post("/api/shutdown")
async def shutdown():
    threading.Timer(0.3, lambda: os._exit(0)).start()
    return {"ok": True}


def _resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst or len(x) == 0:
        return x
    n = int(round(len(x) * dst / src))
    if n <= 1:
        return np.zeros(0, dtype=np.float32)
    idx = np.linspace(0, len(x) - 1, n)
    return np.interp(idx, np.arange(len(x)), x).astype(np.float32)


@app.websocket("/ws/audio")
async def ws_audio(ws: WebSocket):
    await ws.accept()
    settings = dict(app.state.settings)
    start: dict = {}
    try:
        first = await asyncio.wait_for(ws.receive(), timeout=10)
        if first.get("text"):
            start = json.loads(first["text"])
    except Exception:
        await ws.close()
        return

    if start.get("type") != "start":
        await ws.send_json({"type": "error", "message": "首次消息必须是 start"})
        await ws.close()
        return

    cfg = dict(settings)
    for key in SETTING_KEYS:
        if key in start:
            cfg[key] = start[key]

    sample_rate = int(start.get("sample_rate") or 16000)
    cfg["audio_source"] = start.get("source", "mic")
    try:
        cfg["course_id"] = int(start.get("course_id") or 0)
    except Exception:
        cfg["course_id"] = 0
    if cfg["course_id"] and not app.state.store.course_exists(cfg["course_id"]):
        cfg["course_id"] = 0
    cfg["asr_engine"] = app.state.settings.get("asr_engine", "whisper")
    if cfg["asr_engine"] == "qwen3":
        cfg["asr_model_name"] = app.state.settings.get("asr_qwen_model", "")
    elif cfg["asr_engine"] == "sensevoice":
        cfg["asr_model_name"] = "SenseVoice-Small"
    else:
        cfg["asr_model_name"] = app.state.settings.get("asr_model", "")
    cfg["llm_backend"] = app.state.settings.get("llm_backend", "builtin")
    cfg["llm_model"] = (
        app.state.settings.get("llm_model") or "Hy-MT2-1.8B-4bit"
        if cfg["llm_backend"] == "builtin"
        else app.state.settings.get("llm_model", "")
    )
    if cfg.get("llm_backend", "builtin") == "builtin" and app.state.local_llm is not None:
        translator = app.state.local_llm
    else:
        translator = Translator(
            cfg["llm_base_url"], cfg["llm_model"], cfg["llm_api_key"]
        )
    send_lock = asyncio.Lock()

    async def send(obj: dict) -> None:
        async with send_lock:
            await ws.send_json(obj)

    session = Session(
        send, app.state.asr, translator, cfg,
        store=app.state.store, assistant_model=app.state.assistant_model,
    )
    app.state.active_sessions = getattr(app.state, "active_sessions", 0) + 1

    try:
        await send(
            {
                "type": "ready",
                "asr_status": app.state.asr.status,
                "sample_rate": sample_rate,
            }
        )
        frames = 0
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                break
            data = message.get("bytes")
            if data is not None:
                audio = np.frombuffer(data, dtype=np.float32)
                audio = _resample(audio, sample_rate, 16000)
                frames += 1
                if frames == 1 or frames % 100 == 0:
                    rms = (
                        float(np.sqrt(np.mean(np.square(audio))))
                        if len(audio)
                        else 0.0
                    )
                    print(
                        f"[ws] audio frames={frames} samples={len(audio)} rms={rms:.4f}",
                        flush=True,
                    )
                await session.feed(audio)
                continue
            text = message.get("text")
            if text:
                try:
                    payload = json.loads(text)
                except json.JSONDecodeError:
                    continue
                if payload.get("type") == "stop":
                    break
                if payload.get("type") == "ask":
                    await session.ask(payload.get("text") or "")
                    continue
                if payload.get("type") == "recap":
                    await session.recap(int(payload.get("minutes") or 5))
                    continue
                if payload.get("type") == "bookmark":
                    await session.bookmark(str(payload.get("note") or ""))
                    continue
                if payload.get("type") == "pause":
                    await session.pause()
                    continue
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        pass
    finally:
        try:
            await session.stop()
        finally:
            app.state.active_sessions = max(
                0, getattr(app.state, "active_sessions", 1) - 1
            )
            try:
                await ws.close()
            except Exception:
                pass


def main() -> None:
    port = int(os.environ.get("PORT", "8765"))
    url = f"http://127.0.0.1:{port}"
    if os.environ.get("NO_BROWSER") != "1":
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="info")


if __name__ == "__main__":
    main()
