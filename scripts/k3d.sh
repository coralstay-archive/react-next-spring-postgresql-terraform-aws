#!/usr/bin/env bash
# 로컬 k3d 플랫폼 (TASK-36). make k3d-* 가 부른다. 클러스터를 띄우거나 쓰는 하위 명령은 모두 먼저 k3d_preflight(scripts/_k3d.sh)를
# 지난다 — 검사를 우회하는 진입점이 없다(TASK-36#8).
#   up               [PROFILES=core] 사전 검사 → 백엔드 이미지 빌드(SKIP_BUILD=1 이면 생략) → 클러스터 생성/시작(deploy/k8s/k3d/cluster.yaml)
#                    → 로컬 레지스트리 push → base·Secret 적용 → 프로필 적용(고르지 않은 프로필은 삭제) → Job 완료·롤아웃 대기
#   profile          PROFILES=core,... 떠 있는 클러스터에서 프로필만 바꾼다(끈 프로필은 delete -k → 메모리 회수, TASK-36#4)
#   smoke            켜진 프로필로 사전 검사 → http://127.0.0.1:<K3D_HTTP_PORT>/api/actuator/health (Host: board.localhost)가 UP
#   stop             클러스터·레지스트리 컨테이너 정지(데이터 유지, 메모리 회수). 다시: up
#   down             클러스터 삭제(PVC 데이터까지)
#   stop-if-running <누가>  떠 있으면 멈추고 출력(make test·test-integration 시작 시, TASK-36#6). k3d 가 없으면 아무것도 안 한다
# 환경변수: PROFILES, FORCE=1(예산 초과만 통과), SKIP_BUILD=1, K3D_WAIT_TIMEOUT(기본 300s), SMOKE_TIMEOUT(초, 기본 60),
#   K3D_HTTP_PORT(기본 80 — cluster.yaml 의 포트와 같게), Secret 값: POSTGRES_DB·POSTGRES_USER·POSTGRES_PASSWORD·MINIO_ROOT_USER·
#   MINIO_ROOT_PASSWORD·UPLOADS_BUCKET·JWT_SECRET·OTP_HASH_KEY(make 가 env 파일에서 읽어 넘긴다. 없으면 docker-compose 와 같은 로컬 기본값).
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

# Secret board-env: env 파일(환경변수) 값으로 만든다. 리포에 남기지 않는다(임시 파일 → kubectl apply → 삭제).
apply_secret() {
  local db="${POSTGRES_DB:-board}" user="${POSTGRES_USER:-board}" pass="${POSTGRES_PASSWORD:-board}"
  local mu="${MINIO_ROOT_USER:-minioadmin}" mp="${MINIO_ROOT_PASSWORD:-minioadmin}" f
  f="$(mktemp)"
  # shellcheck disable=SC2064 # 지금 경로로 지운다
  trap "rm -f '$f'" RETURN
  chmod 600 "$f"
  {
    echo "POSTGRES_DB=$db"
    echo "POSTGRES_USER=$user"
    echo "POSTGRES_PASSWORD=$pass"
    echo "DB_URL=jdbc:postgresql://board-postgres:5432/$db"
    echo "DB_USERNAME=$user"
    echo "DB_PASSWORD=$pass"
    echo "MINIO_ROOT_USER=$mu"
    echo "MINIO_ROOT_PASSWORD=$mp"
    echo "MC_HOST_local=http://$mu:$mp@board-minio:9000"
    echo "UPLOADS_ACCESS_KEY=$mu"
    echo "UPLOADS_SECRET_KEY=$mp"
    # 없으면 넣지 않는다 — 앱의 로컬 개발 기본값(운영 모드 아님: PUBLIC_BASE_URL 이 http)
    [ -z "${JWT_SECRET:-}" ] || echo "JWT_SECRET=$JWT_SECRET"
    [ -z "${OTP_HASH_KEY:-}" ] || echo "OTP_HASH_KEY=$OTP_HASH_KEY"
  } >"$f"
  kc -n board create secret generic board-env --from-env-file="$f" --dry-run=client -o yaml | kc apply -f - >/dev/null
}

profile_namespace() { awk '$1 == "namespace:" { print $2; exit }' "$K3D_PROFILES_DIR/$1/kustomization.yaml" 2>/dev/null || true; }

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
    kc wait --for=condition=complete job -A -l "board.dev/profile=$p" --timeout="$WAIT_TIMEOUT"
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
