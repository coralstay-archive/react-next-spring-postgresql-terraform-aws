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

# ================================================================ TASK-66#2 infracost.yml(프로젝트) + infracost-usage.yml(사용량·스팟 보정)
# Infracost v0.10.x 는 aws_instance 의 spot_price 가 있을 때만 스팟 가격을 쓴다(instance_market_options 는 보지 않는다 — 소스
# internal/providers/terraform/aws/instance.go). 노드는 instance_market_options 로 스팟이라 온디맨드로 계산된다 → usage 의
# monthly_hrs 를 "온디맨드 환산 시간"(730 × 스팟 상한/온디맨드)으로 보정한다. DLM 스냅샷은 Infracost 가 계산하지 않는다(README).
CFG=infracost.yml
USAGE=infracost-usage.yml
yqv() { yq -r "$1" "$2"; }
check "TASK-66#2 $CFG·$USAGE 존재" sh -c "test -f $CFG && test -f $USAGE"
if need_linter yq; then
  t_cfg_projects() {
    [ "$(yqv '.version' $CFG)" = 0.1 ] || { echo "version 0.1 아님"; return 1; }
    [ "$(yqv '.projects[] | select(.name == "platform") | .path' $CFG)" = infra/platform ] || { echo "platform 프로젝트 없음"; return 1; }
    [ "$(yqv '.projects[] | select(.name == "main") | .path' $CFG)" = infra/main ] || { echo "비교용 main 프로젝트 없음"; return 1; }
    local p v u
    for p in platform main; do
      v="$(yqv ".projects[] | select(.name == \"$p\") | .terraform_var_files[0]" $CFG)"
      [ "$v" = envs/prod.tfvars ] || { echo "$p var file: $v"; return 1; }
      [ -f "$(yqv ".projects[] | select(.name == \"$p\") | .path" $CFG)/$v" ] || { echo "$p: $v 없음"; return 1; }
      u="$(yqv ".projects[] | select(.name == \"$p\") | .usage_file" $CFG)"
      [ "$u" = "$USAGE" ] || { echo "$p usage_file: $u"; return 1; }
    done
  }
  check "TASK-66#2 $CFG: platform(infra/platform)·비교용 main(infra/main), envs/prod.tfvars, 같은 usage 파일" t_cfg_projects
  t_usage_spot() {
    local h a="module.k3s_node.aws_instance.this"
    [ "$(yqv '.version' $USAGE)" = 0.1 ] || { echo "version 0.1 아님"; return 1; }
    h="$(yqv ".resource_usage.\"$a\".monthly_hrs" $USAGE)"
    awk -v h="$h" 'BEGIN { exit !(h + 0 > 0 && h + 0 < 730) }' || { echo "$a monthly_hrs=$h (스팟 보정: 0 < h < 730)"; return 1; }
    [ "$(yqv ".resource_usage.\"$a\".operating_system" $USAGE)" = linux ] || return 1
    # 주소가 실제 리소스: module "k3s_node" → modules/k3s_node 의 aws_instance "this"
    grep -q '^module "k3s_node"' infra/platform/main.tf && grep -q '^resource "aws_instance" "this"' infra/platform/modules/k3s_node/main.tf
  }
  check "TASK-66#2 $USAGE: k3s 노드 monthly_hrs 스팟 보정(< 730), operating_system linux, 실제 리소스 주소" t_usage_spot
  t_usage_spot_formula() {
    # 주석의 산식(730 × 스팟/온디맨드)과 값이 맞는다 — 단가를 바꾸면 값도 바꾼다
    local h s o
    h="$(yqv '.resource_usage."module.k3s_node.aws_instance.this".monthly_hrs' $USAGE)"
    s="$(sed -nE 's/^# *spot_usd_per_hour: *([0-9.]+).*/\1/p' $USAGE)"; o="$(sed -nE 's/^# *on_demand_usd_per_hour: *([0-9.]+).*/\1/p' $USAGE)"
    [ -n "$s" ] && [ -n "$o" ] || { echo "주석에 spot_usd_per_hour·on_demand_usd_per_hour 없음"; return 1; }
    awk -v h="$h" -v s="$s" -v o="$o" 'BEGIN { e = 730 * s / o; d = h - e; if (d < 0) d = -d; exit !(d <= 1) }' \
      || { echo "monthly_hrs=$h ≠ 730×$s/$o"; return 1; }
  }
  check "TASK-66#2 $USAGE: monthly_hrs = 730 × 스팟/온디맨드 단가(주석 값, ±1h)" t_usage_spot_formula
  check "TASK-66#2 $USAGE: 데이터 전송량(aws_data_transfer, us-east-1)" sh -c "
    [ \"\$(yq -r '.resource_usage | to_entries[] | select(.key | test(\"^aws_data_transfer\\\\.\")) | .value.region' $USAGE)\" = us-east-1 ]"
fi
readme_cost() { awk '/^### 비용 추정 \(Infracost/{f=1; print; next} f && /^##/{exit} f' README.md; }
for w in "infracost.yml" "infracost-usage.yml" "spot_price" "monthly_hrs" "DLM" "TASK-52"; do
  check "TASK-66#2 README '비용 추정 (Infracost)' 절에 '$w'" sh -c "$(declare -f readme_cost); readme_cost | grep -qF -- '$w'"
done

echo "cost tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
