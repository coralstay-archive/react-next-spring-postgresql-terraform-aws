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

렌더 환경 고정 (TASK-35#1, OPS-38)
  graphviz 버전·OS(글꼴 측정)가 다르면 같은 .dot 이라도 SVG 좌표가 바이트 단위로 달라진다. 그래서 커밋된 SVG 를 만든 환경을
  diagrams/render-env.txt(graphviz=<버전>, os=<uname -s>)에 적어 두고, --check 는 현재 환경이 이 값과 같을 때만 SVG 를 바이트 비교한다.
  다르면(또는 파일이 없으면) 바이트 비교는 SKIP 이라고 알리고, 환경과 무관한 것만 본다: SVG 의 구조(structure() — 글자
  <title>·<text> 내용 = .dot 의 노드·라벨, 요소 태그 순서, var(--d-*) 색 토큰 순서, 모양·화살표 요소 종류별 개수), 문서 블록,
  .dot 마다 SVG 가 있는지·소스 없는 SVG 가 남았는지. 그래서 색 센티넬·모양만 바꾸고 다시 렌더하지 않은 것도 잡는다.
  CI(Linux, 다른 graphviz)도 이 구조 비교로 검사한다. graphviz 를 올려 다시 렌더했으면 --env 출력으로 파일을 바꾼다.

사용
  python3 scripts/build-diagrams.py          SVG·문서 블록 갱신 (환경이 고정값과 다르면 경고)
  python3 scripts/build-diagrams.py --check  바뀔 것이 있으면 파일 목록과 diff 일부를 보이고 1 로 끝난다(repo_test, 커밋 전 확인)
  python3 scripts/build-diagrams.py --env    현재 렌더 환경을 render-env.txt 형식으로 출력
전제: graphviz `dot`.
"""
from __future__ import annotations

import difflib
import os
import pathlib
import platform
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "diagrams"
OUT = ROOT / "docs" / "diagrams"
DOCS = [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md"))]
PIN = SRC / "render-env.txt"
DIFF_LINES = 20  # --check 가 파일마다 보여 줄 diff 줄 수
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
    # 그래프 배경(흰 폴리곤)은 테마 배경으로. 옛 graphviz(Ubuntu 2.43 — CI)는 stroke="transparent" 로 쓴다
    svg = re.sub(r'(<g id="graph0"[^>]*>\s*(?:<title>[^<]*</title>\s*)?<polygon) fill="white" stroke="(?:none|transparent)"',
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


def dot_path() -> str:
    dot = shutil.which("dot")
    if not dot:
        sys.exit("graphviz dot 이 없다 (brew install graphviz / apt-get install graphviz)")
    return dot


def current_env() -> dict[str, str]:
    r = subprocess.run([dot_path(), "-V"], capture_output=True, text=True)
    m = re.search(r"version\s+(\S+)", r.stderr + r.stdout)
    return {"graphviz": m.group(1) if m else "unknown", "os": platform.system()}


def pinned_env() -> dict[str, str] | None:
    if not PIN.exists():
        return None
    pairs = (line.split("=", 1) for line in PIN.read_text(encoding="utf-8").splitlines()
             if "=" in line and not line.lstrip().startswith("#"))
    return {k.strip(): v.strip() for k, v in pairs}


def env_text(env: dict[str, str]) -> str:
    return " ".join(f"{k}={v}" for k, v in env.items())


def glyphs(svg: str) -> list[str]:
    """환경(글꼴·버전)과 무관한 SVG 내용: <title>·<text> 글자 순서(.dot 의 노드 이름·라벨)."""
    return re.findall(r"<(?:title|text)\b[^>]*>([^<]*)</(?:title|text)>", svg)


SHAPES = ("polygon", "polyline", "path", "ellipse", "circle", "rect", "line", "text", "image", "a")


def structure(svg: str) -> dict[str, list[str]]:
    """환경(글꼴 측정·좌표)과 무관한 SVG 구조. 글자가 같아도 색·모양만 바뀐 .dot 을 잡는다(TASK-35#1).
      글자     <title>·<text> 내용 순서
      요소     그룹(<g class="node|edge|cluster">, 제목으로 식별) 순서대로, 그룹 안 요소 태그
      색 토큰  같은 순서로, 요소마다 쓰는 var(--d-*) (fill·stroke 짝 그대로)
      모양 종류 모양·화살표 요소(polygon·path·ellipse …) 종류별 개수
    그룹 순서는 그대로 비교하되 그룹 안 요소 순서는 정렬해서 본다: graphviz 버전마다 한 노드를 그리는 순서가 다르다
    (예: cylinder 의 path·polygon 순서 — Ubuntu 2.43 과 16.x). 좌표·크기 속성은 보지 않는다."""
    body = svg.split("</style>", 1)[-1]
    groups: list[tuple[str, list[tuple[str, str]]]] = []  # (그룹 이름, [(태그, 색 토큰)])
    stack: list[int] = []
    pending_title: int | None = None
    counts: dict[str, int] = {}
    for m in re.finditer(r"<(/?)([a-zA-Z][\w:-]*)\b([^>]*)>([^<]*)", body):
        close, tag, attrs, after = m.groups()
        if tag == "g":
            if close:
                stack and stack.pop()
                continue
            cls = re.search(r'class="([^"]*)"', attrs)
            groups.append((cls.group(1) if cls else "g", []))
            stack.append(len(groups) - 1)
            pending_title = len(groups) - 1
            continue
        if close:
            continue
        if tag == "title" and pending_title is not None:
            groups[pending_title] = (f"{groups[pending_title][0]} {after.strip()}", groups[pending_title][1])
            pending_title = None
            continue
        if not stack:
            continue
        if tag in SHAPES:
            counts[tag] = counts.get(tag, 0) + 1
        tokens = " ".join(re.findall(r"var\(--d-[\w-]+\)", attrs))
        groups[stack[-1]][1].append((tag, tokens))
    elems: list[str] = []
    colors: list[str] = []
    for name, items in groups:
        for tag, tokens in sorted(items):
            elems.append(f"{name}: {tag}")
            if tokens:
                colors.append(f"{name}: {tag} {tokens}")
    return {
        "글자": glyphs(svg),
        "요소": elems,
        "색 토큰": colors,
        "모양 종류": [f"{t}={n}" for t, n in sorted(counts.items())],
    }


def show_diff(old: list[str], new: list[str], label: str) -> None:
    lines = list(difflib.unified_diff(old, new, f"{label} (커밋됨)", f"{label} (새로 렌더)", n=1, lineterm=""))
    for line in lines[:DIFF_LINES]:
        print(f"      {line}")
    if len(lines) > DIFF_LINES:
        print(f"      … ({len(lines) - DIFF_LINES}줄 더)")


def render(src: pathlib.Path) -> str:
    dot = dot_path()
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
    if "--env" in sys.argv[1:]:
        print("# build-diagrams.py --check 가 SVG 를 바이트 비교하는 렌더 환경(커밋된 SVG 를 만든 환경). --env 출력으로 갱신한다")
        for k, v in current_env().items():
            print(f"{k}={v}")
        return 0
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
    env, pin = current_env(), pinned_env()
    pinned = pin is not None and all(pin.get(k) == v for k, v in env.items())
    if check:
        if not pinned:
            why = f"{PIN.relative_to(ROOT)} 이 없다" if pin is None else f"고정 {env_text(pin)}"
            print(f"SKIP - SVG 바이트 비교: 현재 렌더 환경 {env_text(env)} ≠ {why}. 구조(글자·요소·색 토큰·모양 종류)·문서 블록·파일 목록만 비교한다")
        bad: list[pathlib.Path] = []
        for p in changed:
            old = p.read_text(encoding="utf-8") if p.exists() else ""
            if p.suffix == ".svg" and not pinned and p.exists():
                a, b = structure(old), structure(wanted[p])
                diffs = [k for k in a if a[k] != b[k]]
                if not diffs:
                    continue
                bad.append(p)
                print(f"  out of date: {p.relative_to(ROOT)} ({'·'.join(diffs)}이 다르다)")
                for k in diffs:
                    print(f"    [{k}]")
                    show_diff(a[k], b[k], f"{p.relative_to(ROOT)} {k}")
            else:
                bad.append(p)
                print(f"  out of date: {p.relative_to(ROOT)}" + ("" if p.exists() else " (없음)"))
                show_diff(old.splitlines(), wanted[p].splitlines(), str(p.relative_to(ROOT)))
        for p in stale:
            print(f"  out of date: {p.relative_to(ROOT)} (소스 .dot 이 없다)")
        if bad or stale:
            print("다이어그램이 .dot 과 다르다: make diagrams 후 커밋한다")
            return 1
        print(f"diagrams up to date ({len(sources)})")
        return 0
    if not pinned:
        print(f"WARNING: 렌더 환경 {env_text(env)} 이 고정값({PIN.relative_to(ROOT)})과 다르다 — SVG 좌표가 바뀔 수 있다."
              f" 이 환경으로 고정하려면 --env 출력으로 그 파일을 바꾼다", file=sys.stderr)
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
