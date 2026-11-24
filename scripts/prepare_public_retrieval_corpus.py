#!/usr/bin/env python3
"""下载公开制度的固定快照；仓库只保存来源与摘要，原网页和正文留在 data/。"""

import argparse
import hashlib
import json
import re
import time
from pathlib import Path

import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
BENCHMARK = ROOT / "docs/evaluation/public-campus"
CACHE = ROOT / "data/public-campus"
SOURCES = [("1356", i) for i in [1983, 1984, 1986, 1987, 1988, 1989, 1990, 1991,
                               1992, 1993, 1994, 1995, 1996, 1997, 3377, 1999, 2597, 2001, 2002]]
SOURCES += [("1357", i) for i in [2003, 2004, 2005, 2006]]


def sha(text):
    """以 UTF-8 正文摘要固定评测资料版本。"""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def extract(html):
    """提取教务部网页正文，合并行内格式并保留段落边界。"""
    soup = BeautifulSoup(html, "html.parser")
    title = soup.title.get_text().removesuffix("-深圳大学教务部").strip()
    body = soup.select_one("#vsb_content")
    if body is None:
        raise ValueError("missing article body")
    paragraphs = [re.sub(r"\s+", " ", p.get_text("", strip=False)).strip()
                  for p in body.select("p")]
    paragraphs = [p for p in paragraphs if p]
    if not paragraphs:
        raise ValueError("empty article")
    return title, paragraphs


def main():
    """首次固定语料清单，后续下载必须匹配既有正文摘要。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", action="store_true", help="首次创建来源清单，不覆盖既有清单")
    args = parser.parse_args()
    manifest_path = BENCHMARK / "sources.json"
    if args.freeze and manifest_path.exists():
        raise SystemExit("sources.json already exists; do not overwrite a frozen benchmark")
    manifest = None if args.freeze else json.loads(manifest_path.read_text(encoding="utf-8"))
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / "documents").mkdir(exist_ok=True)
    docs = []
    for category, number in SOURCES:
        key = f"szu-{number}"
        url = f"https://jwb.szu.edu.cn/info/{category}/{number}.htm"
        raw = CACHE / (key + ".html")
        if not raw.exists():
            response = requests.get(url, timeout=45)
            response.raise_for_status()
            raw.write_bytes(response.content)
            time.sleep(0.25)
        title, paragraphs = extract(raw.read_text(encoding="utf-8"))
        text = title + "\n\n" + "\n\n".join(paragraphs) + "\n"
        item = {"id": key, "title": title, "url": url, "textSha256": sha(text),
                "paragraphs": [{"id": f"p{i + 1:03}", "sha256": sha(p)} for i, p in enumerate(paragraphs)]}
        if manifest and item != next(d for d in manifest["documents"] if d["id"] == key):
            raise SystemExit("source changed; restore frozen snapshot before evaluation: " + key)
        (CACHE / "documents" / (key + ".md")).write_text(text, encoding="utf-8", newline="\n")
        (CACHE / (key + ".json")).write_text(json.dumps({"title": title, "paragraphs": dict(
            zip((p["id"] for p in item["paragraphs"]), paragraphs))}, ensure_ascii=False, indent=2), encoding="utf-8")
        docs.append(item)
        print(f"{key}: {title} ({len(paragraphs)} paragraphs)", flush=True)
    if args.freeze:
        BENCHMARK.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps({"id": "szu-undergraduate-v1", "capturedOn": "2026-11-24",
            "extractionVersion": 1, "documents": docs}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
