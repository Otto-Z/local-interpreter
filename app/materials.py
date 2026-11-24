"""课件解析：PDF / DOCX / PPTX / 文本 -> 带页码的文本块。

扫描版 PDF 没有文字层时，调用随应用附带的 Vision OCR 助手（完全离线）。
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

CHUNK_SIZE = 700
CHUNK_OVERLAP = 100


def _ocr_pdf(path: Path) -> list[tuple[int, str]]:
    root = Path(__file__).resolve().parent.parent
    helper = next(
        (
            candidate
            for candidate in (root / "bin" / "pdfocr", root / "build" / "pdfocr")
            if candidate.exists()
        ),
        None,
    )
    if helper is None:
        return []
    try:
        result = subprocess.run(
            [str(helper), str(path)],
            capture_output=True,
            timeout=240,
            check=False,
        )
        if result.returncode != 0:
            return []
        data = json.loads(result.stdout.decode("utf-8", "ignore") or "{}")
    except Exception:
        return []
    pages: list[tuple[int, str]] = []
    for item in data.get("pages") or []:
        try:
            page = int(item.get("page") or 0)
        except Exception:
            continue
        text = str(item.get("text") or "").strip()
        if page > 0 and text:
            pages.append((page, text))
    return pages


def extract_chunks(path: Path) -> list[tuple[int, str]]:
    suffix = path.suffix.lower()
    pages: list[tuple[int, str]] = []
    if suffix == ".pdf":
        from pypdf import PdfReader

        reader = PdfReader(str(path))
        for index, page in enumerate(reader.pages):
            text = (page.extract_text() or "").strip()
            if text:
                pages.append((index + 1, text))
        # 有些扫描件只带一行水印文字，文字层很短时也走 OCR
        if not pages or sum(len(text) for _, text in pages) < 40:
            ocr_pages = _ocr_pdf(path)
            if ocr_pages:
                pages = ocr_pages
    elif suffix == ".docx":
        import docx

        document = docx.Document(str(path))
        text = "\n".join(p.text for p in document.paragraphs if p.text.strip())
        if text.strip():
            pages.append((1, text))
    elif suffix == ".pptx":
        import re as _re
        import zipfile
        from html import unescape

        with zipfile.ZipFile(str(path)) as archive:
            names = [
                name
                for name in archive.namelist()
                if _re.fullmatch(r"ppt/slides/slide\d+\.xml", name)
            ]
            names.sort(key=lambda name: int(_re.search(r"(\d+)", name).group(1)))
            for index, name in enumerate(names, 1):
                xml = archive.read(name).decode("utf-8", "ignore")
                texts = [
                    unescape(item)
                    for item in _re.findall(r"<a:t>(.*?)</a:t>", xml, _re.S)
                ]
                text = "\n".join(t.strip() for t in texts if t.strip())
                if text:
                    pages.append((index, text))
    else:
        text = path.read_text("utf-8", errors="ignore")
        if text.strip():
            pages.append((1, text))

    chunks: list[tuple[int, str]] = []
    for page, text in pages:
        text = text.strip()
        start = 0
        while start < len(text):
            end = min(len(text), start + CHUNK_SIZE)
            piece = text[start:end].strip()
            if piece:
                chunks.append((page, piece))
            if end >= len(text):
                break
            start = end - CHUNK_OVERLAP
    return chunks
