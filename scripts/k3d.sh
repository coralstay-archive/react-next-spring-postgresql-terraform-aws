#!/usr/bin/env bash
# 로컬 k3d 플랫폼 (TASK-36). make k3d-* 가 부른다. 클러스터를 띄우거나 쓰는 하위 명령은 모두 먼저 k3d_preflight(scripts/_k3d.sh)를
# 지난다 — 검사를 우회하는 진입점이 없다(TASK-36#8).
#   up               [PROFILES=core] 사전 검사 → 백엔드 이미지 빌드(SKIP_BUILD=1 이면 생략) → 클러스터 생성/시작(deploy/k8s/k3d/cluster.yaml)
#                    → 로컬 레지스트리 push → base·Secret 적용 → 프로필 적용(고르지 않은 프로필은 삭제) → Job 완료·롤아웃 대기
#                    (Job 이 Failed 면 시간 초과를 기다리지 않고 kubectl logs 와 함께 바로 실패)
#   profile          PROFILES=core,... 떠 있는 클러스터에서 프로필만 바꾼다(끈 프로필은 delete -k → 메모리 회수, TASK-36#4)
#   smoke            켜진 프로필로 사전 검사 → http://127.0.0.1:<K3D_HTTP_PORT>/api/actuator/health (Host: board.localhost)가 UP
#   stop             클러스터·레지스트리 컨테이너 정지(데이터 유지, 메모리 회수). 다시: up
#   down             클러스터 삭제(PVC 데이터까지)
#   stop-if-running <누가>  떠 있으면 멈추고 출력(make test·test-integration·backend-test·backend-build·e2e 시작 시, TASK-36#6). k3d 가 없으면 docker 라벨로 찾아 docker stop
# 환경변수: PROFILES, FORCE=1(예산 초과만 통과), SKIP_BUILD=1, K3D_WAIT_TIMEOUT(기본 300s), SMOKE_TIMEOUT(초, 기본 60),
#   K3D_HTTP_PORT(기본 80 — cluster.yaml 의 포트와 같게), Secret 값: POSTGRES_DB·POSTGRES_USER·POSTGRES_PASSWORD·MINIO_ROOT_USER·
#   MINIO_ROOT_PASSWORD·JWT_SECRET·OTP_HASH_KEY(make 가 env 파일에서 읽어 넘긴다. 없으면 docker-compose 와 같은 로컬 기본값).
#   UPLOADS_BUCKET 등 비밀이 아닌 설정은 env 파일에서 읽지 않고 ConfigMap board-backend-config(deploy/k8s/profiles/core/kustomization.yaml)에 고정이다.
set -euo pipefail

# shellcheck source=scripts/_k3d.sh
source "$(dirname "${BASH_SOURCE[0]}")/_k3d.sh"

K8S_DIR="$K3D_ROOT/deploy/k8s"
CLUSTER_CONFIG="$K8S_DIR/k3d/cluster.yaml"
# 호스트에서는 localhost:5001 로 push 하고, 클러스터 안에서는 k3d-registry.localhost:5001 로 pull 한다(같은 레지스트리 컨테이너)
BACKEND_IMAGE_PUSH="localhost:5001/board-backend:dev"
WAIT_TIMEOUT="${K3D_WAIT_TIMEOUT:-300s}"
HTTP_PORT="${K3D_HTTP_PORT:-80}"

require_tools() {
  local t
  for t in "$@"; do command -v "$t" >/dev/null 2>&1 || k3d_die "$t 가 없다(README 로컬 k8s 의 준비물 참고)"; done
}

selected_profiles() {
  local list
  list="$(k3d_parse_profiles "${PROFILES:-${1:-}}")"
  printf '%s' "$list"
}

build_images() {
  if [ "${SKIP_BUILD:-0}" = 1 ]; then k3d_log "SKIP_BUILD=1 — 이미지 빌드 생략"; return 0; fi
  k3d_log "백엔드 이미지 빌드 ($BACKEND_IMAGE_PUSH, 호스트 아키텍처 — M1 은 arm64)"
  docker build -t "$BACKEND_IMAGE_PUSH" "$K3D_ROOT/backend"
}

push_images() {
  if [ "${SKIP_BUILD:-0}" = 1 ]; then return 0; fi
  k3d_log "로컬 레지스트리로 push ($BACKEND_IMAGE_PUSH)"
  docker push "$BACKEND_IMAGE_PUSH"
}

ensure_cluster() {
  case "$(k3d_cluster_state)" in
    absent)
      k3d_log "클러스터 '$K3D_CLUSTER' 생성 ($CLUSTER_CONFIG)"
      k3d cluster create --config "$CLUSTER_CONFIG" ;;
    stopped)
      k3d_log "멈춘 클러스터 '$K3D_CLUSTER' 시작"
      docker start "$K3D_REGISTRY_CONTAINER" >/dev/null 2>&1 || true
      k3d cluster start "$K3D_CLUSTER" ;;
    running) docker start "$K3D_REGISTRY_CONTAINER" >/dev/null 2>&1 || true ;;
  esac
}

# Secret board-env: env 파일(환경변수) 값으로 만든다. 리포에도 디스크에도 남기지 않는다 — 임시 파일 없이 파이프(stdin)로
# kubectl 에 넘긴다(중간에 실패해도 $TMPDIR 에 평문이 남지 않는다). printf 는 셸 내장이라 값이 프로세스 인자(ps)에 보이지 않는다.
# kubectl 의 env 파일 형식은 한 줄에 KEY=값이고 값은 그대로(따옴표·공백·특수문자 포함) 쓴다 — 줄바꿈만 넣을 수 없으므로 거부한다.
# 값은 출력하지 않는다(오류에도 키 이름만).
apply_secret() {
  local db="${POSTGRES_DB:-board}" user="${POSTGRES_USER:-board}" pass="${POSTGRES_PASSWORD:-board}"
  local mu="${MINIO_ROOT_USER:-minioadmin}" mp="${MINIO_ROOT_PASSWORD:-minioadmin}"
  local lines=(
    "POSTGRES_DB=$db"
    "POSTGRES_USER=$user"
    "POSTGRES_PASSWORD=$pass"
    "DB_URL=jdbc:postgresql://board-postgres:5432/$db"
    "DB_USERNAME=$user"
    "DB_PASSWORD=$pass"
    "MINIO_ROOT_USER=$mu"
    "MINIO_ROOT_PASSWORD=$mp"
    "UPLOADS_ACCESS_KEY=$mu"
    "UPLOADS_SECRET_KEY=$mp"
  )
  # 없으면 넣지 않는다 — 앱의 로컬 개발 기본값(운영 모드 아님: PUBLIC_BASE_URL 이 http)
  [ -z "${JWT_SECRET:-}" ] || lines+=("JWT_SECRET=$JWT_SECRET")
  [ -z "${OTP_HASH_KEY:-}" ] || lines+=("OTP_HASH_KEY=$OTP_HASH_KEY")
  local l
  for l in "${lines[@]}"; do
    case "$l" in *$'\n'* | *$'\r'*) k3d_die "Secret 값에 줄바꿈이 있다: ${l%%=*} — env 파일의 값을 한 줄로 고친다(값은 출력하지 않는다)" ;; esac
  done
  printf '%s\n' "${lines[@]}" \
    | kc -n board create secret generic board-env --from-env-file=/dev/stdin --dry-run=client -o yaml \
    | kc apply -f - >/dev/null
}

profile_namespace() { awk '$1 == "namespace:" { print $2; exit }' "$K3D_PROFILES_DIR/$1/kustomization.yaml" 2>/dev/null || true; }

# "300s"·"5m"·"300" → 초
timeout_seconds() {
  case "$1" in
    *m) echo $(( ${1%m} * 60 )) ;;
    *s) echo "${1%s}" ;;
    *) echo "$1" ;;
  esac
}

# Job 로그(실패·시간 초과 원인)를 보여 준다: <ns>/<이름>...
show_job_logs() {
  local j
  for j in "$@"; do
    echo "--- kubectl -n ${j%%/*} logs job/${j#*/} (마지막 50줄)" >&2
    kc -n "${j%%/*}" logs "job/${j#*/}" --all-containers --tail=50 >&2 || true
  done
}

# 프로필의 Job 이 모두 Complete 가 될 때까지 기다린다. 하나라도 Failed(backoffLimit 소진)면 시간 초과를 기다리지 않고 바로 로그와 함께
# 실패한다(kubectl wait 는 complete 와 failed 를 같이 기다리지 못한다). Job 이 없는 프로필은 바로 통과한다.
wait_jobs() {
  local p="$1" deadline out name conds failed pending
  deadline=$(( $(date +%s) + $(timeout_seconds "$WAIT_TIMEOUT") ))
  while :; do
    out="$(kc get job -A -l "board.dev/profile=$p" \
      -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name}{" "}{range .status.conditions[?(@.status=="True")]}{.type}{" "}{end}{"\n"}{end}')"
    failed=(); pending=()
    while read -r name conds; do
      [ -n "$name" ] || continue
      case " $conds " in
        *" Failed "*) failed+=("$name") ;;
        *" Complete "*) ;;
        *) pending+=("$name") ;;
      esac
    done <<<"$out"
    if [ "${#failed[@]}" -gt 0 ]; then
      show_job_logs "${failed[@]}"
      k3d_die "Job 실패: ${failed[*]} (프로필 $p) — 위 로그를 본다. 고친 뒤 다시: make k3d-up (Job 은 지우고 새로 돈다)"
    fi
    [ "${#pending[@]}" -gt 0 ] || return 0
    if [ "$(date +%s)" -ge "$deadline" ]; then
      show_job_logs "${pending[@]}"
      k3d_die "Job 이 $WAIT_TIMEOUT 안에 끝나지 않았다: ${pending[*]} (프로필 $p) — 위 로그를 본다. 더 기다리려면 K3D_WAIT_TIMEOUT=600s"
    fi
    sleep 2
  done
}

# 고른 프로필은 적용하고 나머지(예산 파일의 프로필)는 지운다 — 지우기를 먼저 해서 메모리를 먼저 돌려받는다
apply_profiles() {
  local selected=" $* " p ns
  for p in $(k3d_known_profiles); do
    [ -d "$K3D_PROFILES_DIR/$p" ] || k3d_die "프로필 디렉터리가 없다: $K3D_PROFILES_DIR/$p"
  done
  for p in $(k3d_known_profiles); do
    case "$selected" in *" $p "*) continue ;; esac
    k3d_log "프로필 $p 끄기 — 리소스 삭제(메모리 회수, PVC 데이터는 남는다)"
    kc delete -k "$K3D_PROFILES_DIR/$p" --ignore-not-found --wait=true
  done
  kc apply -k "$K8S_DIR/base" >/dev/null
  case "$selected" in *" core "*) apply_secret ;; esac
  for p in "$@"; do
    k3d_log "프로필 $p 켜기"
    # Job 은 고칠 수 없다 — 다시 돌릴 Job(board.dev/rerun=true: 마이그레이션 등)은 지우고 새로 만든다(새 이미지로)
    kc delete job -A -l "board.dev/profile=$p,board.dev/rerun=true" --ignore-not-found --wait=true
    kc apply -k "$K3D_PROFILES_DIR/$p"
  done
  for p in "$@"; do
    ns="$(profile_namespace "$p")"
    k3d_log "프로필 $p: Job 완료·롤아웃 대기(최대 $WAIT_TIMEOUT)"
    wait_jobs "$p"
    local w
    for w in $(kc -n "${ns:-default}" get deployment,statefulset -l "board.dev/profile=$p" -o name); do
      kc -n "${ns:-default}" rollout status "$w" --timeout="$WAIT_TIMEOUT"
    done
  done
}

cmd_up() {
  require_tools docker k3d kubectl
  local profiles
  profiles="$(selected_profiles core)"
  # shellcheck disable=SC2086 # 공백으로 나눈 프로필 목록
  k3d_preflight $profiles
  build_images
  local before
  before="$(k3d_cluster_state)"
  ensure_cluster
  push_images
  # shellcheck disable=SC2086
  apply_profiles $profiles
  # 이미 있던 backend 는 새로 push 한 :dev 이미지로 다시 띄운다(imagePullPolicy Always)
  if [ "$before" != absent ] && [ "${SKIP_BUILD:-0}" != 1 ]; then
    case " $profiles " in *" core "*)
      kc -n board rollout restart deployment/board-backend >/dev/null
      kc -n board rollout status deployment/board-backend --timeout="$WAIT_TIMEOUT" ;;
    esac
  fi
  k3d_log "완료: 프로필 [$profiles]. 확인: make k3d-smoke"
}

cmd_profile() {
  require_tools docker k3d kubectl
  local profiles
  profiles="$(selected_profiles)"
  [ -n "$profiles" ] || k3d_die "PROFILES= 가 비었다. 예: make k3d-profile PROFILES=core (전부 끄려면 make k3d-stop)"
  [ "$(k3d_cluster_state)" = running ] || k3d_die "클러스터 '$K3D_CLUSTER' 가 떠 있지 않다. 먼저 make k3d-up"
  # shellcheck disable=SC2086
  k3d_preflight $profiles
  # shellcheck disable=SC2086
  apply_profiles $profiles
  k3d_log "완료: 프로필 [$profiles]"
}

cmd_smoke() {
  require_tools docker k3d kubectl curl
  [ "$(k3d_cluster_state)" = running ] || k3d_die "클러스터 '$K3D_CLUSTER' 가 떠 있지 않다. 먼저 make k3d-up"
  local profiles
  profiles="$(k3d_active_profiles)"
  # shellcheck disable=SC2086
  k3d_preflight $profiles
  local url="http://127.0.0.1:$HTTP_PORT/api/actuator/health" body="" deadline
  deadline=$(( $(date +%s) + ${SMOKE_TIMEOUT:-60} ))
  k3d_log "스모크: $url (Host: board.localhost — k3d LB → Traefik 인그레스 → board-backend)"
  while :; do
    body="$(curl -sS --max-time 5 -H 'Host: board.localhost' "$url" 2>&1 || true)"
    case "$body" in *'"status":"UP"'*) k3d_log "UP: $body"; return 0 ;; esac
    [ "$(date +%s)" -lt "$deadline" ] || break
    sleep 2
  done
  k3d_die "health 가 UP 이 아니다: ${body:-(응답 없음)}"
}

cmd_stop() {
  require_tools k3d
  if [ "$(k3d_cluster_state)" = absent ]; then k3d_log "클러스터 '$K3D_CLUSTER' 없음"; return 0; fi
  k3d cluster stop "$K3D_CLUSTER"
  docker stop "$K3D_REGISTRY_CONTAINER" >/dev/null 2>&1 || true
  k3d_log "멈췄다(데이터 유지, 메모리 회수). 다시: make k3d-up"
}

cmd_down() {
  require_tools k3d
  k3d cluster delete "$K3D_CLUSTER"
}

case "${1:-}" in
  up) cmd_up ;;
  profile) cmd_profile ;;
  smoke) cmd_smoke ;;
  stop) cmd_stop ;;
  down) cmd_down ;;
  stop-if-running) k3d_stop_if_running "${2:-}" ;;
  *) echo "사용: $0 up|profile|smoke|stop|down|stop-if-running <누가>" >&2; exit 2 ;;
esac
