#!/usr/bin/env bash
# 로컬 도구 테스트 (TASK-35): build-diagrams --check 의 렌더 환경 고정, e2e 프로세스 정리, 프론트 의존성 갱신, Makefile env 범위.
# scripts/test.sh 가 호출한다. 실제 graphviz·npm·AWS 는 부르지 않는다(PATH 앞에 둔 가짜 명령·임시 디렉터리).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0
OUT=""
RC=0

# check <이름> <명령...> — 명령 출력은 실패할 때만 보인다
check() {
  local name="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then
    passes=$((passes + 1)); echo "  ok   - $name"
  else
    failures=$((failures + 1)); echo "  FAIL - $name"
    printf '%s\n' "$out" | sed 's/^/         | /' | tail -40
  fi
}
run_capture() { OUT="$("$@" 2>&1)"; RC=$?; }
has_text() { grep -qF -- "$1" <<<"$OUT" || { echo "missing text: $1"; echo "--- output:"; echo "$OUT"; return 1; }; }
lacks_text() { ! grep -qF -- "$1" <<<"$OUT" || { echo "unexpected text: $1"; echo "--- output:"; echo "$OUT"; return 1; }; }
rc_is() { [ "$RC" = "$1" ] || { echo "rc=$RC (기대 $1)"; echo "$OUT"; return 1; }; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "tools tests"

# ================================================================ TASK-35#1 OPS-38 build-diagrams --check: 고정 환경에서만 바이트 비교 + 차이 목록
# 임시 트리(scripts/build-diagrams.py 복사본 + diagrams/a.dot + README 블록)와 가짜 dot 으로 돌린다.
# 가짜 dot: -V 는 FAKE_DOT_VERSION, -Tsvg 는 .dot 의 첫 label 을 글자로, FAKE_DOT_X 를 x 좌표로 쓴다(글꼴 측정 차이 흉내).
DG="$TMP/dg"
mkdir -p "$DG/bin" "$DG/r/scripts" "$DG/r/diagrams" "$DG/r/docs"
cp scripts/build-diagrams.py "$DG/r/scripts/"
cat >"$DG/bin/dot" <<'SH'
#!/usr/bin/env bash
if [ "${1:-}" = -V ]; then echo "dot - graphviz version ${FAKE_DOT_VERSION:-1.0.0} (20260101.0000)" >&2; exit 0; fi
src="${*: -1}"
label="$(sed -nE 's/.*label="([^"]*)".*/\1/p' "$src" | head -n1)"
cat <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<svg width="100pt" height="50pt" viewBox="0.00 0.00 100.00 50.00" xmlns="http://www.w3.org/2000/svg">
<g id="graph0" class="graph">
<title>G</title>
<polygon fill="white" stroke="none" points="0,0 100,0 100,50 0,50"/>
<g id="node1" class="node">
<title>a</title>
<text text-anchor="middle" x="${FAKE_DOT_X:-10}" y="20" font-family="Helvetica" fill="#f00001">$label</text>
</g>
</g>
</svg>
EOF
SH
chmod +x "$DG/bin/dot"
printf '// alt: 그림 A\ndigraph G { a [label="hello"]; }\n' >"$DG/r/diagrams/a.dot"
printf '# t\n\n<!--dot:a-->\n<!--/dot:a-->\n' >"$DG/r/README.md"
dg() { PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" "$@"; }
# 고정 환경 = 가짜 dot 1.0.0 + 이 OS. --env 출력의 graphviz 줄만 바꿔 고정값 파일을 만든다
dg_pin() { FAKE_DOT_VERSION="$1" dg --env >"$DG/r/diagrams/render-env.txt"; }
dg_reset() {
  printf '// alt: 그림 A\ndigraph G { a [label="hello"]; }\n' >"$DG/r/diagrams/a.dot"
  dg_pin 1.0.0
  FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=10 dg >/dev/null
}

t_dg_env() {
  run_capture env FAKE_DOT_VERSION=9.8.7 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --env
  rc_is 0 && has_text "graphviz=9.8.7" && has_text "os=$(uname -s)"
}
check "TASK-35#1 --env 는 렌더 환경(graphviz 버전·OS)을 출력" t_dg_env
t_dg_same() {
  dg_reset
  run_capture env FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=10 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 0 && has_text "diagrams up to date"
}
check "TASK-35#1 고정 환경 + 변경 없음 → 0" t_dg_same
t_dg_pinned_diff() {
  dg_reset
  run_capture env FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "docs/diagrams/a.svg" && has_text '+<text text-anchor="middle" x="11"' && has_text '-<text text-anchor="middle" x="10"'
}
check "TASK-35#1 고정 환경에서 SVG 가 다르면 1 + 파일 목록과 diff 를 보인다" t_dg_pinned_diff
t_dg_other_env_coords() {
  dg_reset
  run_capture env FAKE_DOT_VERSION=2.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 0 && has_text "SKIP" && has_text "graphviz=2.0.0" && has_text "graphviz=1.0.0" && lacks_text "out of date"
}
check "TASK-35#1 다른 환경(graphviz 버전)에서 좌표만 다르면 바이트 비교를 건너뛰고 0" t_dg_other_env_coords
t_dg_other_env_text() {
  dg_reset
  printf '// alt: 그림 A\ndigraph G { a [label="changed"]; }\n' >"$DG/r/diagrams/a.dot"
  run_capture env FAKE_DOT_VERSION=2.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "docs/diagrams/a.svg" && has_text "changed"
}
check "TASK-35#1 다른 환경이어도 글자(.dot 의 라벨)가 다르면 1 + 파일 목록" t_dg_other_env_text
t_dg_other_env_doc() {
  dg_reset
  printf '# t\n\n<!--dot:a-->\nold\n<!--/dot:a-->\n' >"$DG/r/README.md"
  run_capture env FAKE_DOT_VERSION=2.0.0 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 1 && has_text "README.md"
}
check "TASK-35#1 다른 환경이어도 문서 블록이 다르면 1" t_dg_other_env_doc
t_dg_no_pin() {
  dg_reset
  rm -f "$DG/r/diagrams/render-env.txt"
  run_capture env FAKE_DOT_VERSION=1.0.0 FAKE_DOT_X=11 PATH="$DG/bin:$PATH" python3 "$DG/r/scripts/build-diagrams.py" --check
  rc_is 0 && has_text "SKIP" && has_text "render-env.txt"
}
check "TASK-35#1 고정값 파일이 없으면 바이트 비교를 건너뛴다(안내)" t_dg_no_pin
check "TASK-35#1 리포에 렌더 환경 고정값(diagrams/render-env.txt: graphviz·os)" sh -c 'grep -Eq "^graphviz=[0-9]+(\.[0-9]+)+$" diagrams/render-env.txt && grep -Eq "^os=[A-Za-z]+$" diagrams/render-env.txt'

echo "tools: $passes passed, $failures failed"
[ "$failures" -eq 0 ]
