#!/usr/bin/env bash
# shellcheck disable=SC2016 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다
# 비용(Infracost, TASK-66) 테스트. scripts/test.sh 가 호출한다.
# 오프라인: Infracost 가격 API·GitHub 는 부르지 않는다(PATH 앞에 둔 가짜 curl·infracost·uname, 샘플 breakdown JSON).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0
OUT=""
RC=0

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
need_linter() {
  if command -v "$1" >/dev/null 2>&1; then return 0; fi
  if [ "${REQUIRE_LINTERS:-0}" = 1 ]; then echo "  FAIL - $1 이 없다(REQUIRE_LINTERS=1)"; failures=$((failures + 1)); else echo "  WARNING: $1 이 없어 건너뛴다" >&2; fi
  return 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAKE="$TMP/fake"
mkdir -p "$FAKE/bin" "$FAKE/state"
export FAKE_STATE="$FAKE/state"
fake_reset() { rm -rf "$FAKE_STATE"; mkdir -p "$FAKE_STATE"; }

echo "cost tests"

# ================================================================ TASK-66#1 infracost CLI 고정 버전 설치
I=deploy/rocky/install.sh
C=scripts/ci/install-infracost.sh
iver() { sed -nE 's/^INFRACOST_VERSION="?([0-9.]+)"?.*/\1/p' "$1" | head -n1; }
check "TASK-66#1 install.sh dev 프로필: INFRACOST_VERSION(0.10.x)·INFRACOST_SHA256 고정" sh -c "
  grep -Eq '^INFRACOST_VERSION=\"?0\.10\.[0-9]+\"?' $I && grep -Eq '^INFRACOST_SHA256=\"?[0-9a-f]{64}\"?' $I"
check "TASK-66#1 install.sh: arm64 tarball 을 fetch(sha256)로 받고 같은 버전이면 건너뛴다(installed/mark)" sh -c "
  grep -q 'infracost-linux-arm64.tar.gz\" \"\$INFRACOST_SHA256\"' $I && grep -q 'installed \"infracost-\$INFRACOST_VERSION\"' $I \
    && grep -q 'mark \"infracost-\$INFRACOST_VERSION\"' $I && grep -q '/usr/local/bin/infracost' $I"
check "TASK-66#1 install.sh: infracost 는 dev 프로필(server 종료 뒤)에만 있다" sh -c "
  s=\$(grep -n 'PROFILE\" = server' $I | head -n1 | cut -d: -f1); i=\$(grep -n 'installed \"infracost-' $I | head -n1 | cut -d: -f1)
  [ -n \"\$s\" ] && [ -n \"\$i\" ] && [ \"\$i\" -gt \"\$s\" ]"
check "TASK-66#1 $C 는 실행 가능하고 bash 문법이 맞다" sh -c "test -x $C && bash -n $C"
check "TASK-66#1 CI 설치 스크립트 버전·arm64 sha256 은 install.sh 와 같다, amd64 sha256 고정" sh -c "
  v1=\$(sed -nE 's/^INFRACOST_VERSION=\"?([0-9.]+)\"?.*/\1/p' $I); v2=\$(sed -nE 's/^INFRACOST_VERSION=\"?([0-9.]+)\"?.*/\1/p' $C)
  a1=\$(sed -nE 's/^INFRACOST_SHA256=\"?([0-9a-f]{64})\"?.*/\1/p' $I); a2=\$(sed -nE 's/^INFRACOST_SHA256_ARM64=\"?([0-9a-f]{64})\"?.*/\1/p' $C)
  [ -n \"\$v1\" ] && [ \"\$v1\" = \"\$v2\" ] && [ -n \"\$a1\" ] && [ \"\$a1\" = \"\$a2\" ] || { echo \"install=\$v1/\$a1 ci=\$v2/\$a2\"; exit 1; }
  grep -Eq '^INFRACOST_SHA256_AMD64=\"?[0-9a-f]{64}\"?' $C && grep -q 'sha256sum -c' $C"

# 가짜 curl: -o <파일> 에 FAKE_TARBALL(없으면 쓰레기)을 쓰고 URL 을 기록한다. 가짜 uname -m 은 FAKE_ARCH
cat >"$FAKE/bin/curl" <<'SH'
#!/usr/bin/env bash
out=""; url=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift 2 ;; -*) shift ;; *) url="$1"; shift ;; esac; done
printf 'curl %s\n' "$url" >>"$FAKE_STATE/calls.log"
if [ -n "${FAKE_TARBALL:-}" ]; then cp "$FAKE_TARBALL" "$out"; else echo garbage >"$out"; fi
SH
cat >"$FAKE/bin/uname" <<'SH'
#!/usr/bin/env bash
if [ "${1:-}" = -m ]; then echo "${FAKE_ARCH:-x86_64}"; else echo Linux; fi
SH
chmod +x "$FAKE/bin/"*
ci_install() { run_capture env PATH="$FAKE/bin:$PATH" "$@" "$C" "$TMP/dest"; }
t_ci_install_bad_sha() {
  fake_reset; rm -rf "$TMP/dest"
  ci_install env FAKE_ARCH=x86_64
  [ "$RC" != 0 ] || { echo "sha256 이 다른데 성공했다"; echo "$OUT"; return 1; }
  has_text "sha256" && [ ! -e "$TMP/dest/infracost" ] \
    && grep -qF "releases/download/v$(iver $C)/infracost-linux-amd64.tar.gz" "$FAKE_STATE/calls.log"
}
check "TASK-66#1 CI 설치: sha256 이 다르면 실패하고 아무것도 설치하지 않는다(amd64 tarball)" t_ci_install_bad_sha
t_ci_install_arm64_url() {
  fake_reset; rm -rf "$TMP/dest"
  ci_install env FAKE_ARCH=aarch64
  [ "$RC" != 0 ] && grep -qF "infracost-linux-arm64.tar.gz" "$FAKE_STATE/calls.log"
}
check "TASK-66#1 CI 설치: aarch64 러너는 arm64 tarball" t_ci_install_arm64_url
t_ci_install_skip_same() {
  fake_reset; rm -rf "$TMP/dest"; mkdir -p "$TMP/dest"
  printf '#!/bin/sh\necho "Infracost v%s"\n' "$(iver $C)" >"$TMP/dest/infracost"; chmod +x "$TMP/dest/infracost"
  ci_install env FAKE_ARCH=x86_64
  rc_is 0 && has_text "건너뛴다" && [ ! -s "$FAKE_STATE/calls.log" ]
}
check "TASK-66#1 CI 설치: 같은 버전이 이미 있으면 받지 않고 건너뛴다" t_ci_install_skip_same
t_ci_install_bad_arch() {
  fake_reset; rm -rf "$TMP/dest"
  ci_install env FAKE_ARCH=riscv64
  [ "$RC" != 0 ] && has_text "riscv64" && [ ! -s "$FAKE_STATE/calls.log" ]
}
check "TASK-66#1 CI 설치: 고정하지 않은 아키텍처는 거부" t_ci_install_bad_arch
if need_linter shellcheck; then
  check "TASK-66#1 shellcheck $C" shellcheck -x "$C"
fi

echo "cost tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
