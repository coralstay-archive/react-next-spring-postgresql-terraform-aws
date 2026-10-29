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
t_ci_install_exact_version() {
  # v0.10.47 이 v0.10.470 같은 다른 버전에 부분 일치하지 않는다 — 다르면 다시 받는다
  fake_reset; rm -rf "$TMP/dest"; mkdir -p "$TMP/dest"
  printf '#!/bin/sh\necho "Infracost v%s0"\n' "$(iver $C)" >"$TMP/dest/infracost"; chmod +x "$TMP/dest/infracost"
  ci_install env FAKE_ARCH=x86_64
  lacks_text "건너뛴다" && grep -q '^curl ' "$FAKE_STATE/calls.log"
}
check "TASK-66#1 CI 설치: 버전 문자열이 정확히 같을 때만 건너뛴다(v…470 은 다시 받는다)" t_ci_install_exact_version
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
USAGE_MAIN=infracost-usage-main.yml
yqv() { yq -r "$1" "$2"; }
check "TASK-66#2 $CFG·$USAGE·$USAGE_MAIN 존재" sh -c "test -f $CFG && test -f $USAGE && test -f $USAGE_MAIN"
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
    done
    # usage 파일은 프로젝트마다 따로 — aws_data_transfer 같은 사용량 전용 리소스는 usage 파일을 쓰는 프로젝트마다 더해진다(이중 계산 방지)
    u="$(yqv '.projects[] | select(.name == "platform") | .usage_file' $CFG)"; [ "$u" = "$USAGE" ] || { echo "platform usage_file: $u"; return 1; }
    u="$(yqv '.projects[] | select(.name == "main") | .usage_file' $CFG)"; [ "$u" = "$USAGE_MAIN" ] || { echo "main usage_file: $u"; return 1; }
  }
  check "TASK-66#2 $CFG: platform(infra/platform)·비교용 main(infra/main), envs/prod.tfvars, usage 파일은 프로젝트마다 따로" t_cfg_projects
  t_usage_main() {
    [ "$(yqv '.version' $USAGE_MAIN)" = 0.1 ] || return 1
    [ "$(yqv '.resource_usage."module.backend_host.aws_instance.this".monthly_hrs' $USAGE_MAIN)" = 730 ] || { echo "main 노드 730h 아님"; return 1; }
    [ "$(yqv '[.resource_usage | keys[] | select(test("^module\\.k3s_node\\.|^aws_data_transfer\\."))] | length' $USAGE_MAIN)" = 0 ] || { echo "main usage 에 platform·데이터 전송 항목"; return 1; }
    [ "$(yqv '[.resource_usage | keys[] | select(test("^module\\.backend_host\\."))] | length' $USAGE)" = 0 ] || { echo "platform usage 에 레거시 항목"; return 1; }
  }
  check "TASK-66#2 $USAGE_MAIN: 레거시 노드(730h)만, 데이터 전송·platform 항목은 platform usage 에만" t_usage_main
  check "TASK-66#2 $USAGE: Infracost 는 AWS 무료 구간을 빼지 않는다 — '무료 구간' 이라 쓰지 않는다" sh -c "! grep -q '무료 구간' $USAGE"
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
for w in "infracost.yml" "infracost-usage.yml" "infracost-usage-main.yml" "v0.10.47" "v2" "spot_price" "monthly_hrs" "DLM" "TASK-52"; do
  check "TASK-66#2 README '비용 추정 (Infracost)' 절에 '$w'" sh -c "$(declare -f readme_cost); readme_cost | grep -qF -- '$w'"
done

# ================================================================ TASK-66#4 TASK-66#B1 cost-guard: totalMonthlyCost 상한(기본 $30) — 샘플 JSON(오프라인)
G=scripts/cost-guard.sh
FX=scripts/test/fixtures/infracost
guard() { run_capture env -u COST_LIMIT_USD "$@"; }
check "TASK-66#4 $G 는 실행 가능하고 bash 문법이 맞다" sh -c "test -x $G && bash -n $G"
t_guard_under() { guard "$G" "$FX/breakdown-under.json"; rc_is 0 && has_text '$27.23' && has_text '$30'; }
check "TASK-66#4 상한 아래(\$27.23 ≤ 기본 \$30)면 통과하고 금액·상한을 출력" t_guard_under
t_guard_over() { guard "$G" "$FX/breakdown-over.json"; rc_is 1 && has_text '$53.44' && has_text '초과'; }
check "TASK-66#B1 월 추정 비용이 상한(기본 \$30)을 넘으면 실패(\$53.44)" t_guard_over
t_guard_exact() { guard "$G" "$FX/breakdown-exact.json"; rc_is 0; }
check "TASK-66#B1 상한과 같으면(\$30) 넘지 않았으니 통과" t_guard_exact
t_guard_arg() { guard "$G" "$FX/breakdown-under.json" 20; rc_is 1 && has_text '$20'; }
check "TASK-66#B1 상한을 인자로 조정(20 → \$27.23 실패)" t_guard_arg
t_guard_env() {
  run_capture env COST_LIMIT_USD=25.5 "$G" "$FX/breakdown-under.json"; rc_is 1 || return 1
  run_capture env COST_LIMIT_USD=25.5 "$G" "$FX/breakdown-under.json" 60; rc_is 0
}
check "TASK-66#B1 상한을 변수 COST_LIMIT_USD 로 조정(인자가 우선)" t_guard_env
t_guard_project() {
  guard "$G" "$FX/breakdown-two-projects.json"; rc_is 1 || return 1
  guard "$G" --project platform "$FX/breakdown-two-projects.json"; rc_is 0 && has_text 'platform' || return 1
  guard "$G" --project main "$FX/breakdown-two-projects.json" 20; rc_is 1
}
check "TASK-66#4 --project <이름>: 그 프로젝트의 totalMonthlyCost(없으면 전체 합)" t_guard_project
t_guard_diff() { guard "$G" --project platform "$FX/diff-two-projects.json"; rc_is 1 && has_text '$31.50'; }
check "TASK-66#B1 infracost diff JSON 도 같은 키(새 breakdown)로 검사 — \$31.50 실패" t_guard_diff
t_guard_bad_input() {
  local f
  for f in "$FX/breakdown-broken.json" "$FX/breakdown-null.json" "$TMP/nope.json"; do
    guard "$G" "$f"; rc_is 2 || { echo "($f)"; return 1; }
  done
  guard "$G" --project nope "$FX/breakdown-two-projects.json"; rc_is 2 || return 1
  guard "$G" "$FX/breakdown-under.json" abc; rc_is 2 || return 1
  guard "$G"; rc_is 2
}
check "TASK-66#4 형식 오류(깨진 JSON·null·파일 없음·모르는 프로젝트·상한이 숫자 아님·인자 없음)는 실패(2)" t_guard_bad_input
check "TASK-66#4 scripts/test.sh 가 cost_test.sh 를 부른다(make test, 오프라인)" grep -q 'scripts/test/cost_test.sh' scripts/test.sh

# ================================================================ TASK-66#3 TASK-66#B2 make cost·cost-diff (scripts/cost.sh) — 맥이면 VM 안, 키 없으면 경고 후 건너뜀
CS=scripts/cost.sh
# 가짜 infracost: 호출(인자·작업 디렉터리)을 기록. configure get api_key 는 FAKE_IC_KEY, breakdown·diff 는 --out-file 에
# FAKE_BREAKDOWN·FAKE_DIFF 를 복사한다. breakdown 은 설정의 프로젝트 path·usage 파일이 없으면 실패한다(진짜처럼)
IC="$FAKE/ic"
mkdir -p "$IC/bin"
cat >"$IC/bin/infracost" <<'SH'
#!/usr/bin/env bash
printf 'infracost %s | cwd=%s\n' "$*" "$PWD" >>"$FAKE_STATE/calls.log"
out=""; a=("$@")
for ((i = 0; i < ${#a[@]}; i++)); do [ "${a[$i]}" = --out-file ] && out="${a[$((i + 1))]}"; done
case "$1" in
  configure) [ "$2 $3" = "get api_key" ] && printf '%s' "${FAKE_IC_KEY:-}"; exit 0 ;;
  breakdown)
    # 진짜 infracost 처럼: 설정의 프로젝트 path·usage 파일이 없으면 실패한다. 있으면 프로젝트 이름을 남긴다
    cfg=""; for ((i = 0; i < ${#a[@]}; i++)); do [ "${a[$i]}" = --config-file ] && cfg="${a[$((i + 1))]}"; done
    if [ -n "$cfg" ]; then
      while IFS=$'\t' read -r n p u; do
        [ -d "$p" ] || { echo "Error: project $n: path $p does not exist" >&2; exit 1; }
        [ -z "$u" ] || [ -f "$u" ] || { echo "Error: usage file $u does not exist" >&2; exit 1; }
      done < <(yq -r '.projects[] | [.name, .path, (.usage_file // "")] | @tsv' "$cfg")
      echo "projects $(yq -r '[.projects[].name] | join(",")' "$cfg") | cwd=$PWD" >>"$FAKE_STATE/calls.log"
    fi
    cp "$FAKE_BREAKDOWN" "$out" ;;
  diff) cp "$FAKE_DIFF" "$out" ;;
  output) echo "FAKE-TABLE $*" ;;
esac
SH
chmod +x "$IC/bin/infracost"
# INFRACOST_API_KEY 는 지운다(러너·셸에 있어도 테스트가 키 없는 경로를 본다). 리눅스 경로(VM 안·CI)로 돌린다
cost_run() {
  fake_reset
  run_capture env -u INFRACOST_API_KEY -u COST_LIMIT_USD VM_HOST_OS=Linux PATH="$IC/bin:$PATH" \
    FAKE_BREAKDOWN="${FAKE_BREAKDOWN:-$ROOT/$FX/breakdown-under.json}" FAKE_DIFF="${FAKE_DIFF:-$ROOT/$FX/diff-two-projects.json}" "$@"
}
check "TASK-66#3 $CS 는 실행 가능하고 bash 문법이 맞다" sh -c "test -x $CS && bash -n $CS"
t_cost_no_key() {
  cost_run "$CS" breakdown
  rc_is 0 && has_text "WARNING" && has_text "infracost configure set api_key" && has_text "건너뛴다" || return 1
  ! grep -q '^infracost breakdown' "$FAKE_STATE/calls.log" || { echo "키 없이 breakdown 을 불렀다"; return 1; }
  cost_run "$CS" diff HEAD
  rc_is 0 && has_text "건너뛴다" && ! grep -q '^infracost \(breakdown\|diff\)' "$FAKE_STATE/calls.log"
}
check "TASK-66#B2 API 키가 없으면(환경변수·infracost configure 둘 다) 경고와 등록 방법을 내고 0 으로 건너뛴다(breakdown·diff 안 부름)" t_cost_no_key
t_cost_no_cli() {
  cost_run env INFRACOST=/nonexistent/infracost "$CS" breakdown
  rc_is 0 && has_text "WARNING" && has_text "infracost" && has_text "건너뛴다"
}
check "TASK-66#B2 infracost 가 없어도 경고 후 0 으로 건너뛴다" t_cost_no_cli
t_cost_key_not_printed() {
  cost_run env FAKE_IC_KEY=fake-saved-key-value "$CS" breakdown
  rc_is 0 && lacks_text "fake-saved-key-value" || return 1
  cost_run env INFRACOST_API_KEY=fake-env-key-value "$CS" breakdown
  rc_is 0 && lacks_text "fake-env-key-value"
}
check "TASK-66#B2 키(저장된 값·환경변수)를 출력하지 않는다" t_cost_key_not_printed
t_cost_no_key_in_repo() {
  # 저장소(추적 파일)·example.env 에 키가 없다(Infracost 키는 ico- 로 시작). cost.sh 는 키를 저장하지 않는다(configure set 을 부르지 않는다)
  ! git grep -nIE 'ico-[A-Za-z0-9]{20,}' -- . ':!scripts/test/cost_test.sh' || return 1
  ! grep -qE '^#? ?INFRACOST_API_KEY=' example.env || return 1
  ! grep -qE '"\$IC" +configure +set' $CS || return 1
  cost_run env FAKE_IC_KEY=k "$CS" breakdown
  rc_is 0 && ! grep -q '^infracost configure set' "$FAKE_STATE/calls.log"
}
check "TASK-66#B2 API 키는 저장소·example.env 에 없고, cost.sh 는 키를 저장하지 않는다" t_cost_no_key_in_repo
t_cost_breakdown() {
  cost_run env FAKE_IC_KEY=k "$CS" breakdown || true
  rc_is 0 || return 1
  grep -q "^infracost breakdown --config-file infracost.yml --format json --out-file .* | cwd=$ROOT\$" "$FAKE_STATE/calls.log" || { cat "$FAKE_STATE/calls.log"; return 1; }
  has_text "FAKE-TABLE" && has_text '$27.23' && has_text "통과"
}
check "TASK-66#3 make cost: 저장소 루트에서 infracost.yml 로 breakdown → 표 → cost-guard(platform, 통과)" t_cost_breakdown
t_cost_breakdown_over() {
  FAKE_BREAKDOWN="$ROOT/$FX/breakdown-over.json" cost_run env INFRACOST_API_KEY=k "$CS" breakdown
  rc_is 1 && has_text "초과" || return 1
  cost_run env INFRACOST_API_KEY=k "$CS" breakdown 20
  rc_is 1 && has_text '$20'
}
check "TASK-66#3 make cost: 상한을 넘으면 실패(B1), 상한 인자(make cost COST_LIMIT=20)" t_cost_breakdown_over
t_cost_out_dir() {
  rm -rf "$TMP/out"
  cost_run env INFRACOST_API_KEY=k COST_OUT_DIR="$TMP/out" "$CS" breakdown
  rc_is 0 && [ -s "$TMP/out/breakdown.json" ]
}
check "TASK-66#3 COST_OUT_DIR 가 있으면 JSON 을 거기 남긴다(CI 가 쓴다)" t_cost_out_dir
# 임시 git 저장소(얕은 CI 체크아웃에서도 돈다): c0 = infra 없음, c1 = infra/main 만(오늘의 main 처럼 infracost.yml·infra/platform 없음),
# c2 = 지금 설정 + infra/platform. cost.sh·_vm.sh·cost-guard.sh 는 복사본
GR="$TMP/gitrepo"
mkdir -p "$GR/scripts"
cp scripts/cost.sh scripts/_vm.sh scripts/cost-guard.sh "$GR/scripts/"
gr_git() { git -C "$GR" -c user.email=t@example.invalid -c user.name=t -c commit.gpgsign=false "$@"; }
gr_git init -q -b work
echo r >"$GR/README"; gr_git add -A; gr_git commit -q -m c0; GR_C0="$(gr_git rev-parse HEAD)"
mkdir -p "$GR/infra/main/envs"; echo '# main' >"$GR/infra/main/main.tf"; echo 'x = 1' >"$GR/infra/main/envs/prod.tfvars"
gr_git add -A; gr_git commit -q -m c1; GR_C1="$(gr_git rev-parse HEAD)"
mkdir -p "$GR/infra/platform/envs"; echo '# platform' >"$GR/infra/platform/main.tf"; echo 'y = 1' >"$GR/infra/platform/envs/prod.tfvars"
cp infracost.yml infracost-usage.yml infracost-usage-main.yml "$GR/"
gr_git add -A; gr_git commit -q -m c2
t_cost_diff() {
  rm -rf "$TMP/out"
  # 기준 c1: 설정 파일이 없던 ref → 지금 설정을 쓰되, 기준 트리에 없는 프로젝트(platform)는 빼고 main 만 breakdown
  cost_run env INFRACOST_API_KEY=k COST_OUT_DIR="$TMP/out" "$GR/scripts/cost.sh" diff "$GR_C1"
  rc_is 0 || return 1
  grep -q '^projects main | cwd=' "$FAKE_STATE/calls.log" || { echo "기준 breakdown 이 main 만이 아니다"; cat "$FAKE_STATE/calls.log"; return 1; }
  has_text "infra/platform" && has_text "0" || return 1
  grep -q "^infracost diff --config-file infracost.yml --compare-to $TMP/out/base.json --format json --out-file $TMP/out/diff.json | cwd=$GR\$" "$FAKE_STATE/calls.log" \
    || { cat "$FAKE_STATE/calls.log"; return 1; }
  has_text "FAKE-TABLE output --path $TMP/out/diff.json --format diff" && [ -s "$TMP/out/base.json" ] && [ -s "$TMP/out/diff.json" ]
}
check "TASK-66#3 make cost-diff: 기준 ref 에 없는 프로젝트(infra/platform — 오늘의 main)는 빼고 breakdown, 지금과 infracost diff" t_cost_diff
t_cost_diff_no_base_projects() {
  rm -rf "$TMP/out"
  # 기준 c0: infra 가 아예 없다 → 기준 breakdown 없이 비용 0 인 base.json 으로 비교
  cost_run env INFRACOST_API_KEY=k COST_OUT_DIR="$TMP/out" "$GR/scripts/cost.sh" diff "$GR_C0"
  rc_is 0 && has_text "기준 비용 0" || return 1
  ! grep -q "^infracost breakdown " "$FAKE_STATE/calls.log" || { echo "기준 breakdown 을 불렀다"; return 1; }
  [ "$(jq -r '.totalMonthlyCost' "$TMP/out/base.json")" = 0 ] && [ "$(jq -r '.projects | length' "$TMP/out/base.json")" = 0 ] \
    && grep -q '^infracost diff ' "$FAKE_STATE/calls.log"
}
check "TASK-66#3 make cost-diff: 기준 ref 에 프로젝트가 하나도 없으면 기준 비용 0(빈 base.json)으로 비교" t_cost_diff_no_base_projects
t_cost_diff_bad_ref() {
  cost_run env INFRACOST_API_KEY=k "$CS" diff no-such-ref-66
  [ "$RC" != 0 ] && has_text "no-such-ref-66" && ! grep -q '^infracost breakdown' "$FAKE_STATE/calls.log"
}
check "TASK-66#3 make cost-diff: 없는 기준 ref 는 실패" t_cost_diff_bad_ref
t_cost_bad_cmd() { cost_run "$CS" nope; [ "$RC" = 2 ] && has_text "breakdown" && has_text "diff"; }
check "TASK-66#3 모르는 하위 명령은 사용법과 함께 실패(2)" t_cost_bad_cmd
t_cost_mac_proxy() {
  # 맥(VM_HOST_OS=Darwin)이면 VM 으로 보낸다: 가짜 utmctl 이 꺼짐을 알리면 켜지 않고 make vm-start 를 안내하며 실패, infracost 는 안 부른다
  printf '#!/usr/bin/env bash\n[ "$1" = status ] && echo stopped\n' >"$IC/utmctl"; chmod +x "$IC/utmctl"
  fake_reset
  run_capture env -u INFRACOST_API_KEY VM_HOST_OS=Darwin UTMCTL="$IC/utmctl" PATH="$IC/bin:$PATH" "$CS" breakdown
  [ "$RC" != 0 ] && has_text "make vm-start" && [ ! -s "$FAKE_STATE/calls.log" ] && grep -q 'vm_proxy_if_mac' $CS
}
check "TASK-66#3 맥에서는 VM 안에서 돈다(vm_proxy_if_mac — 꺼져 있으면 make vm-start 안내 후 실패)" t_cost_mac_proxy
t_cost_make() {
  # 출력을 먼저 받는다(pipefail 에서 grep -q 가 일찍 끝나면 make 가 SIGPIPE 로 실패한다)
  local a b c d e f h
  a="$(env -u COST_LIMIT_USD -u COST_LIMIT make -n cost)"; b="$(env -u COST_LIMIT_USD make -n cost COST_LIMIT=25)"
  e="$(env COST_LIMIT_USD=22 make -n cost)"; f="$(env COST_LIMIT_USD=22 make -n cost COST_LIMIT=25)"
  c="$(make -n cost-diff)"; d="$(make -n cost-diff COST_BASE=dev)"; h="$(make help)"
  grep -qx '\./scripts/cost\.sh breakdown *' <<<"$a" || { echo "상한 없이 불러야 한다(cost-guard 기본 30): $a"; return 1; }
  grep -q 'scripts/cost.sh breakdown 25' <<<"$b" && grep -q 'scripts/cost.sh breakdown 22' <<<"$e" && grep -q 'scripts/cost.sh breakdown 25' <<<"$f" \
    && grep -q 'scripts/cost.sh diff main' <<<"$c" && grep -q 'scripts/cost.sh diff dev' <<<"$d" \
    && grep -q '^  cost ' <<<"$h" && grep -q '^  cost-diff ' <<<"$h"
}
check "TASK-66#3 make cost: 상한 COST_LIMIT > COST_LIMIT_USD(맥에서도 make 가 인자로 넘긴다) > 기본 30, make cost-diff(COST_BASE, 기본 main) + help" t_cost_make
t_cost_mac_key_note() {
  # 맥 셸의 INFRACOST_API_KEY 는 VM 으로 넘기지 않는다(ssh 인자·환경에 키를 싣지 않는다) — 그렇다고 알리고, 키 값은 출력하지 않는다
  fake_reset
  run_capture env INFRACOST_API_KEY=fake-mac-key-value VM_HOST_OS=Darwin UTMCTL="$IC/utmctl" PATH="$IC/bin:$PATH" "$CS" breakdown
  has_text "넘기지 않는다" && has_text "infracost configure set api_key" && lacks_text "fake-mac-key-value" || return 1
  cost_run "$CS" breakdown
  has_text "맥 셸의 INFRACOST_API_KEY"
}
check "TASK-66#B2 맥 셸의 INFRACOST_API_KEY 는 VM 으로 넘기지 않는다고 알린다(키 없음 경고에도)" t_cost_mac_key_note
readme_cost_section() { awk '/^### 비용 추정 \(Infracost/{f=1; print; next} f && /^##/{exit} f' README.md; }
for w in "make cost" "make cost-diff" "infracost configure set api_key" "INFRACOST_API_KEY" "ssh rocky-dev" "건너뛴다"; do
  check "TASK-66#3 README '비용 추정 (Infracost)' 절에 '$w'" sh -c "$(declare -f readme_cost_section); readme_cost_section | grep -qF -- '$w'"
done

# ================================================================ TASK-66#5 CI: infra 변경 PR 에 비용 표·차이 코멘트 + cost-guard (secret 없으면 건너뜀)
W=.github/workflows/cost.yml
check "TASK-66#5 $W 존재" test -f "$W"
if need_linter actionlint; then
  check "TASK-66#5 actionlint $W" actionlint -shellcheck "$(command -v shellcheck || true)" "$W"
fi
if need_linter yq; then
  t_wf_trigger() {
    local p
    [ "$(yq -r '.on | keys | join(",")' $W)" = pull_request ] || { echo "트리거는 pull_request 만"; return 1; }
    for p in 'infra/**' infracost.yml infracost-usage.yml infracost-usage-main.yml scripts/cost.sh scripts/cost-guard.sh scripts/ci/install-infracost.sh .github/workflows/cost.yml; do
      yq -e ".on.pull_request.paths[] | select(. == \"$p\")" $W >/dev/null || { echo "paths 에 $p 없음"; return 1; }
    done
  }
  check "TASK-66#5 트리거: infra·비용 설정·스크립트를 바꾼 PR 만(paths)" t_wf_trigger
  t_wf_perms() {
    [ "$(yq -r '.permissions.contents' $W)" = read ] && [ "$(yq -r '.permissions["pull-requests"]' $W)" = write ] \
      && [ "$(yq -r '.permissions | keys | length' $W)" = 2 ] && [ "$(yq -r '[.jobs[] | select(has("permissions"))] | length' $W)" = 0 ]
  }
  check "TASK-66#5 권한 최소: contents: read + pull-requests: write 만" t_wf_perms
  t_wf_secret_skip() {
    local j='.jobs.cost'
    # 키는 쓰는 단계(키 확인·cost diff·PR 코멘트)에만 env 로 준다 — job·워크플로 env 나 다른 단계(checkout·설치·guard)에는 없다
    [ "$(yq -r '[.env, .jobs[].env] | map(select(. != null) | has("INFRACOST_API_KEY")) | any' $W)" = false ] || { echo "job·워크플로 env 에 키"; return 1; }
    [ "$(yq -r "[$j.steps[] | select(.env.INFRACOST_API_KEY == \"\${{ secrets.INFRACOST_API_KEY }}\") | .name] | join(\",\")" $W)" \
      = "Check Infracost API key,Cost diff (base branch → PR),Comment on PR" ] || { echo "키를 받는 단계가 다르다"; yq -r "$j.steps[] | .name + \" \" + (.env.INFRACOST_API_KEY // \"-\")" $W; return 1; }
    # 키 확인 단계(id: key)가 맨 앞이고, 나머지 단계는 모두 steps.key.outputs.present == 'true' 일 때만
    [ "$(yq -r "$j.steps[0].id" $W)" = key ] || { echo "첫 단계가 키 확인이 아니다"; return 1; }
    yq -r "$j.steps[0].run" $W | grep -q '::warning' || { echo "키가 없을 때 경고가 없다"; return 1; }
    [ "$(yq -r "[$j.steps[1:][] | select(.if != \"steps.key.outputs.present == 'true'\")] | length" $W)" = 0 ] || { echo "조건 없는 단계가 있다"; return 1; }
  }
  check "TASK-66#B2 CI: secret INFRACOST_API_KEY 가 없으면 경고 후 나머지 단계를 건너뛴다" t_wf_secret_skip
  t_wf_steps() {
    local runs; runs="$(yq -r '.jobs.cost.steps[] | (.uses // "") + " " + (.run // "")' $W)"
    grep -q 'actions/checkout@v7' <<<"$runs" && [ "$(yq -r '.jobs.cost.steps[] | select(.uses == "actions/checkout@v7") | .with["fetch-depth"]' $W)" = 0 ] \
      || { echo "checkout(fetch-depth 0) 없음"; return 1; }
    grep -q 'scripts/ci/install-infracost.sh "$RUNNER_TEMP/bin"' <<<"$runs" || { echo "고정 설치 스크립트 안 씀"; return 1; }
    grep -q 'scripts/cost.sh diff "origin/$BASE_REF"' <<<"$runs" || { echo "cost.sh diff origin/\$BASE_REF 없음"; return 1; }
    if ! grep -q 'infracost comment github --path "$COST_OUT_DIR/diff.json"' <<<"$runs" || ! grep -q -- '--behavior update' <<<"$runs" \
      || ! grep -q -- '--pull-request "$PR_NUMBER"' <<<"$runs"; then echo "PR 코멘트 없음"; return 1; fi
    grep -q 'scripts/cost-guard.sh --project platform "$COST_OUT_DIR/diff.json"' <<<"$runs" || { echo "cost-guard 없음"; return 1; }
    # 코멘트를 먼저 남기고 상한을 검사한다(초과해도 PR 에 표가 남게)
    [ "$(grep -n 'infracost comment' <<<"$runs" | cut -d: -f1)" -lt "$(grep -n 'cost-guard.sh' <<<"$runs" | cut -d: -f1)" ]
  }
  check "TASK-66#5 단계: checkout(전체 이력) → 고정 설치 → cost.sh diff(origin/기준) → PR 코멘트(update) → cost-guard(platform)" t_wf_steps
fi
t_wf_no_expr_in_run() { local r; r="$(yq -r '.jobs[].steps[].run // ""' $W)" || return 1; ! grep -nF '${{' <<<"$r"; }
if command -v yq >/dev/null 2>&1; then
  check "TASK-66#5 run 에 \${{ }} 를 직접 넣지 않는다(env 로 넘긴다 — 주입 방지)" t_wf_no_expr_in_run
fi
for w in ".github/workflows/cost.yml" "secret" "PR 코멘트"; do
  check "TASK-66#5 README '비용 추정 (Infracost)' 절에 '$w'" sh -c "$(declare -f readme_cost_section); readme_cost_section | grep -qF -- '$w'"
done

echo "cost tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
