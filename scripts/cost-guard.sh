#!/usr/bin/env bash
# 월 비용 상한 검사 (TASK-66#4, B1). Infracost breakdown·diff JSON 의 totalMonthlyCost 가 상한을 넘으면 실패한다.
#   scripts/cost-guard.sh [--project <이름>] <JSON> [상한 USD]
#     --project   그 프로젝트(.projects[].name)의 breakdown.totalMonthlyCost. 없으면 전체 합(.totalMonthlyCost)
#     상한        인자 > COST_LIMIT_USD > 기본 30. 같으면 통과(넘을 때만 실패)
# 종료 코드: 0 통과, 1 상한 초과, 2 사용법·형식 오류(파일 없음·깨진 JSON·값 없음). make cost·CI(cost.yml)가 부른다.
set -euo pipefail

die2() { echo "ERROR: [cost-guard] $*" >&2; exit 2; }

project=""
if [ "${1:-}" = --project ]; then
  [ $# -ge 2 ] || die2 "--project 에 이름이 없다"
  project="$2"; shift 2
fi
if [ $# -lt 1 ] || [ $# -gt 2 ]; then die2 "사용: $0 [--project <이름>] <breakdown.json> [상한 USD, 기본 30]"; fi
json="$1"
limit="${2:-${COST_LIMIT_USD:-30}}"
[[ "$limit" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die2 "상한이 숫자가 아니다: $limit"
[ -f "$json" ] || die2 "파일이 없다: $json"

if [ -n "$project" ]; then
  cost="$(jq -r --arg p "$project" '[.projects[]? | select(.name == $p)] | if length == 1 then .[0].breakdown.totalMonthlyCost else "no-project" end' "$json" 2>/dev/null)" \
    || die2 "JSON 을 읽지 못했다: $json"
  [ "$cost" != no-project ] || die2 "프로젝트 '$project' 가 하나가 아니다(없거나 여럿): $json"
  label="$project"
else
  cost="$(jq -r '.totalMonthlyCost' "$json" 2>/dev/null)" || die2 "JSON 을 읽지 못했다: $json"
  label="전체"
fi
[[ "$cost" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die2 "totalMonthlyCost 가 숫자가 아니다($label: $cost): $json"

shown="$(LC_ALL=C printf '%.2f' "$cost")"
if awk -v c="$cost" -v l="$limit" 'BEGIN { exit !(c + 0 > l + 0) }'; then
  echo "ERROR: [cost-guard] $label 월 추정 \$$shown 가 상한 \$$limit 을 초과한다(B1). 줄이거나, 의도한 증가면 상한(COST_LIMIT_USD·인자)을 올린다" >&2
  exit 1
fi
echo "==> [cost-guard] $label 월 추정 \$$shown / 상한 \$$limit — 통과"
