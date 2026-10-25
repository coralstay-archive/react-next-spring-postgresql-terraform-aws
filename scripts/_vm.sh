#!/usr/bin/env bash
# Rocky 개발 VM(UTM, rocky-dev) 공통 함수 (TASK-64). source 해서 쓴다(scripts/vm.sh·test.sh·test-integration.sh·_k3d.sh).
# 맥에는 컨테이너 런타임이 없다(Docker 삭제, doc-4). 컨테이너·k8s·테스트는 UTM 의 Rocky Linux 9 VM 안(Podman)에서 돈다.
#   vm_preflight            사전 검사(B2): VM 이 켜져 있는지(꺼져 있으면 켜지 않고 make vm-start 안내 후 실패), ssh 가 되는지,
#                           VM 메모리(/proc/meminfo) ≥ vm-min, 맥 여유 메모리(vm_stat) < mac-free-min 이면 경고. 맥이 아니면 할 일이 없다.
#   vm_proxy_if_mac <명령>  맥이면 사전 검사 → VM 안 잠금(/tmp/board-repo-test.lock) 확인 → 작업 트리를 VM /repo/<상대 경로> 에
#                           rsync(VirtioFS 공유면 생략) → ssh rocky-dev 로 VM 안에서 <명령>을 돌리고(vm_remote_script: 같은 잠금,
#                           ssh 가 끊기면 남은 원격 명령을 멈춘다) 그 종료 코드로 끝낸다(exit).
#                           상대 경로는 make vm-create 때 기록한 저장소(share-dir) 기준. git worktree(.claude/worktrees/<x>)는 .git 파일에
#                           맥 절대 경로(gitdir:)가 적혀 있어서, VM 에 그 맥 경로 → /repo 심볼릭 링크를 만들고 공통 .git 도 맞춘다
#                           (vm_prepare_worktree). 공통 .git 이 공유 디렉터리 밖이면 거부한다.
#                           리눅스(VM 안·CI)나 VM_PROXY=0 이면 아무것도 하지 않고 돌아온다.
#   vm_create               make vm-create (TASK-64#1): 이미지(sha256)·raw 변환·cloud-init seed ISO·AppleScript 로 UTM VM 생성·시작
# 맥 권한 창은 vm_create 의 osascript(UTM 자동화 허용) 1회뿐이다(B1). utmctl 은 UTM 앱의 명령줄 도구라 권한 창이 없다.
# 메모리 기준값은 deploy/k8s/budgets.txt(vm-min·mac-free-min) 하나에서 읽는다.
# 테스트용 환경변수: VM_HOST_OS(기본 uname -s), UTMCTL, VM_CACHE_DIR, VM_SHARE_DIR, VM_DHCP_LEASES, VM_WAIT_TIMEOUT, VM_QEMU_IMG,
#   VM_IMAGE_URL·VM_IMAGE_SHA256(이미지 고정값 대신), VM_PROXY=0(맥에서도 VM 으로 보내지 않는다), VM_LOCK_FILE(VM 안 잠금 파일),
#   VM_CLOUD_INIT_TIMEOUT(초, cloud-init 완료 대기 제한).

VM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VM_NAME="${VM_NAME:-rocky-dev}"
VM_SSH_HOST="${VM_SSH_HOST:-$VM_NAME}"
VM_USER="${VM_USER:-rocky}"
VM_GUEST_REPO="/repo"
UTMCTL="${UTMCTL:-/Applications/UTM.app/Contents/MacOS/utmctl}"
VM_CACHE_DIR="${VM_CACHE_DIR:-$HOME/.cache/rocky-dev}"
VM_DHCP_LEASES="${VM_DHCP_LEASES:-/var/db/dhcpd_leases}"
VM_WAIT_TIMEOUT="${VM_WAIT_TIMEOUT:-300}"
VM_SSH_KEY="${VM_SSH_KEY:-$HOME/.ssh/rocky-dev_ed25519}"
VM_APPLESCRIPT="$VM_ROOT/deploy/dev-vm/create-vm.applescript"
VM_INSTALL_SCRIPT="$VM_ROOT/deploy/rocky/install.sh"
VM_BUDGETS_FILE="${K3D_BUDGETS_FILE:-$VM_ROOT/deploy/k8s/budgets.txt}"
# VM 안 실행 잠금 — 동시에 두 실행이 돌면 뒤 실행의 rsync --delete 가 앞 실행의 gradle 아래에서 /repo 를 바꾼다
VM_LOCK_FILE="${VM_LOCK_FILE:-/tmp/board-repo-test.lock}"
VM_CLOUD_INIT_TIMEOUT="${VM_CLOUD_INIT_TIMEOUT:-1200}"
# 공유를 나중에 붙였을 때 VM 로컬 bind 를 설정하는 명령(cloud-init runcmd 와 같은 인자 — VM 안 /usr/local/sbin 에 남아 있다)
VM_REINSTALL_CMD="sudo /usr/local/sbin/rocky-install.sh --profile dev --user $VM_USER --share-tag share --share-path $VM_GUEST_REPO"

# Rocky Linux 9 aarch64 GenericCloud(cloud-init 내장). 버전 디렉터리(9.8)로 고정하고, 다음 마이너가 나와 vault 로 옮겨지면 거기서 받는다.
# sha256 은 Rocky 의 CHECKSUM 파일 값이다 — 받은 뒤 이 값과 비교하고, CHECKSUM 에도 이 값이 있는지 본다.
VM_IMAGE_URL_DEFAULT="https://dl.rockylinux.org/pub/rocky/9.8/images/aarch64/Rocky-9-GenericCloud-Base-9.8-20260525.0.aarch64.qcow2"
VM_IMAGE_SHA256_DEFAULT="24692a444f1f0b8bb95375c38c8b43f8099a115347623691be2c330b40c8a1fe"
VM_IMAGE_URL="${VM_IMAGE_URL:-$VM_IMAGE_URL_DEFAULT}"
VM_IMAGE_SHA256="${VM_IMAGE_SHA256:-$VM_IMAGE_SHA256_DEFAULT}"
VM_MEMORY_MIB="${VM_MEMORY_MIB:-5120}"
VM_CPUS="${VM_CPUS:-4}"
VM_DISK_SIZE="${VM_DISK_SIZE:-40G}"

vm_log() { echo "==> $*" >&2; }
vm_warn() { echo "WARNING: $*" >&2; }
vm_err() { echo "ERROR: $*" >&2; }
vm_die() { vm_err "$@"; exit 1; }

vm_host_os() { echo "${VM_HOST_OS:-$(uname -s)}"; }
# 맥에서 VM 을 쓰는 경우(VM_PROXY=0 이면 쓰지 않는다 — 다른 컨테이너 런타임이 있는 맥 등)
vm_on_mac() { [ "$(vm_host_os)" = Darwin ] && [ "${VM_PROXY:-1}" != 0 ]; }

vm_budget() { awk -v k="$1" '$1 == k { print $2; exit }' "$VM_BUDGETS_FILE" 2>/dev/null; }

# 공유 디렉터리(맥 쪽) — make vm-create 때 기록한 값. 없으면 이 저장소
vm_share_dir() {
  if [ -n "${VM_SHARE_DIR:-}" ]; then echo "$VM_SHARE_DIR"; return; fi
  cat "$VM_CACHE_DIR/share-dir" 2>/dev/null || echo "$VM_ROOT"
}

# 상태: started | stopped | starting | paused | ... | absent(VM 없음 또는 UTM 없음)
vm_state() {
  local s
  [ -x "$UTMCTL" ] || { echo absent; return 0; }
  if s="$("$UTMCTL" status --hide "$VM_NAME" 2>/dev/null)" && [ -n "$s" ]; then
    printf '%s\n' "$s" | tr -d '[:space:]'
  else
    echo absent
  fi
}

# 게스트 IPv4: utmctl ip-address(QEMU 게스트 에이전트), 안 되면 맥의 DHCP 임대 파일(Apple 백엔드 NAT)에서 이름이 같은 최신 항목
vm_ip() {
  local ip
  ip="$("$UTMCTL" ip-address --hide "$VM_NAME" 2>/dev/null | grep -Eo '^[0-9]+(\.[0-9]+){3}$' | head -n1 || true)"
  if [ -z "$ip" ] && [ -r "$VM_DHCP_LEASES" ]; then
    ip="$(awk -v n="$VM_NAME" '
      /^\{/ { name = ""; ip = ""; lease = "" }
      /^[[:space:]]*name=/ { sub(/^[[:space:]]*name=/, ""); name = $0 }
      /^[[:space:]]*ip_address=/ { sub(/^[[:space:]]*ip_address=/, ""); ip = $0 }
      /^[[:space:]]*lease=/ { sub(/^[[:space:]]*lease=/, ""); lease = $0 }
      /^\}/ { if (name == n && ip != "" && (best == "" || lease > bestlease)) { best = ip; bestlease = lease } }
      END { print best }' "$VM_DHCP_LEASES")"
  fi
  printf '%s' "$ip"
}

# keepalive: 연결이 끊기면 1분 안에 알아챈다(오래 걸리는 cloud-init 대기 등이 끝없이 매달리지 않게)
vm_ssh() { ssh -o BatchMode=yes -o ConnectTimeout=5 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 "$VM_SSH_HOST" "$@"; }

# 맥 여유 메모리(MiB): vm_stat 의 free + inactive + speculative 페이지
vm_mac_free_mib() {
  vm_stat 2>/dev/null | awk '
    /page size of/ { for (i = 1; i <= NF; i++) if ($i == "of") ps = $(i + 1) }
    /^Pages (free|inactive|speculative):/ { gsub(/\./, "", $NF); n += $NF }
    END { if (ps) printf "%d", n * ps / 1048576 }'
}

vm_not_running_msg() {
  local state="$1"
  if [ "$state" = absent ]; then
    echo "VM '$VM_NAME' 이 없다(UTM). 해결: make vm-create (README 로컬 개발 환경)"
  else
    echo "VM '$VM_NAME' 이 켜져 있지 않다(상태: $state). 사전 검사는 VM 을 스스로 켜지 않는다 — 메모리(8GB 맥에서 5GB)를 쓰는 일이라 직접 켠다.
  해결: make vm-start"
  fi
}

vm_preflight() {
  vm_on_mac || return 0
  local state kb vm_mib min mac_mib mac_min
  state="$(vm_state)"
  if [ "$state" != started ]; then
    vm_err "$(vm_not_running_msg "$state")"
    return 1
  fi
  if ! kb="$(vm_ssh awk "'/^MemTotal:/ { print \$2 }'" /proc/meminfo 2>/dev/null)" || ! [[ "$kb" =~ ^[0-9]+$ ]]; then
    vm_err "VM '$VM_NAME' 은 켜져 있는데 ssh $VM_SSH_HOST 가 안 된다(부팅 중이거나 IP 가 바뀌었다).
  해결: 잠시 뒤 다시, 또는 make vm-start (IP 를 다시 읽어 ~/.ssh/config.d/rocky-dev 를 고친다)"
    return 1
  fi
  vm_mib=$((kb / 1024))
  min="$(vm_budget vm-min)"; mac_min="$(vm_budget mac-free-min)"
  mac_mib="$(vm_mac_free_mib)"
  vm_log "VM 메모리 ${vm_mib} MiB (최소 ${min:-?}) · 맥 여유 메모리 ${mac_mib:-?} MiB (경고 기준 ${mac_min:-?})"
  if [ -n "$mac_mib" ] && [ -n "$mac_min" ] && [ "$mac_mib" -lt "$mac_min" ]; then
    vm_warn "맥 여유 메모리 ${mac_mib} MiB < ${mac_min} MiB — 맥 쪽 앱(브라우저·IDE)을 줄이지 않으면 스왑으로 느려진다."
  fi
  if [ -n "$min" ] && [ "$vm_mib" -lt "$min" ]; then
    vm_err "VM 메모리 ${vm_mib} MiB < ${min} MiB(vm-min) — VM 이 5GB(${VM_MEMORY_MIB}MiB)로 만들어지지 않았다.
  해결: UTM 에서 $VM_NAME 을 끄고(make vm-stop) 메모리를 ${VM_MEMORY_MIB}MiB 로 고치거나, 다시 만든다(README 다시 만들기)"
    return 1
  fi
}

# 셸 한 단어로 인용(작은따옴표)
vm_q() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

# 저장소를 VM 에 맞춘다(TASK-64#5): /repo 가 VirtioFS 공유(UTM 창에서 직접 붙인 경우)면 그대로 쓰고, 아니면 rsync(ssh)로 작업 트리
# (커밋 안 된 변경·.git 포함)를 VM 로컬 디렉터리에 맞춘다(--delete: 지운 파일도). 플랫폼이 다른 산출물(node_modules·빌드·.terraform)은
# 보내지 않고 VM 쪽 것을 그대로 둔다(--delete 는 제외 대상을 지우지 않는다). 비밀 파일(.env·deploy/*.local.txt)은 보내지 않는다.
VM_SYNC_EXCLUDES=(
  --exclude=node_modules/ --exclude=/frontend/.next/ --exclude=/frontend/out/ --exclude=/frontend/coverage/
  --exclude=/frontend/test-results/ --exclude=/frontend/playwright-report/ --exclude=/frontend/e2e/artifacts/
  --exclude=/backend/build/ --exclude=/backend/.gradle/ --exclude=/backend/bin/ --exclude=/backend/out/
  --exclude=.terraform/ --exclude=/.claude/worktrees/ --exclude=/.env --exclude=/.env.* --exclude=/deploy/*.local.txt
  --exclude=.DS_Store
)
# VM 로컬 bind 로 덮는 디렉터리(deploy/rocky/install.sh 와 같은 목록)
VM_OVERLAY_DIRS="frontend/node_modules backend/build backend/.gradle"

vm_repo_is_virtiofs() {
  vm_ssh "mountpoint -q $(vm_q "$VM_GUEST_REPO") && [ \"\$(stat -f -c %T $(vm_q "$VM_GUEST_REPO"))\" = virtiofs ]" 2>/dev/null
}

# VirtioFS 공유를 VM 을 만든 뒤에 붙이면 install.sh 의 bind 덮기가 설정되지 않은 채다 — 맥의 node_modules(darwin)·빌드 산출물과 섞인다
vm_check_overlays() {
  local missing
  missing="$(vm_ssh "cd $(vm_q "$VM_GUEST_REPO") && for d in $VM_OVERLAY_DIRS; do [ -d \"\${d%%/*}\" ] || continue; mountpoint -q \"\$d\" || echo \"\$d\"; done" 2>/dev/null || true)"
  [ -z "$missing" ] && return 0
  vm_warn "VM $VM_GUEST_REPO 는 VirtioFS 공유인데 VM 로컬 bind 덮기가 없다: $(printf '%s' "$missing" | tr '\n' ' ')
  — 맥의 node_modules(darwin 네이티브 모듈)·backend 빌드 산출물을 VM 이 그대로 쓰게 된다(공유를 VM 을 만든 뒤에 붙였을 때).
  해결(한 번, 다시 돌려도 된다): ssh $VM_SSH_HOST $VM_REINSTALL_CMD"
}

vm_sync_repo() {
  local src="$1" dest="$2"
  vm_log "저장소 동기화: $src/ → $VM_SSH_HOST:$dest/ (rsync, 작업 트리 그대로)"
  # VM 쪽 rsync 도 같은 잠금을 잡는다(-n: 다른 실행이 잡고 있으면 기다리지 않고 실패) — 실행 중인 테스트 아래에서 파일을 바꾸지 않게
  rsync -a --delete "${VM_SYNC_EXCLUDES[@]}" -e "ssh -o BatchMode=yes" \
    --rsync-path="mkdir -p $(vm_q "$dest") && flock -n $(vm_q "$VM_LOCK_FILE") rsync" \
    "$src/" "$VM_SSH_HOST:$dest/" \
    || vm_die "rsync 로 저장소를 VM($VM_SSH_HOST:$dest)에 맞추지 못했다 — ssh $VM_SSH_HOST 가 되는지, VM 디스크 여유, 다른 실행이 잠금($VM_LOCK_FILE)을 잡고 있지 않은지 본다"
}

vm_lock_busy_msg() {
  echo "VM 안에서 다른 테스트 실행이 아직 돌고 있다(잠금 $VM_LOCK_FILE) — 동시에 돌리면 뒤 실행의 동기화가 앞 실행의 gradle 아래에서 /repo 를 바꾼다.
  해결: 끝날 때까지 기다린 뒤 다시. 남은 프로세스 보기: ssh $VM_SSH_HOST 'ps -eo pid,etime,args --forest' (필요하면 kill)"
}

# 잠금이 비어 있는지 먼저 본다(rsync 전에 — 잡혀 있으면 아무것도 바꾸지 않고 실패)
vm_check_lock() {
  local rc=0
  vm_ssh "flock -n -E 75 $(vm_q "$VM_LOCK_FILE") true" 2>/dev/null || rc=$?
  case "$rc" in
    0) ;;
    75) vm_die "$(vm_lock_busy_msg)" ;;
    *) vm_die "VM 잠금($VM_LOCK_FILE)을 확인하지 못했다(ssh $VM_SSH_HOST, 종료 코드 $rc)" ;;
  esac
}

# git worktree: worktree 의 .git 은 "gitdir: <맥 절대 경로>/.git/worktrees/<이름>" 파일이고, 그 디렉터리의 gitdir 도 맥 경로를 가리킨다.
# 파일을 고치는 대신 VM 에 같은 맥 경로를 /repo 로 가는 심볼릭 링크로 만든다(VirtioFS 공유에서도 맥 파일을 건드리지 않고 된다).
# rsync 방식이면 공통 .git 도 VM 의 같은 상대 위치에 맞춘다(저장소 본체 동기화는 .claude/worktrees 를 빼서 서로 지우지 않는다).
# $3=virtiofs 면 링크만.
vm_prepare_worktree() {
  local here="$1" share="$2" mode="${3:-rsync}" gd_raw gd cd_raw common
  [ -f "$here/.git" ] || return 0
  gd_raw="$(sed -n 's/^gitdir: //p' "$here/.git" | head -n1)"
  [ -n "$gd_raw" ] || vm_die "$here/.git 파일에 gitdir: 줄이 없다(git worktree 가 아니다?)"
  case "$gd_raw" in /*) ;; *) gd_raw="$here/$gd_raw" ;; esac
  gd="$(cd "$gd_raw" 2>/dev/null && pwd -P)" || vm_die "git worktree 의 git 디렉터리($gd_raw)가 없다 — 맥에서 git worktree repair 로 고친다"
  if [ -f "$gd/commondir" ]; then
    cd_raw="$(head -n1 "$gd/commondir")"
    case "$cd_raw" in /*) ;; *) cd_raw="$gd/$cd_raw" ;; esac
    common="$(cd "$cd_raw" 2>/dev/null && pwd -P)" || vm_die "git worktree 의 공통 .git($cd_raw)이 없다"
  else
    common="$gd"
  fi
  case "$common/" in
    "$share"/*) ;;
    *) vm_die "이 git worktree 의 공통 .git($common)이 VM 공유 디렉터리($share) 밖에 있다 — VM 안 git 이 읽지 못한다.
  해결: 공유 디렉터리 안 저장소($share)의 worktree 에서 실행하거나, 저장소 본체에서 실행한다" ;;
  esac
  vm_log "git worktree: VM 에 $share → $VM_GUEST_REPO 링크(.git 파일의 맥 절대 경로를 VM 안에서도 읽게)"
  vm_ssh sudo -n sh -c "$(vm_q "{ [ -L $(vm_q "$share") ] || [ ! -e $(vm_q "$share") ]; } && mkdir -p $(vm_q "$(dirname "$share")") && ln -sfn $(vm_q "$VM_GUEST_REPO") $(vm_q "$share")")" \
    || vm_die "VM 에 $share → $VM_GUEST_REPO 링크를 만들지 못했다(VM 안 같은 경로에 실제 디렉터리가 있거나 VM 안 무비밀번호 관리자 권한이 없다)"
  [ "$mode" = virtiofs ] && return 0
  vm_sync_repo "$common" "$VM_GUEST_REPO${common#"$share"}"
}

# VM 안에서 돌 셸 스크립트(sshd 가 사용자 셸 bash -c 로 돌린다). 가상 터미널(ssh -tt)을 쓰지 않아 stdout/stderr·줄바꿈이 그대로다.
#  - 잠금: fd 9 로 $VM_LOCK_FILE 을 -n 으로 잡는다. 잡혀 있으면 75 로 바로 실패. 명령에는 fd 9 를 넘기지 않는다(오래 사는 자식이 잠금을 쥐지 않게)
#  - 감시: 명령은 setsid 로 새 프로세스 그룹. 부모(sshd)가 바뀌면 = ssh 가 끊겼으면(호출자 취소·시간 초과) 그룹 전체에 TERM(10초 뒤 KILL).
#    Ctrl-C(ssh -t)·TERM·HUP 도 그룹에 전한다. 끝나면 명령의 종료 코드로 끝난다
vm_remote_script() {
  local dir="$1" cmd="$2"
  cat <<EOF
exec 9>>$(vm_q "$VM_LOCK_FILE") || exit 1
flock -n 9 || { echo $(vm_q "ERROR: $(vm_lock_busy_msg)") >&2; exit 75; }
cd $(vm_q "$dir") || exit 1
pp=\$PPID
setsid bash -lc $(vm_q "$cmd") 9>&- &
c=\$!
trap 'kill -TERM -- -\$c 2>/dev/null; exit 130' INT TERM HUP
while kill -0 "\$c" 2>/dev/null; do
  if [ "\$(ps -o ppid= -p \$\$ | tr -d ' ')" != "\$pp" ]; then
    kill -TERM -- -"\$c" 2>/dev/null; sleep 10; kill -KILL -- -"\$c" 2>/dev/null; exit 129
  fi
  sleep 2
done
wait "\$c"
EOF
}

vm_proxy_if_mac() {
  vm_on_mac || return 0
  vm_preflight || exit 1
  local share here rel dir rc=0 tty=()
  share="$(cd "$(vm_share_dir)" 2>/dev/null && pwd -P || true)"
  here="$(cd "$VM_ROOT" && pwd -P)"
  case "$here/" in
    "$share"/*) [ -n "$share" ] || vm_die "VM 공유 디렉터리($(vm_share_dir))가 없다 — README 다시 만들기"; rel="${here#"$share"}" ;;
    *) vm_die "이 저장소($here)가 VM 공유 디렉터리(${share:-$(vm_share_dir)}) 밖에 있다 — VM 안에서 볼 수 없다.
  해결: 공유 디렉터리 안의 저장소에서 실행하거나, VM 을 이 저장소로 다시 만든다(README 다시 만들기)" ;;
  esac
  dir="$VM_GUEST_REPO$rel"
  vm_check_lock
  if vm_repo_is_virtiofs; then
    vm_log "VM $VM_GUEST_REPO 는 VirtioFS 공유 — 동기화 없이 바로 쓴다"
    vm_check_overlays
    vm_prepare_worktree "$here" "$share" virtiofs
  else
    vm_prepare_worktree "$here" "$share"
    vm_sync_repo "$here" "$dir"
  fi
  [ -t 0 ] && [ -t 1 ] && tty=(-t)
  vm_log "맥에는 컨테이너 런타임이 없다 — VM '$VM_NAME' 안에서 돈다: ssh $VM_SSH_HOST 'cd $dir && $*'"
  ssh "${tty[@]}" -o BatchMode=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=4 "$VM_SSH_HOST" "$(vm_remote_script "$dir" "$*")" || rc=$?
  exit "$rc"
}

# ---------------------------------------------------------------- ssh 설정 (TASK-64#3)
# ~/.ssh/config 에는 맨 위에 Include 한 줄만 더한다(처음 한 번, 먼저 백업). Host rocky-dev 는 ~/.ssh/config.d/rocky-dev 에 다시 쓴다.
vm_write_ssh_config() {
  local ip="$1" dir="$HOME/.ssh" inc_line="Include config.d/rocky-dev" cfg="$HOME/.ssh/config" tmp
  mkdir -p "$dir/config.d"; chmod 700 "$dir" "$dir/config.d"
  tmp="$dir/config.d/.rocky-dev.tmp"
  cat >"$tmp" <<EOF
# make vm-create·vm-start 가 쓴다(TASK-64#3) — 손으로 고치지 않는다(다시 쓰인다). ~/.ssh/config 의 Include 로 읽힌다.
Host $VM_SSH_HOST
  HostName $ip
  User $VM_USER
  IdentityFile ~/.ssh/rocky-dev_ed25519
  IdentitiesOnly yes
  UserKnownHostsFile ~/.ssh/known_hosts.rocky-dev
  StrictHostKeyChecking accept-new
  ServerAliveInterval 30
EOF
  chmod 600 "$tmp"; mv "$tmp" "$dir/config.d/rocky-dev"
  if [ ! -e "$cfg" ]; then
    printf '%s\n' "$inc_line" >"$cfg"; chmod 600 "$cfg"
    vm_log "$cfg 를 만들었다(Include 한 줄)"
  elif ! grep -qxF "$inc_line" "$cfg"; then
    local bak; bak="$cfg.bak-rocky-dev-$(date +%Y%m%d%H%M%S)"
    cp -p "$cfg" "$bak"
    { printf '%s\n' "$inc_line"; cat "$bak"; } >"$cfg"
    vm_log "$cfg 맨 위에 '$inc_line' 한 줄을 더했다(백업: $bak)"
  fi
  vm_log "ssh 설정: Host $VM_SSH_HOST → $ip (~/.ssh/config.d/rocky-dev)"
}

vm_wait_ip() {
  local deadline ip
  deadline=$(( $(date +%s) + VM_WAIT_TIMEOUT ))
  while :; do
    ip="$(vm_ip)"
    [ -z "$ip" ] || { printf '%s' "$ip"; return 0; }
    [ "$(date +%s)" -lt "$deadline" ] || return 1
    sleep 2
  done
}

# utmctl start 는 UTM 이 디스크·ISO 를 열지 못해도 0 으로 끝날 때가 있다 — 상태가 started 가 될 때까지만 짧게 기다린다
vm_start_checked() {
  local deadline
  "$UTMCTL" start --hide "$VM_NAME" || true
  deadline=$(( $(date +%s) + ${VM_START_TIMEOUT:-60} ))
  until [ "$(vm_state)" = started ]; do
    [ "$(date +%s)" -lt "$deadline" ] || vm_die "VM '$VM_NAME' 이 켜지지 않았다(상태: $(vm_state)). 위 utmctl 오류와 UTM 창의 메시지를 본다"
    sleep 2
  done
}

vm_wait_ssh() {
  local deadline
  deadline=$(( $(date +%s) + VM_WAIT_TIMEOUT ))
  until vm_ssh true 2>/dev/null; do
    [ "$(date +%s)" -lt "$deadline" ] || return 1
    sleep 3
  done
}

# 켜진 VM 의 IP 를 읽어 ssh 설정을 쓰고 ssh 가 될 때까지 기다린다
vm_connect() {
  local ip
  ip="$(vm_wait_ip)" || vm_die "VM '$VM_NAME' 의 IP 를 ${VM_WAIT_TIMEOUT}초 안에 찾지 못했다(utmctl ip-address·$VM_DHCP_LEASES). UTM 창에서 부팅 화면을 본다"
  vm_write_ssh_config "$ip"
  vm_wait_ssh || vm_die "ssh $VM_SSH_HOST($ip) 가 ${VM_WAIT_TIMEOUT}초 안에 되지 않았다"
}

# ---------------------------------------------------------------- make vm-create (TASK-64#1)
vm_sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

vm_qemu_img() {
  if [ -n "${VM_QEMU_IMG:-}" ]; then
    [ -x "$VM_QEMU_IMG" ] && { echo "$VM_QEMU_IMG"; return 0; }
    return 1
  fi
  command -v qemu-img
}

vm_fetch_image() {
  local name file sums
  name="$(basename "$VM_IMAGE_URL")"; file="$VM_CACHE_DIR/$name"
  mkdir -p "$VM_CACHE_DIR"
  # Rocky 의 CHECKSUM 에 고정값이 있는지(고정값을 잘못 적지 않았는지). 오프라인이면 캐시의 고정값 비교만 한다
  if sums="$(curl -fsSL --retry 3 "$VM_IMAGE_URL.CHECKSUM" 2>/dev/null || curl -fsSL --retry 3 "$(vm_vault_url).CHECKSUM" 2>/dev/null)"; then
    grep -qF "= $VM_IMAGE_SHA256" <<<"$sums" \
      || vm_die "Rocky CHECKSUM($VM_IMAGE_URL.CHECKSUM)에 고정 sha256($VM_IMAGE_SHA256)이 없다 — 이미지 고정값을 확인한다"
  else
    [ -f "$file" ] || vm_die "Rocky CHECKSUM 을 받지 못했다(네트워크): $VM_IMAGE_URL.CHECKSUM"
    vm_warn "Rocky CHECKSUM 을 받지 못해 캐시 이미지를 고정 sha256 으로만 확인한다"
  fi
  if [ ! -f "$file" ]; then
    vm_log "이미지 받기: $VM_IMAGE_URL → $file"
    curl -fL --retry 3 -o "$file.part" "$VM_IMAGE_URL" || curl -fL --retry 3 -o "$file.part" "$(vm_vault_url)" \
      || { rm -f "$file.part"; vm_die "이미지를 받지 못했다: $VM_IMAGE_URL"; }
    mv "$file.part" "$file"
  fi
  local got; got="$(vm_sha256 "$file")"
  if [ "$got" != "$VM_IMAGE_SHA256" ]; then
    rm -f "$file"
    vm_die "이미지 sha256 이 고정값과 다르다(받은 $got, 고정 $VM_IMAGE_SHA256) — 지웠다. 다시 make vm-create"
  fi
  vm_log "이미지 sha256 확인: $VM_IMAGE_SHA256"
  printf '%s' "$file"
}
# pub/rocky/9.8 → vault/rocky/9.8 (다음 마이너가 나오면 옮겨진다)
vm_vault_url() { printf '%s' "${VM_IMAGE_URL/\/pub\/rocky\//\/vault\/rocky\/}"; }

vm_render_seed() {
  local dir="$1" pub b64 uid
  pub="$(cat "$VM_SSH_KEY.pub")"; uid="$(id -u)"
  b64="$(base64 <"$VM_INSTALL_SCRIPT" | tr -d '\n')"
  mkdir -p "$dir"
  cat >"$dir/meta-data" <<EOF
instance-id: $VM_NAME-$(date +%Y%m%d%H%M%S)
local-hostname: $VM_NAME
EOF
  # uid 는 맥 사용자와 같게 — VirtioFS 공유의 파일 주인이 맥 uid 로 보여서, 같아야 VM 안에서 저장소에 쓸 수 있다
  cat >"$dir/user-data" <<EOF
#cloud-config
# make vm-create 가 만든다(TASK-64#2). 설치는 deploy/rocky/install.sh(AWS Rocky EC2 와 공유)를 seed 에 그대로 넣어 돌린다.
hostname: $VM_NAME
fqdn: $VM_NAME
ssh_pwauth: false
users:
  - name: $VM_USER
    uid: $uid
    groups: [wheel]
    shell: /bin/bash
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    lock_passwd: true
    ssh_authorized_keys:
      - $pub
write_files:
  - path: /usr/local/sbin/rocky-install.sh
    permissions: "0755"
    encoding: b64
    content: $b64
runcmd:
  - /usr/local/sbin/rocky-install.sh --profile dev --user $VM_USER --share-tag share --share-path $VM_GUEST_REPO
EOF
}

vm_create() {
  [ "$(vm_host_os)" = Darwin ] || vm_die "make vm-create 는 macOS(UTM)에서만 돈다"
  [ -x "$UTMCTL" ] || vm_die "UTM 이 없다($UTMCTL). UTM 을 설치한다(https://mac.getutm.app)"
  local state; state="$(vm_state)"
  if [ "$state" != absent ]; then
    vm_die "VM '$VM_NAME' 이 이미 있다(상태: $state) — 만들지 않는다.
  켜기: make vm-start · 상태: make vm-status
  다시 만들기: UTM 에서 '$VM_NAME' 을 삭제한 뒤(VM 안 데이터가 모두 지워진다) make vm-create (README 로컬 개발 환경의 다시 만들기)"
  fi
  local qemu share
  qemu="$(vm_qemu_img)" || vm_die "qemu-img 가 없다(qcow2 → raw 변환, Apple 가상화는 raw 디스크만 쓴다). 설치: brew install qemu"
  for t in hdiutil osascript ssh-keygen curl; do command -v "$t" >/dev/null 2>&1 || vm_die "$t 가 없다"; done
  share="$(cd "$(vm_share_dir)" && pwd -P)"

  if [ ! -f "$VM_SSH_KEY" ]; then
    mkdir -p "$(dirname "$VM_SSH_KEY")"; chmod 700 "$(dirname "$VM_SSH_KEY")"
    ssh-keygen -q -t ed25519 -N "" -C "$VM_NAME" -f "$VM_SSH_KEY"
    vm_log "전용 SSH 키를 만들었다: $VM_SSH_KEY"
  fi

  local image build raw seed
  image="$(vm_fetch_image)" || exit 1
  build="$VM_CACHE_DIR/build"; rm -rf "$build"; mkdir -p "$build"
  raw="$build/$VM_NAME.img"; seed="$build/seed.iso"
  vm_log "qcow2 → raw 변환 + 디스크 $VM_DISK_SIZE ($raw, 희소 파일)"
  "$qemu" convert -f qcow2 -O raw "$image" "$raw"
  "$qemu" resize -f raw "$raw" "$VM_DISK_SIZE" >/dev/null
  vm_render_seed "$build/seed"
  hdiutil makehybrid -iso -joliet -default-volume-name cidata -o "$seed" "$build/seed" >/dev/null

  cat >&2 <<EOF
==> 다음 단계에서 macOS 가 "'터미널'(또는 쓰는 앱)이 'UTM' 을 제어하도록 허용하겠습니까?" 자동화 권한 창을 한 번 띄운다.
    [허용] 을 누른다. 이 프로젝트에서 맥 권한 창이 뜨는 곳은 여기 한 번뿐이다(관리자 비밀번호는 묻지 않는다).
    거절했다면: 시스템 설정 → 개인정보 보호 및 보안 → 자동화 에서 UTM 을 켜고 다시 make vm-create
EOF
  vm_log "UTM 에 VM '$VM_NAME' 만들기(Apple 가상화, ${VM_MEMORY_MIB}MiB, CPU $VM_CPUS, 디스크 $VM_DISK_SIZE, NAT)"
  osascript "$VM_APPLESCRIPT" "$VM_NAME" "$VM_MEMORY_MIB" "$VM_CPUS" "$raw" "$seed" >/dev/null \
    || vm_die "UTM 에 VM 을 만들지 못했다(osascript). 자동화 권한을 거절했다면 위 안내대로 허용하고 다시 make vm-create"
  printf '%s\n' "$share" >"$VM_CACHE_DIR/share-dir"
  rm -f "$raw" "$HOME/.ssh/known_hosts.rocky-dev"

  vm_log "VM 시작 — 처음 부팅은 cloud-init 이 패키지를 설치해서 10분쯤 걸린다"
  vm_start_checked
  vm_connect
  vm_log "cloud-init 완료 대기(설치 로그: VM 안 /var/log/cloud-init-output.log)"
  # cloud-init status 는 VM 안 root 만 읽을 수 있다(/run/cloud-init) — VM 안 사용자의 NOPASSWD 권한으로(맥이 아니다)
  # VM 안 timeout 으로 제한한다(기본 20분) — 설치가 멈춰도 끝없이 기다리지 않는다. 연결이 끊기면 vm_ssh keepalive 가 알아챈다
  local ci_rc=0
  vm_ssh sudo -n timeout "$VM_CLOUD_INIT_TIMEOUT" cloud-init status --wait >/dev/null || ci_rc=$?
  if [ "$ci_rc" = 124 ]; then
    vm_ssh sudo -n cloud-init status --long >&2 || true
    vm_die "cloud-init 이 $((VM_CLOUD_INIT_TIMEOUT / 60))분 안에 끝나지 않았다(시간 초과) — VM 안 /var/log/cloud-init-output.log 에서 멈춘 단계를 본다.
  아직 설치 중이면 ssh $VM_SSH_HOST 'sudo cloud-init status --wait' 로 이어서 기다릴 수 있다. 실패면 고친 뒤 다시 만든다(README 다시 만들기)"
  elif [ "$ci_rc" != 0 ]; then
    vm_ssh sudo -n cloud-init status --long >&2 || true
    vm_die "cloud-init 이 실패했다 — 위 상태와 VM 안 /var/log/cloud-init-output.log 를 본다. 고친 뒤 다시 만든다(README 다시 만들기)"
  fi
  vm_log "완료: ssh $VM_SSH_HOST · make vm-status · make test(맥에서 실행해도 VM 안에서 돈다)"
}
