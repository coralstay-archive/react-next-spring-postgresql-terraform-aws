#!/usr/bin/env python3
"""diagrams/*.dot → docs/diagrams/<이름>.svg 렌더 + 문서의 <!--dot:이름--> 블록 갱신 (TASK-18#4).

원본과 생성물
  원본은 diagrams/*.dot 이다. docs/diagrams/*.svg 는 이 스크립트가 만드는 생성물이라 직접 고치지 않는다.
  그림을 바꾸려면 .dot 을 고치고 `make diagrams`(= python3 scripts/build-diagrams.py)를 돌린다.

색 — 라이트/다크 (센티넬 → CSS 변수)
  .dot 에는 색을 센티넬 hex(#f00001 …)로만 쓴다. 이 스크립트가 센티넬을 var(--d-*)로 바꾸고, SVG 맨 앞의 <style> 에
  라이트 값(기본)과 @media (prefers-color-scheme: dark) 다크 값을 정의한다. 그래서 SVG 파일 하나가 보는 쪽 테마를 따른다.

GitHub 에서 보이게 — 인라인 <svg> 대신 독립 SVG 파일 + <img>
  GitHub 의 README 렌더러는 Markdown 안의 인라인 <svg> 를 지우고, 페이지 CSS 변수도 SVG 에 전달하지 않는다.
  그래서 변수 정의를 SVG 파일 안에 넣고, 문서에서는 <img src="docs/diagrams/NAME.svg"> 로 참조한다. <img> 로 불러온 SVG 는
  스크립트·외부 리소스는 막히지만 내부 <style> 과 prefers-color-scheme 미디어 쿼리는 적용된다(OS·브라우저 테마를 따른다).
  GitHub 테마를 OS 와 다르게 강제한 경우에도 읽히도록 배경을 투명으로 두지 않고 테마 배경색(--d-bg)을 칠한다.

문서 블록
  README.md 와 docs/*.md 에서 <!--dot:NAME--> … <!--/dot:NAME--> 사이를 <img> 한 줄로 바꾼다(경로는 문서 위치 기준 상대 경로).
  alt 문구는 .dot 의 `// alt: …` 줄에서 읽는다.

사용
  python3 scripts/build-diagrams.py          SVG·문서 블록 갱신
  python3 scripts/build-diagrams.py --check  바뀔 것이 있으면 목록을 보이고 1 로 끝난다(repo_test, 커밋 전 확인)
전제: graphviz `dot`. 글꼴 측정이 환경마다 달라 SVG 가 바이트 단위로 달라질 수 있다 — 렌더는 한 환경(로컬)에서 하고 커밋한다.
"""
from __future__ import annotations

import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "diagrams"
OUT = ROOT / "docs" / "diagrams"
DOCS = [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md"))]
BUILD_MARK = 'data-generated-by="scripts/build-diagrams.py"'

# 센티넬 → 토큰 이름. .dot 은 이 hex 만 쓴다(diagrams/README 주석 참고).
TOKENS = {
    "#f00001": "ink",        # 글자
    "#f00002": "muted",      # 보조 글자·설명
    "#f00003": "line",       # 선·테두리
    "#f00004": "surface",    # 노드 바탕
    "#f00005": "surface-2",  # 클러스터 바탕
    "#f00006": "accent",     # 강조 선·테두리
    "#f00007": "accent-bg",  # 강조 바탕
    "#f00008": "aws",        # AWS 관리형 서비스 테두리
    "#f00009": "aws-bg",
    "#f0000a": "ext",        # 외부(IdP·GitHub)
    "#f0000b": "ext-bg",
    "#f0000c": "data",       # 저장소(DB·S3·EBS)
    "#f0000d": "data-bg",
    "#f0000e": "warn",       # 실패·롤백 경로
    "#f0000f": "bg",         # 그림 배경
}
LIGHT = {
    "ink": "#1f2328", "muted": "#59636e", "line": "#8c959f", "surface": "#ffffff", "surface-2": "#f6f8fa",
    "accent": "#0969da", "accent-bg": "#ddf4ff", "aws": "#bc4c00", "aws-bg": "#fff1e5",
    "ext": "#8250df", "ext-bg": "#fbefff", "data": "#1a7f37", "data-bg": "#dafbe1", "warn": "#cf222e", "bg": "#ffffff",
}
DARK = {
    "ink": "#e6edf3", "muted": "#9198a1", "line": "#656c76", "surface": "#151b23", "surface-2": "#1c2128",
    "accent": "#4493f8", "accent-bg": "#0c2d6b", "aws": "#f0883e", "aws-bg": "#3d1e0b",
    "ext": "#ab7df8", "ext-bg": "#2b1b4a", "data": "#3fb950", "data-bg": "#0f2d19", "warn": "#f85149", "bg": "#0d1117",
}
FONT = ('-apple-system, BlinkMacSystemFont, "Segoe UI", "Apple SD Gothic Neo", "Noto Sans KR", '
        '"Malgun Gothic", Helvetica, Arial, sans-serif')


def theme_style() -> str:
    decl = lambda t: " ".join(f"--d-{k}: {v};" for k, v in t.items())
    return (f"<style>svg {{ {decl(LIGHT)} }}\n"
            f"@media (prefers-color-scheme: dark) {{ svg {{ {decl(DARK)} }} }}\n"
            f"text {{ font-family: {FONT}; }}</style>")


def finish(svg: str, name: str, alt: str) -> str:
    svg = re.sub(r"<\?xml[^>]*\?>\s*", "", svg)
    svg = re.sub(r"<!DOCTYPE[^>]*>\s*", "", svg, flags=re.S)
    svg = re.sub(r"<!--.*?-->\s*", "", svg, flags=re.S)
    # 그래프 배경(흰 폴리곤)은 테마 배경으로
    svg = re.sub(r'(<g id="graph0"[^>]*>\s*(?:<title>[^<]*</title>\s*)?<polygon) fill="white" stroke="none"',
                 r'\1 fill="#f0000f" stroke="none"', svg, count=1)
    unknown = set(re.findall(r"#f0[0-9a-f]{4}", svg, flags=re.I)) - set(TOKENS)
    if unknown:
        sys.exit(f"{name}: 알 수 없는 센티넬 색 {sorted(unknown)}")
    for sentinel, token in TOKENS.items():
        svg = re.sub(re.escape(sentinel), f"var(--d-{token})", svg, flags=re.I)
    # 센티넬이 아닌 색이 남으면(.dot 에 직접 쓴 색) 테마를 못 따른다
    stray = sorted(set(re.findall(r'(?:fill|stroke)="(#[0-9a-fA-F]{3,6}|white|black)"', svg)))
    if stray:
        sys.exit(f"{name}: 센티넬이 아닌 색 {stray} — diagrams/*.dot 에서 #f000xx 로 바꾼다")
    svg = re.sub(r' font-family="[^"]*"', "", svg)

    # fill="var(...)" 같은 표현 속성 대신 style 로 옮긴다(var() 는 CSS 선언에서 확실히 동작한다)
    def to_style(m: re.Match[str]) -> str:
        tag = m.group(0)
        decls = [f"{k}:{v}" for k, v in re.findall(r' (fill|stroke)="(var\(--d-[\w-]+\))"', tag)]
        if not decls:
            return tag
        tag = re.sub(r' (fill|stroke)="var\(--d-[\w-]+\)"', "", tag)
        end = "/>" if tag.endswith("/>") else ">"
        return tag[: -len(end)].rstrip() + f' style="{";".join(decls)}"' + end

    svg = re.sub(r"<(?!svg\b)[a-z]+\b[^>]*>", to_style, svg)

    def fix_root(m: re.Match[str]) -> str:
        tag = m.group(0)
        tag = re.sub(r'\s(width|height)="([\d.]+)pt"', lambda a: f' {a.group(1)}="{a.group(2)}"', tag)
        return tag.replace("<svg", f'<svg {BUILD_MARK} role="img" aria-label="{alt}"', 1)

    svg = re.sub(r"<svg\b[^>]*>", fix_root, svg, count=1)
    svg = re.sub(r"(<svg\b[^>]*>)", lambda m: m.group(1) + "\n" + theme_style(), svg, count=1)
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + svg.strip() + "\n"


def alt_of(src: pathlib.Path) -> str:
    m = re.search(r"^\s*//\s*alt:\s*(.+)$", src.read_text(encoding="utf-8"), flags=re.M)
    return (m.group(1).strip() if m else src.stem).replace('"', "'")


def render(src: pathlib.Path) -> str:
    dot = shutil.which("dot")
    if not dot:
        sys.exit("graphviz dot 이 없다 (brew install graphviz / apt-get install graphviz)")
    r = subprocess.run([dot, "-Tsvg", str(src)], capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"dot 실패: {src}\n{r.stderr}")
    return finish(r.stdout, src.stem, alt_of(src))


def update_doc(text: str, doc: pathlib.Path, alts: dict[str, str]) -> str:
    def block(m: re.Match[str]) -> str:
        name = m.group(1)
        if name not in alts:
            sys.exit(f"{doc.relative_to(ROOT)}: <!--dot:{name}--> 에 해당하는 diagrams/{name}.dot 이 없다")
        rel = os.path.relpath(OUT / f"{name}.svg", doc.parent)
        return f'<!--dot:{name}-->\n<p align="center"><img src="{rel}" alt="{alts[name]}"></p>\n<!--/dot:{name}-->'

    return re.sub(r"<!--dot:([\w-]+)-->.*?<!--/dot:\1-->", block, text, flags=re.S)


def main() -> int:
    check = "--check" in sys.argv[1:]
    sources = sorted(SRC.glob("*.dot"))
    if not sources:
        sys.exit(f"소스 없음: {SRC}")
    wanted: dict[pathlib.Path, str] = {OUT / f"{s.stem}.svg": render(s) for s in sources}
    alts = {s.stem: alt_of(s) for s in sources}
    for doc in DOCS:
        if doc.exists():
            text = doc.read_text(encoding="utf-8")
            wanted[doc] = update_doc(text, doc, alts)
    # 소스가 없어진 SVG 는 지운다
    stale = [p for p in OUT.glob("*.svg") if p not in wanted] if OUT.exists() else []

    changed = [p for p, body in wanted.items() if not p.exists() or p.read_text(encoding="utf-8") != body]
    if check:
        for p in changed + stale:
            print(f"  out of date: {p.relative_to(ROOT)}")
        if changed or stale:
            print("다이어그램이 .dot 과 다르다: make diagrams 후 커밋한다")
            return 1
        print(f"diagrams up to date ({len(sources)})")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for p in changed:
        p.write_text(wanted[p], encoding="utf-8")
        print(f"  wrote {p.relative_to(ROOT)}")
    for p in stale:
        p.unlink()
        print(f"  removed {p.relative_to(ROOT)}")
    print(f"완료 — SVG {len(sources)}개, 바뀐 파일 {len(changed) + len(stale)}개")
    return 0


if __name__ == "__main__":
    sys.exit(main())
