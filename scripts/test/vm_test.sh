#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2015,SC2088 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다, A && B || C 는 실패 출력용, ~ 는 글자 그대로 찾는다
# Rocky 개발 VM(UTM) + Podman 테스트 (TASK-64). scripts/test.sh 가 호출한다.
#   - make vm-create: 이미지 체크섬·raw 변환·seed ISO·AppleScript 호출 순서, 이미 있으면 거부, 권한 창 안내(B1)
#   - cloud-init·설치 스크립트(deploy/rocky/install.sh): 고정 버전·sha256, SELinux enforcing 유지, podman 소켓
#   - ~/.ssh/config Include(별도 파일), make vm-start·vm-stop·vm-status·vm-ssh
#   - 사전 검사(B2): VM 이 꺼져 있으면 켜지 않고 make vm-start 를 안내하며 실패. 테스트·k3d 진입점이 같은 검사를 거친다
#   - 맥의 make test 는 VM 안에서 돈다(ssh rocky-dev). 리눅스(VM·CI)에서는 그대로
#   - YAML→JSON 은 고정 버전 yq(ruby 없음)
# 진짜 UTM·ssh·osascript·네트워크는 부르지 않는다(PATH 앞에 둔 가짜 utmctl·ssh·osascript·curl·qemu-img·hdiutil·vm_stat, 임시 HOME).
# 맥이 아닌 곳(VM·CI)에서도 돌도록 VM_HOST_OS=Darwin 으로 맥을 흉내 낸다.
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

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "vm tests"

# ---------------------------------------------------------------- 가짜 명령
FAKE="$TMP/fake"
mkdir -p "$FAKE/bin" "$FAKE/state"
export FAKE_STATE="$FAKE/state"
calls() { cat "$FAKE_STATE/calls.log" 2>/dev/null || true; }
called() { grep -qF -- "$1" "$FAKE_STATE/calls.log" 2>/dev/null || { echo "호출 없음: $1"; echo "--- calls:"; calls; echo "--- output:"; echo "$OUT"; return 1; }; }
not_called() { ! grep -qF -- "$1" "$FAKE_STATE/calls.log" 2>/dev/null || { echo "호출되면 안 됨: $1"; echo "--- calls:"; calls; return 1; }; }

# 가짜 utmctl: status → $FAKE_STATE/vm 의 상태(없으면 "Virtual machine not found." + 1), start/stop 은 상태를 바꾼다,
# ip-address → FAKE_VM_IP(없으면 실패 — 게스트 에이전트 없음 흉내)
cat >"$FAKE/bin/utmctl" <<'SH'
#!/usr/bin/env bash
printf 'utmctl %s\n' "$*" >>"$FAKE_STATE/calls.log"
args=(); for a in "$@"; do case "$a" in --*) ;; *) args+=("$a") ;; esac; done
state="$(cat "$FAKE_STATE/vm" 2>/dev/null || true)"
case "${args[0]:-}" in
  status) [ -n "$state" ] || { echo "Error: Virtual machine not found." >&2; exit 1; }; echo "$state" ;;
  start) [ -n "$state" ] || exit 1; [ "${FAKE_START_NOOP:-0}" = 1 ] && { echo "Error from event: (OSStatus error -10004.)" >&2; exit 0; }; echo started >"$FAKE_STATE/vm" ;;
  stop) [ -n "$state" ] || exit 1; echo stopped >"$FAKE_STATE/vm" ;;
  ip-address) [ -n "${FAKE_VM_IP:-}" ] || { echo "Error: Operation not supported by the backend." >&2; exit 1; }; echo "$FAKE_VM_IP" ;;
  *) exit 0 ;;
esac
SH
# 가짜 ssh: 명령을 기록하고, /proc/meminfo 를 물으면 FAKE_VM_MEM_KB, make 는 FAKE_MAKE_RC 로 끝난다
cat >"$FAKE/bin/ssh" <<'SH'
#!/usr/bin/env bash
printf 'ssh %s\n' "$*" >>"$FAKE_STATE/calls.log"
[ "${FAKE_SSH_FAIL:-0}" = 1 ] && { echo "ssh: connect to host rocky-dev port 22: Connection refused" >&2; exit 255; }
case "$*" in
  *meminfo*) echo "${FAKE_VM_MEM_KB:-4900000}" ;;
  # 잠금 사전 확인(flock -n -E 75 … true): FAKE_LOCK_BUSY=1 이면 다른 실행이 잡고 있다
  *"-E 75"*) exit "$([ "${FAKE_LOCK_BUSY:-0}" = 1 ] && echo 75 || echo 0)" ;;
  # VirtioFS 공유의 bind 덮기 확인: 빠진 디렉터리를 한 줄씩(FAKE_OVERLAY_MISSING)
  *"frontend/node_modules"*) [ -n "${FAKE_OVERLAY_MISSING:-}" ] && echo "$FAKE_OVERLAY_MISSING"; exit 0 ;;
  # /repo 가 VirtioFS(/mnt/share 안 저장소의 bind)인지(FAKE_VIRTIOFS), 태그만 /mnt/share 에 붙고 bind 는 없는지(FAKE_STAGE_MOUNTED)
  *findmnt*) exit "$([ "${FAKE_VIRTIOFS:-0}" = 1 ] && echo 0 || echo 1)" ;;
  # rsync 전에 /repo 를 쓸 수 있는지(FAKE_REPO_RO=1 이면 공유가 빠진 뒤 root 소유)
  *"-w '/repo'"*) exit "$([ "${FAKE_REPO_RO:-0}" = 1 ] && echo 1 || echo 0)" ;;
  *"mountpoint -q '/mnt/share'"*) exit "$([ "${FAKE_STAGE_MOUNTED:-0}" = 1 ] && echo 0 || echo 1)" ;;
  *"cloud-init status --wait"*) exit "${FAKE_CLOUD_INIT_RC:-0}" ;;
  *"make "*) echo "FAKE-REMOTE-MAKE"; exit "${FAKE_MAKE_RC:-0}" ;;
  *) exit 0 ;;
esac
SH
# 가짜 osascript: 호출 순서를 보이게 출력하고, 상태를 stopped(새 VM)로 만든다
cat >"$FAKE/bin/osascript" <<'SH'
#!/usr/bin/env bash
printf 'osascript %s\n' "$*" >>"$FAKE_STATE/calls.log"
echo "FAKE-OSASCRIPT-RUN" >&2
[ "${FAKE_OSA_FAIL:-0}" = 1 ] && exit 1
echo stopped >"$FAKE_STATE/vm"
SH
# 가짜 curl: CHECKSUM 이면 Rocky 형식 한 줄(FAKE_CHECKSUM_SHA), 아니면 -o 경로에 FAKE_IMAGE_BODY
cat >"$FAKE/bin/curl" <<'SH'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"$FAKE_STATE/calls.log"
out=""; url=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift 2 ;; -*) shift ;; *) url="$1"; shift ;; esac; done
case "$url" in
  *CHECKSUM) printf 'SHA256 (%s) = %s\n' "$(basename "${url%.CHECKSUM}")" "${FAKE_CHECKSUM_SHA:-}" | { if [ -n "$out" ]; then cat >"$out"; else cat; fi; } ;;
  *) printf '%s' "${FAKE_IMAGE_BODY:-FAKE-IMAGE}" >"$out" ;;
esac
SH
cat >"$FAKE/bin/qemu-img" <<'SH'
#!/usr/bin/env bash
printf 'qemu-img %s\n' "$*" >>"$FAKE_STATE/calls.log"
case "$1" in convert) out="${*: -1}"; echo RAW >"$out" ;; esac
SH
# 가짜 hdiutil: makehybrid 의 원본 디렉터리를 남겨 user-data 를 볼 수 있게 한다
cat >"$FAKE/bin/hdiutil" <<'SH'
#!/usr/bin/env bash
printf 'hdiutil %s\n' "$*" >>"$FAKE_STATE/calls.log"
out=""; src="${*: -1}"
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift 2 ;; *) shift ;; esac; done
rm -rf "$FAKE_STATE/seed"; cp -R "$src" "$FAKE_STATE/seed"; echo ISO >"$out"
SH
cat >"$FAKE/bin/rsync" <<'SH'
#!/usr/bin/env bash
printf 'rsync %s\n' "$*" >>"$FAKE_STATE/calls.log"
exit "${FAKE_RSYNC_RC:-0}"
SH
cat >"$FAKE/bin/vm_stat" <<'SH'
#!/usr/bin/env bash
cat <<EOF
Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                               ${FAKE_MAC_FREE_PAGES:-20000}.
Pages active:                              60000.
Pages inactive:                           ${FAKE_MAC_INACTIVE_PAGES:-30000}.
Pages speculative:                          2000.
EOF
SH
chmod +x "$FAKE/bin/"*

FAKE_HOME="$TMP/home"
IMG_SHA="$(printf '%s' FAKE-IMAGE | { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi; } | cut -d' ' -f1)"
fake_reset() {
  rm -rf "$FAKE_STATE" "$FAKE_HOME"; mkdir -p "$FAKE_STATE" "$FAKE_HOME/.ssh"
  : >"$FAKE_STATE/calls.log"
}
# vm_run <env...> <명령...>: 맥 흉내(VM_HOST_OS=Darwin) + 가짜 PATH + 임시 HOME·캐시. 이미지 sha256 은 가짜 이미지 값으로 고정
vm_run() {
  env PATH="$FAKE/bin:$PATH" HOME="$FAKE_HOME" VM_HOST_OS=Darwin UTMCTL="$FAKE/bin/utmctl" \
    VM_CACHE_DIR="$FAKE_HOME/cache" VM_IMAGE_SHA256="$IMG_SHA" FAKE_CHECKSUM_SHA="$IMG_SHA" \
    VM_DHCP_LEASES="$FAKE_STATE/leases" VM_WAIT_TIMEOUT=3 VM_SHARE_DIR="$ROOT" "$@"
}

# ================================================================ TASK-64#1 make vm-create
check "TASK-64#1 make vm-create 타깃은 scripts/vm.sh create 를 부른다" sh -c 'make -n vm-create | grep -q "scripts/vm.sh create"'
check "TASK-64#1 AppleScript 는 저장소에 있다(deploy/dev-vm/create-vm.applescript): Apple 백엔드·NAT, 디스크·seed 는 VM 안으로 복사(이동식·공유 디렉터리 없음 — UTM 샌드박스가 원본 경로를 다시 못 연다)" sh -c '
  f=deploy/dev-vm/create-vm.applescript
  test -f $f && grep -q "backend:apple" $f && grep -q "mode:shared" $f && grep -q "memory:" $f && grep -q "cpu cores:" $f \
    && grep -q "{source:seedFile}" $f && ! grep -q "removable:true" $f && ! grep -Eq "^[^-]*update registry" $f'

check "TASK-64#1 이미지는 버전·sha256 고정(Rocky 9 aarch64 GenericCloud)" sh -c '
  grep -Eq "^VM_IMAGE_URL_DEFAULT=\"https://dl.rockylinux.org/.*/Rocky-9-GenericCloud-Base-9\.[0-9]+-[0-9]+\.[0-9]+\.aarch64\.qcow2\"" scripts/_vm.sh \
    && grep -Eq "^VM_IMAGE_SHA256_DEFAULT=\"?[0-9a-f]{64}\"?" scripts/_vm.sh'
check "TASK-64#1 VM 크기: 메모리 5GB(5120MiB)·CPU 4·디스크 40GB" sh -c '
  grep -Eq "^VM_MEMORY_MIB=\"?\\\$\{VM_MEMORY_MIB:-5120\}\"?" scripts/_vm.sh && grep -Eq "^VM_CPUS=\"?\\\$\{VM_CPUS:-4\}\"?" scripts/_vm.sh \
    && grep -Eq "^VM_DISK_SIZE=\"?\\\$\{VM_DISK_SIZE:-40G\}\"?" scripts/_vm.sh'
check "TASK-64#1 이미지 캐시는 저장소 밖(~/.cache/rocky-dev)" grep -q 'VM_CACHE_DIR:-$HOME/.cache/rocky-dev' scripts/_vm.sh

t_create_ok() {
  fake_reset
  run_capture vm_run FAKE_VM_IP=192.168.64.9 ./scripts/vm.sh create
  rc_is 0 || return 1
  called "curl" && called "qemu-img convert -f qcow2 -O raw" && called "qemu-img resize -f raw" && called "40G" \
    && called "hdiutil makehybrid -iso -joliet -default-volume-name cidata" \
    && called "osascript $ROOT/deploy/dev-vm/create-vm.applescript rocky-dev 5120 4" && called "utmctl start --hide rocky-dev" || return 1
  test -f "$FAKE_HOME/.ssh/rocky-dev_ed25519" && test -f "$FAKE_HOME/.ssh/rocky-dev_ed25519.pub" || { echo "SSH 키 없음"; return 1; }
  test -f "$FAKE_HOME/cache/Rocky-9-GenericCloud-Base-9.8-20260525.0.aarch64.qcow2" || { echo "캐시에 이미지 없음"; ls -R "$FAKE_HOME/cache"; return 1; }
}
check "TASK-64#1 vm-create: 다운로드 → sha256 → raw 변환 + 40G → seed ISO(cidata) → AppleScript(rocky-dev 5120MiB CPU 4) → 시작" t_create_ok
t_create_exists() {
  fake_reset; echo stopped >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/vm.sh create
  rc_is 1 && has_text "이미 있다" && has_text "rocky-dev" && has_text "UTM" && not_called "osascript" && not_called "curl"
}
t_create_start_fail() {
  # utmctl start 가 오류를 내고도 0 으로 끝나는 경우(UTM 이 디스크·ISO 를 열지 못함) — IP 를 오래 기다리지 않고 바로 실패
  fake_reset
  run_capture vm_run FAKE_VM_IP=192.168.64.9 FAKE_START_NOOP=1 VM_START_TIMEOUT=2 ./scripts/vm.sh create
  rc_is 1 && has_text "켜지지 않았다" && has_text "UTM" && not_called "ssh"
}
check "TASK-64#1 VM 이 켜지지 않으면(utmctl start 오류) IP 를 기다리지 않고 바로 실패한다" t_create_start_fail
check "TASK-64#1 VM 이 이미 있으면 거부하고 다시 만드는 법을 안내한다(osascript·다운로드 없음)" t_create_exists
t_create_bad_sha() {
  fake_reset
  run_capture vm_run FAKE_IMAGE_BODY=TAMPERED ./scripts/vm.sh create
  rc_is 1 && has_text "sha256" && not_called "osascript" && not_called "qemu-img" || return 1
  [ ! -e "$FAKE_HOME/cache/Rocky-9-GenericCloud-Base-9.8-20260525.0.aarch64.qcow2" ] || { echo "틀린 이미지가 캐시에 남았다"; return 1; }
  fake_reset
  run_capture vm_run FAKE_CHECKSUM_SHA=0000000000000000000000000000000000000000000000000000000000000000 ./scripts/vm.sh create
  rc_is 1 && has_text "CHECKSUM" && not_called "osascript"
}
check "TASK-64#1 sha256 이 고정값과 다르거나 Rocky CHECKSUM 에 고정값이 없으면 실패하고 이미지를 지운다" t_create_bad_sha
t_create_no_qemu() {
  fake_reset
  local nb="$TMP/noqemu"; rm -rf "$nb"; mkdir -p "$nb"
  local c; for c in "$FAKE/bin/"*; do [ "$(basename "$c")" = qemu-img ] || ln -sf "$c" "$nb/"; done
  run_capture env PATH="$nb:/usr/bin:/bin" HOME="$FAKE_HOME" VM_HOST_OS=Darwin UTMCTL="$FAKE/bin/utmctl" VM_CACHE_DIR="$FAKE_HOME/cache" \
    VM_QEMU_IMG=/nonexistent ./scripts/vm.sh create
  rc_is 1 && has_text "qemu-img" && has_text "brew install qemu" && not_called "osascript"
}
check "TASK-64#1 qemu-img 가 없으면 설치 방법(brew install qemu)을 안내하고 실패한다" t_create_no_qemu
t_create_linux() {
  fake_reset
  run_capture env PATH="$FAKE/bin:$PATH" HOME="$FAKE_HOME" VM_HOST_OS=Linux UTMCTL="$FAKE/bin/utmctl" ./scripts/vm.sh create
  rc_is 1 && has_text "macOS" && not_called "osascript"
}
check "TASK-64#1 vm-create 는 맥에서만 돈다" t_create_linux
t_create_cloud_init_timeout() {
  # cloud-init status --wait 는 VM 안 timeout(기본 1200초 = 20분)으로 감싼다. ssh 는 keepalive 로 끊긴 연결을 알아챈다
  fake_reset
  run_capture vm_run FAKE_VM_IP=192.168.64.9 FAKE_CLOUD_INIT_RC=124 ./scripts/vm.sh create
  rc_is 1 && has_text "20분" && has_text "cloud-init" && called "sudo -n timeout 1200 cloud-init status --wait" \
    && called "ServerAliveInterval=15" && called "ServerAliveCountMax=4" || return 1
  fake_reset
  run_capture vm_run FAKE_VM_IP=192.168.64.9 FAKE_CLOUD_INIT_RC=1 ./scripts/vm.sh create
  rc_is 1 && has_text "cloud-init 이 실패했다" && lacks_text "20분"
}
check "TASK-64#1 cloud-init 완료 대기는 20분 제한(timeout)·ssh keepalive — 넘으면 시간 초과를 알리고 실패" t_create_cloud_init_timeout

# ================================================================ TASK-64#B1 권한 창은 vm-create 의 UTM 자동화 허용 1회, 실행 전 안내
t_b1_announce() {
  fake_reset
  run_capture vm_run FAKE_VM_IP=192.168.64.9 ./scripts/vm.sh create
  rc_is 0 || return 1
  local ann osa
  ann="$(grep -n "자동화" <<<"$OUT" | head -n1 | cut -d: -f1)"; osa="$(grep -n "FAKE-OSASCRIPT-RUN" <<<"$OUT" | head -n1 | cut -d: -f1)"
  [ -n "$ann" ] && [ -n "$osa" ] && [ "$ann" -lt "$osa" ] || { echo "안내($ann)가 osascript($osa)보다 먼저 나와야 한다"; echo "$OUT"; return 1; }
  has_text "허용"
}
check "TASK-64#B1 osascript 전에 'UTM 자동화 허용' 창이 한 번 뜬다고 안내한다" t_b1_announce
check "TASK-64#B1 맥 권한이 필요한 명령(맥의 sudo·open -a·launchctl·tccutil — VM 안 vm_ssh sudo -n 만 예외)을 쓰지 않고, osascript 는 vm_create 안에서만" sh -c '
  ! grep -nE "(^|[;&|[:space:]])(sudo|launchctl|tccutil)[[:space:]]|open -a" scripts/vm.sh scripts/_vm.sh | grep -v "vm_ssh sudo -n " || exit 1
  cat scripts/vm.sh scripts/_vm.sh | awk "/^vm_create\\(\\)/ { f = 1 } /^}/ { f = 0 } !/^[[:space:]]*#/ && /osascript/ { n++; if (!f) bad = 1 } END { exit (bad || n == 0) }"'

# ================================================================ TASK-64#2 cloud-init + 공유 설치 스크립트
t_user_data() {
  fake_reset
  run_capture vm_run FAKE_VM_IP=192.168.64.9 ./scripts/vm.sh create
  rc_is 0 || return 1
  local ud="$FAKE_STATE/seed/user-data" md="$FAKE_STATE/seed/meta-data" pub
  pub="$(cat "$FAKE_HOME/.ssh/rocky-dev_ed25519.pub")"
  head -n1 "$ud" | grep -qx '#cloud-config' && grep -qF "$pub" "$ud" && grep -q 'NOPASSWD:ALL' "$ud" \
    && grep -q "uid: $(id -u)" "$ud" && grep -q 'ssh_pwauth: false' "$ud" && grep -q 'lock_passwd: true' "$ud" \
    && grep -q 'deploy/rocky/install.sh' "$ud" && grep -q -- '--profile dev' "$ud" && grep -q 'encoding: b64' "$ud" \
    && grep -q 'local-hostname: rocky-dev' "$md" && grep -q 'instance-id:' "$md" || { echo "--- user-data:"; cat "$ud"; return 1; }
  # 설치 스크립트를 seed 에 그대로(base64) 넣는다 — 저장소 공유가 안 붙어도 설치는 돈다
  local b64; b64="$(base64 <deploy/rocky/install.sh | tr -d '\n')"
  tr -d '\n ' <"$ud" | grep -qF "$b64" || { echo "install.sh 본문이 seed 에 없다"; return 1; }
}
check "TASK-64#2 cloud-init: 전용 SSH 키·비밀번호 로그인 없음·NOPASSWD sudo·맥과 같은 uid·install.sh(--profile dev) 실행" t_user_data
I=deploy/rocky/install.sh
check "TASK-64#2 install.sh 는 실행 가능하고 bash 문법이 맞다" sh -c "test -x $I && bash -n $I"
check "TASK-64#2 install.sh 는 SELinux 를 끄지 않고 enforcing 을 확인한다" sh -c "
  ! grep -nE 'setenforce 0|SELINUX=(disabled|permissive)|selinux=0' $I && grep -q 'getenforce' $I && grep -q 'Enforcing' $I"
check "TASK-64#2 install.sh 고정 버전 + sha256: kubectl·kubeconform 0.8.0·yq·node·shellcheck·actionlint·terraform" sh -c "
  for v in KUBECTL KUBECONFORM YQ NODE SHELLCHECK ACTIONLINT TERRAFORM; do
    grep -Eq \"^\$v\"'_VERSION=\"?[0-9]+\.[0-9]+\.[0-9]+\"?' $I && grep -Eq \"^\$v\"'_SHA256=\"?[0-9a-f]{64}\"?' $I || { echo \"\$v 고정 없음\"; exit 1; }
  done
  grep -Eq '^KUBECONFORM_VERSION=\"?0\.8\.0\"?' $I && grep -q 'sha256sum -c' $I"
check "TASK-64#2 install.sh: podman·temurin-25·clang·llvm·libbpf-devel·bpftool·kernel-devel·git·make·rsync, podman.socket(사용자)·linger" sh -c "
  for p in podman temurin-25-jdk clang llvm libbpf-devel bpftool kernel-devel git make rsync; do grep -q \"\$p\" $I || { echo \"\$p 없음\"; exit 1; }; done
  grep -q 'enable-linger' $I && grep -q 'podman.socket' $I"
check "TASK-64#2 install.sh 는 AWS Rocky EC2 와 같이 쓴다(--profile server|dev), dev 만 저장소 공유 /repo(virtiofs, container_file_t)" sh -c "
  grep -q -- '--profile' $I && grep -q 'server' $I && grep -q 'virtiofs' $I && grep -q '/repo' $I && grep -q 'container_file_t' $I"
check "TASK-64#2 install.sh 의 Node 는 CI(ci.yml node-version)와 같은 메이저" sh -c "
  ci=\$(sed -nE 's/^[[:space:]]*node-version: \"?([0-9]+)\"?.*/\1/p' .github/workflows/ci.yml | head -n1)
  grep -Eq \"^NODE_VERSION=\\\"?\$ci\.\" $I"
check "TASK-64#2 install.sh 의 shellcheck·kubeconform 버전은 ci.yml 고정값과 같다" sh -c "
  for v in SHELLCHECK KUBECONFORM; do
    ci=\$(sed -nE \"s/^[[:space:]]*\${v}_VERSION: \\\"?([0-9.]+)\\\"?.*/\1/p\" .github/workflows/ci.yml | head -n1)
    loc=\$(sed -nE \"s/^\${v}_VERSION=\\\"?([0-9.]+)\\\"?.*/\1/p\" $I | head -n1)
    [ -n \"\$ci\" ] && [ \"\$ci\" = \"\$loc\" ] || { echo \"\$v ci=\$ci install=\$loc\"; exit 1; }
  done"

# install.sh 저장소 공유(dev_share_setup): ROCKY_INSTALL_LIB=1 로 함수만 읽고 가짜 mount·umount·mountpoint 로 부른다.
# 가짜 mount: -t virtiofs 는 FAKE_SHARE=1 일 때만 되고, fstab 의 bind 줄이면 대상(빈 디렉터리)을 원본(\040·\134·\011 을 푼 경로)으로 가는
# 심볼릭 링크로 바꾼다. 가짜 umount 는 대상을 빈 디렉터리로 되돌린다. FAKE_UMOUNT_BUSY=1 이면 umount -R 이 아래 마운트만 내리고 EBUSY.
IFAKE="$TMP/ifake"; IS="$TMP/istate"
mkdir -p "$IFAKE/bin"
cat >"$IFAKE/bin/mount" <<'SH'
#!/usr/bin/env bash
printf 'mount %s\n' "$*" >>"$FAKE_ISTATE/calls.log"
t="${*: -1}"
if [ "$1" = -t ]; then
  [ "${FAKE_SHARE:-0}" = 1 ] || exit 32
else
  line="$(awk -v p="$t" '$2 == p' "$FAKE_FSTAB" | tail -n1)"
  [ -n "$line" ] || exit 1
  read -r src _ type _ <<<"$line"
  case "$type" in
    virtiofs) [ "${FAKE_SHARE:-0}" = 1 ] || exit 32 ;;
    none) src="$(printf '%b' "${src//\\/\\0}")"; rmdir "$t" 2>/dev/null || exit 32; ln -s "$src" "$t" ;;
  esac
fi
echo "$t" >>"$FAKE_ISTATE/mounted"
SH
cat >"$IFAKE/bin/umount" <<'SH'
#!/usr/bin/env bash
printf 'umount %s\n' "$*" >>"$FAKE_ISTATE/calls.log"
t="${*: -1}"
under() { awk -v p="$t" 'index($0, p "/") == 1' "$FAKE_ISTATE/mounted"; }
while read -r m; do [ -L "$m" ] && { rm "$m"; mkdir "$m"; }; done < <(under)
awk -v p="$t" 'index($0, p "/") != 1' "$FAKE_ISTATE/mounted" >"$FAKE_ISTATE/m.tmp"; mv "$FAKE_ISTATE/m.tmp" "$FAKE_ISTATE/mounted"
[ "${FAKE_UMOUNT_BUSY:-0}" = 1 ] && { echo "umount: $t: target is busy." >&2; exit 32; }
grep -vxF -- "$t" "$FAKE_ISTATE/mounted" >"$FAKE_ISTATE/m.tmp"; mv "$FAKE_ISTATE/m.tmp" "$FAKE_ISTATE/mounted"
rm -rf "$t"; mkdir "$t"
SH
cat >"$IFAKE/bin/mountpoint" <<'SH'
#!/usr/bin/env bash
grep -qxF -- "${*: -1}" "$FAKE_ISTATE/mounted"
SH
cat >"$IFAKE/bin/runuser" <<'SH'
#!/usr/bin/env bash
shift 3; "$@"
SH
cat >"$IFAKE/bin/systemctl" <<'SH'
#!/usr/bin/env bash
printf '%s %s\n' "${0##*/}" "$*" >>"$FAKE_ISTATE/calls.log"
SH
cp "$IFAKE/bin/systemctl" "$IFAKE/bin/chown"
chmod +x "$IFAKE/bin/"*
SHARE_OPTS="rw,nofail,context=system_u:object_r:container_file_t:s0"
ist_reset() {
  rm -rf "$IS"; mkdir -p "$IS/mnt/share" "$IS/repo"
  echo "UUID=f871 / xfs defaults 0 1" >"$IS/fstab"; : >"$IS/mounted"; : >"$IS/calls.log"
}
# 공유에 저장소 폴더 하나(Apple 백엔드 배치): mk_share_repo <폴더 이름>
mk_share_repo() { mkdir -p "$IS/mnt/share/$1/frontend" "$IS/mnt/share/$1/backend"; touch "$IS/mnt/share/$1/Makefile"; }
inst_share() {
  env PATH="$IFAKE/bin:$PATH" FAKE_ISTATE="$IS" FAKE_FSTAB="$IS/fstab" ROCKY_INSTALL_LIB=1 "$@" bash -c '
    . deploy/rocky/install.sh
    FSTAB="$FAKE_FSTAB"; SHARE_STAGE="$FAKE_ISTATE/mnt/share"; SHARE_PATH="$FAKE_ISTATE/repo"; TARGET_USER=rocky
    dev_share_setup "$FAKE_ISTATE/overlay"'
}
fstab_has() { grep -qxF -- "$1" "$IS/fstab" || { echo "fstab 에 없음: $1"; echo "--- fstab:"; cat "$IS/fstab"; echo "--- output:"; echo "$OUT"; return 1; }; }
fstab_lacks() { ! grep -qF -- "$1" "$IS/fstab" || { echo "fstab 에 있으면 안 됨: $1"; cat "$IS/fstab"; return 1; }; }
called_i() { grep -qF -- "$1" "$IS/calls.log" || { echo "호출 없음: $1"; cat "$IS/calls.log"; return 1; }; }
same_dir() { [ "$(cd "$1" && pwd -P)" = "$(cd "$2" && pwd -P)" ] || { echo "$1 → $(cd "$1" && pwd -P) (기대 $2)"; return 1; }; }
repo_line() { printf '%s %s none bind,nofail,x-systemd.requires-mounts-for=%s 0 0' "$1" "$IS/repo" "$IS/mnt/share"; }
# /repo bind 줄이 하나이고 덮기 줄보다 앞에 있다
repo_line_first() {
  local r o
  [ "$(grep -c " $IS/repo " "$IS/fstab")" = 1 ] || { echo "/repo 줄이 하나가 아니다"; cat "$IS/fstab"; return 1; }
  r="$(grep -n " $IS/repo " "$IS/fstab" | cut -d: -f1)"; o="$(grep -n " $IS/repo/" "$IS/fstab" | head -n1 | cut -d: -f1)"
  [ -z "$o" ] || [ "$r" -lt "$o" ] || { echo "/repo 줄이 덮기 줄보다 뒤다"; cat "$IS/fstab"; return 1; }
}
# 맥에서 공유 폴더 이름을 바꾼 흉내: 실제 bind 는 디렉터리를 따라가므로 가짜 bind(심볼릭 링크)도 새 이름으로 고친다
share_rename() { mv "$IS/mnt/share/$1" "$IS/mnt/share/$2"; rm "$IS/repo"; ln -s "$IS/mnt/share/$2" "$IS/repo"; }
snap() { cp "$IS/fstab" "$IS/fstab.snap"; cp "$IS/mounted" "$IS/mounted.snap"; }
unchanged() { diff "$IS/fstab.snap" "$IS/fstab" && diff "$IS/mounted.snap" "$IS/mounted" || { echo "--- output:"; echo "$OUT"; return 1; }; }
t_share_nested() {
  # Apple Virtualization 백엔드: 태그 루트 아래에 고른 폴더 이름의 디렉터리 하나 → 그것을 /repo 로 bind, 그 아래 VM 로컬 덮기
  ist_reset; mk_share_repo react-x
  run_capture inst_share FAKE_SHARE=1
  rc_is 0 && fstab_has "share $IS/mnt/share virtiofs $SHARE_OPTS 0 0" && fstab_has "$(repo_line "$IS/mnt/share/react-x")" \
    && fstab_has "$IS/overlay/frontend-node_modules $IS/repo/frontend/node_modules none bind,nofail,x-systemd.requires-mounts-for=$IS/repo 0 0" \
    && fstab_has "$IS/overlay/backend-build $IS/repo/backend/build none bind,nofail,x-systemd.requires-mounts-for=$IS/repo 0 0" \
    && fstab_has "$IS/overlay/backend-.gradle $IS/repo/backend/.gradle none bind,nofail,x-systemd.requires-mounts-for=$IS/repo 0 0" \
    && repo_line_first || return 1
  [ -f "$IS/repo/Makefile" ] || { echo "/repo 에 저장소 파일이 없다"; return 1; }
  same_dir "$IS/repo/frontend/node_modules" "$IS/overlay/frontend-node_modules" && same_dir "$IS/repo/backend/build" "$IS/overlay/backend-build" || return 1
  # 공유 아래 마운트 지점도 사용자 소유(공유가 나중에 빠져도 rsync 로 맞출 수 있게)
  called_i "chown rocky: $IS/repo" || return 1
  # 다시 돌려도 fstab·마운트가 그대로
  snap; run_capture inst_share FAKE_SHARE=1
  rc_is 0 && unchanged
}
check "TASK-64#1 install.sh 공유: Apple 백엔드(태그 루트 아래 폴더 하나) → /mnt/share/<폴더> 를 /repo(사용자 소유 마운트 지점)로 bind + VM 로컬 덮기, 다시 돌려도 그대로" t_share_nested
t_share_root_repo() {
  # 태그 루트가 곧 저장소(.git·Makefile)면 루트를 bind
  ist_reset; mkdir -p "$IS/mnt/share/.git" "$IS/mnt/share/frontend" "$IS/mnt/share/docs"; touch "$IS/mnt/share/Makefile"
  run_capture inst_share FAKE_SHARE=1
  rc_is 0 && fstab_has "$(repo_line "$IS/mnt/share")" && fstab_lacks "$IS/mnt/share/docs" && [ -f "$IS/repo/Makefile" ] \
    && same_dir "$IS/repo/frontend/node_modules" "$IS/overlay/frontend-node_modules"
}
check "TASK-64#1 install.sh 공유: 태그 루트가 저장소(.git·Makefile)면 루트를 /repo 로 bind" t_share_root_repo
t_share_space() {
  # 폴더 이름의 공백·역슬래시는 fstab 에 \040·\134 로 쓰고 mount 가 풀어 같은 디렉터리를 붙인다
  ist_reset; mk_share_repo 'my repo\x'
  run_capture inst_share FAKE_SHARE=1
  rc_is 0 && fstab_has "$(repo_line "$IS/mnt/share/my\\040repo\\134x")" && [ -f "$IS/repo/Makefile" ] || return 1
  snap; run_capture inst_share FAKE_SHARE=1
  rc_is 0 && unchanged
}
check "TASK-64#1 install.sh 공유: 폴더 이름의 공백·역슬래시는 fstab 이스케이프(\\040·\\134)로 다시 돌려도 그대로" t_share_space
t_share_ambiguous() {
  # 저장소 표시도 없고 하위 디렉터리가 여럿이거나, 하나뿐이어도 저장소(.git·Makefile)가 아니면 아무것도 바꾸지 않고 실패
  ist_reset; mkdir -p "$IS/mnt/share/a" "$IS/mnt/share/b"; snap
  run_capture inst_share FAKE_SHARE=1
  rc_is 1 && has_text "저장소를 고르지 못했다" && has_text "a b" && has_text "아무것도 바꾸지 않았다" && unchanged || return 1
  ist_reset; mkdir -p "$IS/mnt/share/Downloads/x"; snap
  run_capture inst_share FAKE_SHARE=1
  rc_is 1 && has_text "저장소를 고르지 못했다" && unchanged || return 1
  # 옛 배치(share 를 /repo 에 바로 마운트) 이전 중이어도 옛 줄·마운트를 그대로 둔다
  ist_reset; mkdir -p "$IS/mnt/share/a" "$IS/mnt/share/b" "$IS/repo/a" "$IS/repo/b"
  echo "share $IS/repo virtiofs $SHARE_OPTS 0 0" >>"$IS/fstab"; echo "$IS/repo" >"$IS/mounted"; snap
  run_capture inst_share FAKE_SHARE=1
  rc_is 1 && has_text "저장소를 고르지 못했다" && unchanged && [ -d "$IS/repo/a" ] && ! grep -q "^umount" "$IS/calls.log" || { calls_i; return 1; }
}
calls_i() { echo "--- calls:"; cat "$IS/calls.log"; }
check "TASK-64#1 install.sh 공유: 저장소를 고를 수 없으면(하위 디렉터리 여럿·저장소 아님, 옛 배치 이전 중 포함) fstab·마운트를 그대로 두고 실패" t_share_ambiguous
t_share_migrate() {
  # 옛 배치(share 를 /repo 에 바로 마운트)의 fstab 줄을 내린 뒤 스테이징 + bind 로(옛 마운트 안에서 저장소를 고른다)
  ist_reset; mk_share_repo react-x; mkdir -p "$IS/repo/react-x"; touch "$IS/repo/react-x/Makefile"
  echo "share $IS/repo virtiofs $SHARE_OPTS 0 0" >>"$IS/fstab"; echo "$IS/repo" >"$IS/mounted"
  run_capture inst_share FAKE_SHARE=1
  rc_is 0 && fstab_lacks "share $IS/repo virtiofs" && called_i "umount -R $IS/repo" \
    && fstab_has "share $IS/mnt/share virtiofs $SHARE_OPTS 0 0" && fstab_has "$(repo_line "$IS/mnt/share/react-x")" \
    && [ -f "$IS/repo/Makefile" ] && repo_line_first
}
check "TASK-64#1 install.sh 공유: 옛 fstab 줄(share /repo virtiofs)을 내리고 /mnt/share + bind 로 바꾼다" t_share_migrate
t_share_busy() {
  # /repo 를 내리지 못하면(EBUSY — umount -R 이 덮기만 내렸다) 덮기를 다시 붙이고 fstab 은 그대로 둔 채 실패
  ist_reset; mkdir -p "$IS/repo/react-x/frontend" "$IS/repo/react-x/backend"; touch "$IS/repo/react-x/Makefile"; mk_share_repo react-x
  echo "share $IS/repo virtiofs $SHARE_OPTS 0 0" >>"$IS/fstab"; echo "$IS/repo" >"$IS/mounted"; snap
  run_capture inst_share FAKE_SHARE=1 FAKE_UMOUNT_BUSY=1
  rc_is 1 && has_text "내리지 못했다" && has_text "fstab 은 바꾸지 않았다" && diff "$IS/fstab.snap" "$IS/fstab" && grep -qxF "$IS/repo" "$IS/mounted" || return 1
  # 이미 새 배치인데 다른 폴더로 바꾸다 EBUSY: 덮기(node_modules·build)를 다시 붙여 맥 공유로 새지 않게
  ist_reset; mk_share_repo react-x
  run_capture inst_share FAKE_SHARE=1
  rc_is 0 || return 1
  share_rename react-x react-y; snap
  run_capture inst_share FAKE_SHARE=1 FAKE_UMOUNT_BUSY=1
  rc_is 1 && has_text "fstab 은 바꾸지 않았다" && diff "$IS/fstab.snap" "$IS/fstab" \
    && grep -qxF "$IS/repo/frontend/node_modules" "$IS/mounted" && grep -qxF "$IS/repo/backend/build" "$IS/mounted"
}
check "TASK-64#1 install.sh 공유: /repo 를 내리지 못하면(EBUSY) 덮기를 다시 붙이고 fstab 을 바꾸지 않은 채 실패" t_share_busy
t_share_renamed() {
  # 맥 폴더 이름이 바뀐 뒤 다시 돌리면 /repo 줄을 새 폴더로 바꾸고(덮기 줄 앞) 다시 붙인다
  ist_reset; mk_share_repo react-x
  run_capture inst_share FAKE_SHARE=1
  rc_is 0 || return 1
  share_rename react-x react-y
  run_capture inst_share FAKE_SHARE=1
  rc_is 0 && fstab_has "$(repo_line "$IS/mnt/share/react-y")" && fstab_lacks "react-x" && repo_line_first && [ -f "$IS/repo/Makefile" ] \
    && same_dir "$IS/repo/frontend/node_modules" "$IS/overlay/frontend-node_modules"
}
check "TASK-64#1 install.sh 공유: 공유 폴더 이름이 바뀐 뒤 다시 돌리면 /repo bind 를 새 폴더로 바꾼다" t_share_renamed
t_share_none() {
  # 공유가 없으면(rsync 방식) /repo 는 VM 로컬 그대로 사용자 소유, bind 없음. 옛 virtiofs 줄은 지운다
  ist_reset; echo "share $IS/repo virtiofs $SHARE_OPTS 0 0" >>"$IS/fstab"
  run_capture inst_share FAKE_SHARE=0
  rc_is 0 && has_text "rsync" && fstab_has "share $IS/mnt/share virtiofs $SHARE_OPTS 0 0" && fstab_lacks " $IS/repo " \
    && called_i "chown rocky: $IS/repo" && [ -d "$IS/repo" ] && [ ! -L "$IS/repo" ]
}
check "TASK-64#1 install.sh 공유: 공유가 없으면 /repo 는 VM 로컬(사용자 소유, rsync 방식)" t_share_none

# ================================================================ TASK-64#3 ~/.ssh/config Include + make vm-*
for t in vm-create vm-start vm-stop vm-status vm-ssh; do
  check "TASK-64#3 make $t 타깃" sh -c "grep -Eq '^$t:' Makefile"
done
t_ssh_config() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  printf 'Host github.com\n  User git\n' >"$FAKE_HOME/.ssh/config"
  run_capture vm_run FAKE_VM_IP=192.168.64.7 ./scripts/vm.sh ssh-config
  rc_is 0 || return 1
  local cfg="$FAKE_HOME/.ssh/config" inc="$FAKE_HOME/.ssh/config.d/rocky-dev"
  head -n1 "$cfg" | grep -qx 'Include config.d/rocky-dev' || { echo "Include 가 첫 줄이 아니다"; cat "$cfg"; return 1; }
  grep -q 'Host github.com' "$cfg" && [ "$(wc -l <"$cfg" | tr -d ' ')" = 3 ] || { echo "원래 설정이 바뀌었다"; cat "$cfg"; return 1; }
  bak_count() { local f n=0; for f in "$FAKE_HOME"/.ssh/config.bak-rocky-dev*; do [ -e "$f" ] && n=$((n + 1)); done; echo "$n"; }
  [ "$(bak_count)" = 1 ] || { echo "백업 없음"; ls -a "$FAKE_HOME/.ssh"; return 1; }
  grep -q '^Host rocky-dev$' "$inc" && grep -q 'HostName 192.168.64.7' "$inc" && grep -q 'IdentityFile ~/.ssh/rocky-dev_ed25519' "$inc" \
    && grep -q 'IdentitiesOnly yes' "$inc" && grep -q 'UserKnownHostsFile ~/.ssh/known_hosts.rocky-dev' "$inc" || { cat "$inc"; return 1; }
  # 두 번째(IP 가 바뀜): Include 는 늘지 않고 include 파일만 새 IP
  run_capture vm_run FAKE_VM_IP=192.168.64.8 ./scripts/vm.sh ssh-config
  rc_is 0 && [ "$(grep -c 'Include config.d/rocky-dev' "$cfg")" = 1 ] && grep -q 'HostName 192.168.64.8' "$inc" \
    && [ "$(bak_count)" = 1 ]
}
check "TASK-64#3 ~/.ssh/config 는 백업 후 Include 한 줄만 맨 위에 더하고, Host rocky-dev 는 config.d/rocky-dev 에(다시 돌려도 한 줄)" t_ssh_config
t_ssh_config_leases() {
  # Apple 백엔드는 게스트 에이전트가 없어 utmctl ip-address 가 실패한다 — 맥의 DHCP 임대 파일(/var/db/dhcpd_leases)에서 이름으로 찾는다
  fake_reset; echo started >"$FAKE_STATE/vm"
  cat >"$FAKE_STATE/leases" <<'EOF'
{
	name=rocky-dev
	ip_address=192.168.64.3
	hw_address=1,aa:bb:cc:dd:ee:01
	lease=0x60000000
}
{
	name=other
	ip_address=192.168.64.4
	lease=0x70000000
}
{
	name=rocky-dev
	ip_address=192.168.64.5
	hw_address=1,aa:bb:cc:dd:ee:02
	lease=0x65000000
}
EOF
  run_capture vm_run ./scripts/vm.sh ssh-config
  rc_is 0 && grep -q 'HostName 192.168.64.5' "$FAKE_HOME/.ssh/config.d/rocky-dev"
}
check "TASK-64#3 IP 는 utmctl ip-address, 안 되면 DHCP 임대 파일의 최신 rocky-dev 항목" t_ssh_config_leases
t_ssh_config_no_file() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VM_IP=192.168.64.7 ./scripts/vm.sh ssh-config
  rc_is 0 && head -n1 "$FAKE_HOME/.ssh/config" | grep -qx 'Include config.d/rocky-dev' \
    && [ -n "$(find "$FAKE_HOME/.ssh/config" -perm 600)" ]
}
check "TASK-64#3 ~/.ssh/config 가 없으면 Include 한 줄로 만든다(600)" t_ssh_config_no_file
t_vm_start() {
  fake_reset; echo stopped >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VM_IP=192.168.64.7 ./scripts/vm.sh start
  rc_is 0 && called "utmctl start --hide rocky-dev" && grep -q 'HostName 192.168.64.7' "$FAKE_HOME/.ssh/config.d/rocky-dev" && called "ssh" || return 1
  fake_reset
  run_capture vm_run ./scripts/vm.sh start
  rc_is 1 && has_text "make vm-create"
}
check "TASK-64#3 vm-start: 켜고 IP 로 ssh 설정을 다시 쓰고 ssh 가 될 때까지 기다린다. VM 이 없으면 make vm-create 안내" t_vm_start
t_vm_stop() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/vm.sh stop
  rc_is 0 && called "utmctl stop --hide rocky-dev --request" || return 1
  fake_reset; echo stopped >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/vm.sh stop
  rc_is 0 && not_called "stop rocky-dev"
}
check "TASK-64#3 vm-stop: 게스트에 종료를 요청(--request)한다. 이미 꺼져 있으면 아무것도 하지 않는다" t_vm_stop
t_vm_status() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VM_IP=192.168.64.7 ./scripts/vm.sh status
  rc_is 0 && has_text "started" && has_text "192.168.64.7" && has_text "MiB" || return 1
  fake_reset
  run_capture vm_run ./scripts/vm.sh status
  rc_is 0 && has_text "없다" && has_text "make vm-create"
}
check "TASK-64#3 vm-status: 상태·IP·메모리(VM·맥), 없으면 make vm-create 안내" t_vm_status
t_vm_ssh() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/vm.sh ssh
  rc_is 0 && called "ssh rocky-dev" || return 1
  fake_reset; echo stopped >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/vm.sh ssh
  rc_is 1 && has_text "make vm-start" && not_called "ssh rocky-dev" && not_called "start rocky-dev"
}
check "TASK-64#3 vm-ssh: ssh rocky-dev, 꺼져 있으면 켜지 않고 make vm-start 안내" t_vm_ssh

# ================================================================ TASK-64#4 · B2 사전 검사
t_preflight_ok() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VM_MEM_KB=4900000 ./scripts/vm.sh preflight
  rc_is 0 && has_text "VM 메모리" && has_text "4785 MiB" && has_text "맥 여유 메모리" && called "meminfo" && not_called "start rocky-dev"
}
check "TASK-64#4 사전 검사: VM 메모리(/proc/meminfo)·맥 여유 메모리(vm_stat)를 재서 출력한다" t_preflight_ok
t_b2_stopped() {
  local s
  for s in stopped paused; do
    fake_reset; echo "$s" >"$FAKE_STATE/vm"
    run_capture vm_run ./scripts/vm.sh preflight
    rc_is 1 && has_text "make vm-start" && not_called "start rocky-dev" && not_called "ssh" || return 1
  done
  fake_reset
  run_capture vm_run ./scripts/vm.sh preflight
  rc_is 1 && has_text "make vm-create" && not_called "osascript"
}
check "TASK-64#B2 VM 이 꺼져 있으면 켜지 않고 make vm-start 를 안내하며 실패한다(없으면 make vm-create)" t_b2_stopped
t_preflight_low_mem() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VM_MEM_KB=3900000 ./scripts/vm.sh preflight
  rc_is 1 && has_text "VM 메모리" && has_text "4500" || return 1
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_SSH_FAIL=1 ./scripts/vm.sh preflight
  rc_is 1 && has_text "ssh" && has_text "make vm-start"
}
check "TASK-64#4 VM 메모리가 vm-min(4500MiB) 미만이거나 ssh 가 안 되면 실패한다" t_preflight_low_mem
t_vm_min_headroom() {
  # 5GB VM 의 실측 MemTotal 4649MiB(커널 예약) — 경계가 실측에 너무 붙으면 커널 업데이트만으로 실패한다(100MiB 이상 여유).
  # README 의 숫자는 budgets.txt 와 같아야 한다
  local v; v="$(awk '$1 == "vm-min" { print $2 }' deploy/k8s/budgets.txt)"
  [ -n "$v" ] && [ $((4649 - v)) -ge 100 ] && [ "$v" -ge 4096 ] || { echo "vm-min=$v (실측 4649 − 100 이하, 4096 이상이어야)"; return 1; }
  grep -qF "\`vm-min\`($v MiB)" README.md || { echo "README 의 vm-min 숫자가 budgets.txt($v)와 다르다"; grep -n "vm-min" README.md; return 1; }
}
check "TASK-64#4 vm-min 은 실측(4649MiB)보다 100MiB 이상 낮고, README 숫자와 budgets.txt 가 같다" t_vm_min_headroom
t_preflight_mac_low() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_MAC_FREE_PAGES=1000 FAKE_MAC_INACTIVE_PAGES=1000 ./scripts/vm.sh preflight
  rc_is 0 && has_text "WARNING" && has_text "맥 여유 메모리"
}
check "TASK-64#4 맥 여유 메모리가 mac-free-min 미만이면 경고한다" t_preflight_mac_low
check "TASK-64#4 예산 파일에 vm-min·mac-free-min" sh -c 'grep -Eq "^vm-min [0-9]+" deploy/k8s/budgets.txt && grep -Eq "^mac-free-min [0-9]+" deploy/k8s/budgets.txt'
t_preflight_linux() {
  fake_reset
  run_capture env PATH="$FAKE/bin:$PATH" HOME="$FAKE_HOME" VM_HOST_OS=Linux UTMCTL="$FAKE/bin/utmctl" ./scripts/vm.sh preflight
  rc_is 0 && not_called "utmctl"
}
check "TASK-64#4 리눅스(VM 안·CI)에서는 사전 검사가 할 일이 없다" t_preflight_linux
check "TASK-64#4 테스트·k3d 진입점이 같은 사전 검사를 거친다(test.sh·test-integration.sh·_k3d.sh·Makefile backend-*)" sh -c '
  grep -q "vm_proxy_if_mac" scripts/test.sh && grep -q "vm_proxy_if_mac" scripts/test-integration.sh && grep -q "vm_preflight" scripts/_k3d.sh \
    && grep -q "vm_proxy_if_mac\|vm_preflight" scripts/_vm.sh && make -n backend-test | grep -q "scripts/vm.sh" && make -n backend-build | grep -q "scripts/vm.sh"'
t_k3d_preflight_vm() {
  # k3d 사전 검사(up·profile·smoke)도 맥에서는 VM 검사를 먼저 지난다 — VM 이 꺼져 있으면 docker 를 보기 전에 실패
  fake_reset; echo stopped >"$FAKE_STATE/vm"
  run_capture vm_run bash -c 'source scripts/_k3d.sh; k3d_preflight core'
  rc_is 1 && has_text "make vm-start" && not_called "start rocky-dev"
}
check "TASK-64#4 TASK-64#B2 k3d_preflight 도 VM 사전 검사를 거친다" t_k3d_preflight_vm

# ================================================================ TASK-64#5 맥의 make test → VM 안에서
t_proxy() {
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/test.sh
  rc_is 0 && called "cd '/repo' || exit 1" && called "setsid bash -lc 'make test'" && has_text "FAKE-REMOTE-MAKE" && lacks_text "==> repo" || return 1
  # 저장소는 rsync 로 VM 로컬 /repo 에 맞춘다(지운 파일도), 플랫폼별 산출물·비밀 파일은 빼고. 그 다음에 make
  called "rsync -a --delete" && called "$ROOT/ rocky-dev:/repo/" && called "--exclude=node_modules/" && called "--exclude=/backend/build/" \
    && called "--exclude=.terraform/" && called "--exclude=/.claude/worktrees/" || return 1
  # rsync(VM 쪽)도 같은 잠금을 잡고(-n: 잡혀 있으면 바로 실패), make 전에 잠금을 먼저 확인한다
  called "--rsync-path=mkdir -p '/repo' && flock -n '/tmp/board-repo-test.lock' rsync" && called "flock -n -E 75 '/tmp/board-repo-test.lock' true" || return 1
  [ "$(grep -n '^rsync' "$FAKE_STATE/calls.log" | cut -d: -f1)" -lt "$(grep -n "make test" "$FAKE_STATE/calls.log" | cut -d: -f1)" ] || { echo "rsync 가 make 보다 먼저여야 한다"; calls; return 1; }
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_MAKE_RC=3 ./scripts/test.sh
  rc_is 3 || return 1
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/test-integration.sh
  rc_is 0 && called "make test-integration"
}
check "TASK-64#5 맥의 make test·test-integration 은 사전 검사 → rsync(작업 트리 → VM /repo) → ssh rocky-dev 'cd /repo && make …', 종료 코드 그대로" t_proxy
t_proxy_virtiofs() {
  # UTM 에서 공유 디렉터리를 붙여 /repo 가 VirtioFS 로 마운트돼 있으면 rsync 없이 바로
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VIRTIOFS=1 ./scripts/test.sh
  rc_is 0 && not_called "rsync" && called "make test" && called "findmnt -n -o FSTYPE -M '/repo'" || return 1
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_RSYNC_RC=12 ./scripts/test.sh
  rc_is 1 && has_text "rsync" && not_called "make test"
}
check "TASK-64#5 /repo 가 VirtioFS 공유면 rsync 를 건너뛰고, rsync 가 실패하면 make 를 돌리지 않는다" t_proxy_virtiofs
t_proxy_virtiofs_overlay() {
  # VirtioFS 공유를 나중에 붙여 VM 로컬 bind(node_modules·backend/build·.gradle)가 없으면 설치 스크립트 재실행 명령을 안내한다(경고, 계속 진행)
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VIRTIOFS=1 FAKE_OVERLAY_MISSING="frontend/node_modules" ./scripts/test.sh
  rc_is 0 && has_text "WARNING" && has_text "frontend/node_modules" \
    && has_text "sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo" && called "make test" || return 1
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_VIRTIOFS=1 ./scripts/test.sh
  rc_is 0 && lacks_text "WARNING" || return 1
  # rsync 방식(공유 없음)에서는 bind 를 확인하지 않는다
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_OVERLAY_MISSING="frontend/node_modules" ./scripts/test.sh
  rc_is 0 && lacks_text "WARNING"
}
check "TASK-64#5 VirtioFS 공유인데 VM 로컬 bind 덮기가 없으면 install.sh 재실행 명령을 안내한다" t_proxy_virtiofs_overlay
t_proxy_share_unbound() {
  # 공유를 VM 을 만든 뒤에 붙여 태그만 /mnt/share 에 있고 /repo bind 는 없으면: rsync 로 계속하되 재실행 명령을 안내한다
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_STAGE_MOUNTED=1 ./scripts/test.sh
  rc_is 0 && has_text "WARNING" && has_text "/mnt/share" && has_text "저장소를 고를 수 없다" \
    && has_text "sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo" \
    && called "rsync -a --delete" && called "make test"
}
check "TASK-64#1 공유가 /mnt/share 에만 있고 /repo bind 가 없으면 rsync 로 계속하며 install.sh 재실행 명령을 안내한다" t_proxy_share_unbound
t_proxy_repo_ro() {
  # 공유가 빠져 /repo 가 root 소유 마운트 지점만 남았으면 rsync(권한 오류) 전에 이유와 재실행 명령을 알리고 실패
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_REPO_RO=1 ./scripts/test.sh
  rc_is 1 && has_text "쓸 수 없다" \
    && has_text "sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo" \
    && not_called "rsync" && not_called "make test"
}
check "TASK-64#1 /repo 를 쓸 수 없으면(공유가 빠진 뒤 root 소유) rsync 전에 install.sh 재실행 명령을 안내하고 실패한다" t_proxy_repo_ro
t_proxy_lock_busy() {
  # 다른 실행(취소된 호출이 남긴 원격 make 등)이 잠금을 잡고 있으면 rsync 도 make 도 하지 않고 바로 실패한다
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run FAKE_LOCK_BUSY=1 ./scripts/test.sh
  rc_is 1 && has_text "다른" && has_text "/tmp/board-repo-test.lock" && not_called "rsync" && not_called "make test"
}
check "TASK-64#5 VM 안에서 다른 실행이 잠금(/tmp/board-repo-test.lock)을 잡고 있으면 rsync·make 없이 바로 실패한다" t_proxy_lock_busy
t_proxy_stopped() {
  fake_reset; echo stopped >"$FAKE_STATE/vm"
  run_capture vm_run ./scripts/test.sh
  rc_is 1 && has_text "make vm-start" && not_called "make test" && not_called "start rocky-dev"
}
check "TASK-64#5 TASK-64#B2 VM 이 꺼져 있으면 make test 는 켜지 않고 실패한다" t_proxy_stopped
t_proxy_worktree() {
  # 저장소 아래의 git worktree(.claude/worktrees/x)에서 돌리면 VM 안에서도 같은 상대 경로
  fake_reset; echo started >"$FAKE_STATE/vm"
  local wt="$TMP/share/.claude/worktrees/x"; mkdir -p "$wt/scripts"
  cp scripts/test.sh scripts/_vm.sh "$wt/scripts/"; cp -R deploy "$wt/" 2>/dev/null || true
  run_capture vm_run VM_SHARE_DIR="$TMP/share" "$wt/scripts/test.sh"
  rc_is 0 && called "cd '/repo/.claude/worktrees/x'" && called "$wt/ rocky-dev:/repo/.claude/worktrees/x/" || return 1
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run VM_SHARE_DIR="$TMP/elsewhere" "$wt/scripts/test.sh"
  rc_is 1 && has_text "공유" && not_called "make test"
}
check "TASK-64#5 공유 디렉터리 아래의 경로는 /repo 기준 상대 경로로 바꾸고, 밖이면 실패한다" t_proxy_worktree
t_proxy_git_worktree() {
  # 진짜 git worktree: .git 은 "gitdir: <맥 절대 경로>/.git/worktrees/x" 파일이다. VM 안 git 이 이 경로를 읽도록
  # (1) VM 에 맥 공유 경로 → /repo 심볼릭 링크(VM 안 sudo -n), (2) 공통 .git 을 VM /repo/.git 에 rsync, (3) worktree 를 rsync — 그 다음 make
  fake_reset; echo started >"$FAKE_STATE/vm"
  local sh; mkdir -p "$TMP/gshare"; sh="$(cd "$TMP/gshare" && pwd -P)"
  local wt="$sh/.claude/worktrees/x"; rm -rf "$sh"/.git "$sh"/.claude; mkdir -p "$wt/scripts" "$sh/.git/worktrees/x"
  cp scripts/test.sh scripts/_vm.sh "$wt/scripts/"; cp -R deploy "$wt/" 2>/dev/null || true
  printf '../..\n' >"$sh/.git/worktrees/x/commondir"; printf '%s/.git\n' "$wt" >"$sh/.git/worktrees/x/gitdir"
  printf 'gitdir: %s/.git/worktrees/x\n' "$sh" >"$wt/.git"
  run_capture vm_run VM_SHARE_DIR="$sh" "$wt/scripts/test.sh"
  # 링크 명령은 VM 안 sudo -n sh -c '…' 로 한 번 더 인용되므로 줄에 ln -sfn·/repo·공유 경로가 함께 있는지만 본다
  linked() { grep "ln -sfn" "$FAKE_STATE/calls.log" | grep -F "/repo" | grep -qF "$sh" || { echo "링크 명령 없음"; calls; return 1; }; }
  rc_is 0 && linked && called "$sh/.git/ rocky-dev:/repo/.git/" && called "$wt/ rocky-dev:/repo/.claude/worktrees/x/" \
    && called "cd '/repo/.claude/worktrees/x'" || return 1
  local ln com wtl mk
  ln="$(grep -n 'ln -sfn' "$FAKE_STATE/calls.log" | head -n1 | cut -d: -f1)"; com="$(grep -nF "$sh/.git/ rocky-dev:" "$FAKE_STATE/calls.log" | head -n1 | cut -d: -f1)"
  wtl="$(grep -nF "$wt/ rocky-dev:" "$FAKE_STATE/calls.log" | head -n1 | cut -d: -f1)"; mk="$(grep -n "setsid bash -lc 'make test'" "$FAKE_STATE/calls.log" | head -n1 | cut -d: -f1)"
  [ "$ln" -lt "$mk" ] && [ "$com" -lt "$mk" ] && [ "$wtl" -lt "$mk" ] || { echo "링크($ln)·공통 .git($com)·worktree($wtl) 가 make($mk) 보다 먼저여야"; calls; return 1; }
  # VirtioFS 공유면 rsync 없이 링크만
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run VM_SHARE_DIR="$sh" FAKE_VIRTIOFS=1 "$wt/scripts/test.sh"
  rc_is 0 && linked && not_called "rsync" || return 1
  # 공통 .git 이 공유 디렉터리 밖이면(다른 저장소의 worktree) VM 안 git 이 못 읽는다 — 분명히 거부
  fake_reset; echo started >"$FAKE_STATE/vm"
  local other; mkdir -p "$TMP/other/.git/worktrees/x"; other="$(cd "$TMP/other" && pwd -P)"
  printf '../..\n' >"$other/.git/worktrees/x/commondir"; printf 'gitdir: %s/.git/worktrees/x\n' "$other" >"$wt/.git"
  run_capture vm_run VM_SHARE_DIR="$sh" "$wt/scripts/test.sh"
  rc_is 1 && has_text "worktree" && has_text "$other/.git" && not_called "rsync" && not_called "make test"
}
check "TASK-64#5 git worktree(.git 파일의 맥 절대 gitdir): VM 에 맥 경로 → /repo 링크 + 공통 .git 동기화, 공유 밖 공통 .git 은 거부" t_proxy_git_worktree
# 원격 실행 스크립트(vm_remote_script)를 이 호스트에서 직접 돌려 본다 — flock·setsid(util-linux)가 있는 리눅스(VM 안·CI)에서만
if command -v flock >/dev/null 2>&1 && command -v setsid >/dev/null 2>&1; then
  rs() { VM_LOCK_FILE="$TMP/rlock" bash -c 'source scripts/_vm.sh; vm_remote_script "$1" "$2"' _ "$@"; }
  t_remote_rc() {
    mkdir -p "$TMP/rdir"
    local s out err rc
    s="$(rs "$TMP/rdir" 'echo OUT-LINE; echo ERR-LINE >&2; pwd; exit 3')"
    out="$(bash -c "$s" 2>"$TMP/rerr")"; rc=$?; err="$(cat "$TMP/rerr")"
    [ "$rc" = 3 ] || { echo "rc=$rc (기대 3)"; return 1; }
    # 표준 출력·오류가 섞이거나 줄바꿈이 바뀌지 않는다(가상 터미널을 쓰지 않는다)
    [ "$out" = "OUT-LINE"$'\n'"$TMP/rdir" ] && [ "$err" = "ERR-LINE" ] || { echo "out=[$out] err=[$err]"; return 1; }
  }
  check "TASK-64#5 원격 실행: 지정 디렉터리에서 명령을 돌리고 종료 코드·stdout/stderr 를 그대로 돌려준다" t_remote_rc
  t_remote_lock_busy() {
    mkdir -p "$TMP/rdir"
    flock "$TMP/rlock" sleep 30 & local holder=$!
    sleep 1
    local s rc; s="$(rs "$TMP/rdir" 'echo SHOULD-NOT-RUN')"
    run_capture bash -c "$s"
    kill "$holder" 2>/dev/null; wait "$holder" 2>/dev/null
    rc_is 75 && has_text "다른" && lacks_text "SHOULD-NOT-RUN"
  }
  check "TASK-64#5 원격 실행: 잠금이 잡혀 있으면 기다리지 않고 75 로 실패한다" t_remote_lock_busy
  t_remote_watchdog() {
    # ssh 연결이 끊기면(= 원격 셸의 부모 sshd 가 사라지면) 원격 명령의 프로세스 그룹 전체를 멈추고 잠금을 푼다
    mkdir -p "$TMP/rdir"; rm -f "$TMP/sleep.pid"
    local s mid sp
    s="$(rs "$TMP/rdir" "sleep 317 & echo \$! >$TMP/sleep.pid; wait")"
    bash -c 'bash -c "$1"; echo after' _ "$s" >/dev/null 2>&1 & mid=$!
    for _ in $(seq 1 50); do [ -s "$TMP/sleep.pid" ] && break; sleep 0.2; done
    sp="$(cat "$TMP/sleep.pid" 2>/dev/null)"; [ -n "$sp" ] && kill -0 "$sp" || { echo "원격 명령이 시작되지 않았다"; return 1; }
    kill -9 "$mid"
    for _ in $(seq 1 30); do kill -0 "$sp" 2>/dev/null || break; sleep 0.5; done
    ! kill -0 "$sp" 2>/dev/null || { kill "$sp"; echo "부모가 죽었는데 원격 명령(sleep $sp)이 살아 있다"; return 1; }
    for _ in $(seq 1 30); do flock -n "$TMP/rlock" true && return 0; sleep 0.5; done
    echo "잠금이 풀리지 않았다"; return 1
  }
  check "TASK-64#5 원격 실행: 호출한 ssh 가 죽으면 남은 원격 명령(프로세스 그룹)을 멈추고 잠금을 푼다" t_remote_watchdog
else
  echo "  WARNING: flock·setsid 가 없어(맥) 원격 실행 스크립트 동작 검사를 건너뛴다 — VM 안 make test 에서 돈다" >&2
fi
t_proxy_optout() {
  # VM_PROXY=0 이면 맥에서도 그대로(예: 다른 컨테이너 런타임) — 여기서는 첫 단계(k3d 정지)까지만 보고 끊는다
  fake_reset; echo started >"$FAKE_STATE/vm"
  run_capture vm_run VM_PROXY=0 bash -c 'source scripts/_vm.sh; vm_proxy_if_mac "make test"; echo NOT-PROXIED'
  rc_is 0 && has_text "NOT-PROXIED" && not_called "ssh"
}
check "TASK-64#5 VM_PROXY=0 이면 맥에서도 VM 으로 보내지 않는다" t_proxy_optout
t_proxy_linux() {
  fake_reset
  run_capture env PATH="$FAKE/bin:$PATH" HOME="$FAKE_HOME" VM_HOST_OS=Linux bash -c 'source scripts/_vm.sh; vm_proxy_if_mac "make test"; echo NOT-PROXIED'
  rc_is 0 && has_text "NOT-PROXIED" && not_called "ssh" && not_called "utmctl"
}
check "TASK-64#5 리눅스(VM 안·CI ubuntu)에서는 그대로 실행한다" t_proxy_linux
t_no_docker_stop_if_running() {
  # 맥에 Docker 가 없어도(k3d 만 PATH 에 있음) k3d 정지 검사는 조용히 통과한다
  local nb="$TMP/nodocker"; rm -rf "$nb"; mkdir -p "$nb"
  local c; for c in bash env dirname cat grep awk sed tr; do ln -sf "$(command -v "$c")" "$nb/$c"; done
  printf '#!/usr/bin/env bash\necho "Cannot connect to the Docker daemon" >&2; exit 1\n' >"$nb/k3d"; chmod +x "$nb/k3d"
  run_capture env PATH="$nb" bash ./scripts/k3d.sh stop-if-running "make test"
  rc_is 0 && lacks_text "ERROR"
}
check "TASK-64#5 Docker 가 없어도 k3d stop-if-running 은 실패하지 않는다" t_no_docker_stop_if_running
check "TASK-64#5 CI(ubuntu)는 그대로: ci.yml 에 VM·ssh·utmctl 없음" sh -c '! grep -nE "utmctl|rocky-dev|VM_PROXY" .github/workflows/ci.yml'
check "TASK-64#5 VM 안 Testcontainers: DOCKER_HOST=Podman 사용자 소켓 + Ryuk privileged" sh -c "
  grep -q 'DOCKER_HOST=unix:///run/user/' $I && grep -q 'TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=true' $I \
    && grep -q 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE' $I"
check "TASK-64#5 Podman API 서비스는 유휴 종료하지 않는다(--time=0 — 기본 5초 종료가 Testcontainers 연결을 끊어 Broken pipe)" sh -c "
  grep -q 'podman.service.d' $I && grep -q 'system service --time=0' $I"
check "TASK-64#5 VM 안 node_modules·backend 빌드 산출물은 VM 로컬 디렉터리(bind)로 — 맥과 섞이지 않는다" sh -c "
  grep -q 'frontend/node_modules' $I && grep -q 'backend/build' $I && grep -q 'backend/.gradle' $I && grep -q 'bind' $I"

# ================================================================ TASK-64#6 YAML→JSON 은 고정 yq
check "TASK-64#6 테스트에 ruby 가 없다" sh -c '! grep -nE "(^|[^a-z])ruby([^a-z]|$)" scripts/test/*.sh scripts/*.sh | grep -v "vm_test.sh"'
check "TASK-64#6 k8s_test 의 y2j 는 yq" grep -Eq '^y2j\(\) \{ yq ' scripts/test/k8s_test.sh
check "TASK-64#6 ci.yml 은 yq 를 버전·sha256 고정 설치하고, 버전은 install.sh 와 같다" sh -c "
  grep -Eq '^\s*YQ_VERSION: \"?[0-9.]+\"?' .github/workflows/ci.yml && grep -Eq '^\s*YQ_SHA256: [0-9a-f]{64}$' .github/workflows/ci.yml \
    && grep -Eq 'YQ_SHA256.*sha256sum -c -' .github/workflows/ci.yml || exit 1
  ci=\$(sed -nE 's/^[[:space:]]*YQ_VERSION: \"?([0-9.]+)\"?.*/\1/p' .github/workflows/ci.yml | head -n1)
  loc=\$(sed -nE 's/^YQ_VERSION=\"?([0-9.]+)\"?.*/\1/p' $I | head -n1)
  [ \"\$ci\" = \"\$loc\" ] || { echo \"ci=\$ci install=\$loc\"; exit 1; }"
if command -v yq >/dev/null 2>&1; then
  t_yq_y2j() {
    local got
    got="$(printf 'a: 1\n---\n---\nb: [x, "2"]\n' | bash -c "$(grep -E '^y2j\(\)' scripts/test/k8s_test.sh); y2j")"
    [ "$got" = '[{"a":1},{"b":["x","2"]}]' ] || { echo "got: $got"; return 1; }
  }
  check "TASK-64#6 y2j: 여러 문서 YAML → 빈 문서를 뺀 JSON 배열 한 줄" t_yq_y2j
else
  echo "  WARNING: yq 가 없어 y2j 동작 검사를 건너뛴다" >&2
fi

# ================================================================ TASK-64#7 README
t_readme() {
  local sec
  sec="$(awk '/^## 로컬 개발 환경/ { f = 1; print; next } f && /^## / { exit } f { print }' README.md)"
  [ -n "$sec" ] || { echo "README 에 '## 로컬 개발 환경' 절이 없다"; return 1; }
  local w
  for w in "make vm-create" "make vm-start" "make vm-stop" "make vm-status" "make vm-ssh" "다시 만들기" "메모리" "SELinux" "자동화" "make test" "ssh rocky-dev" "~/.ssh/config"; do
    grep -qF -- "$w" <<<"$sec" || { echo "README 로컬 개발 환경 절에 '$w' 가 없다"; return 1; }
  done
}
check "TASK-64#7 README 로컬 개발 환경 절(만들기·켜기·끄기·다시 만들기, 메모리 규칙, SELinux, 권한 창 1회)" t_readme
t_readme_ops() {
  local sec
  sec="$(awk '/^## 로컬 개발 환경/ { f = 1; print; next } f && /^## / { exit } f { print }' README.md)"
  # VirtioFS 공유를 나중에 붙였을 때의 재실행 명령, 동시 실행 잠금, worktree
  grep -qF "sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo" <<<"$sec" \
    && grep -qF "/tmp/board-repo-test.lock" <<<"$sec" && grep -qF "worktree" <<<"$sec" || { echo "README 로컬 개발 환경 절에 재실행 명령·잠금·worktree 설명이 없다"; return 1; }
  # 커밋 훅의 make test 는 Claude Code 훅 시간 제한(넘으면 막지 않고 통과)을 받는다 — 커밋 전에 직접 돌린다는 안내
  awk '/^## 개발 워크플로/ { f = 1 } f' README.md | grep -q "시간 제한" || { echo "README 개발 워크플로 절에 훅 시간 제한 안내가 없다"; return 1; }
  # 진입점 주석이 rsync 기본 + 선택 공유를 말한다(VirtioFS 가 기본인 것처럼 쓰지 않는다)
  grep -q "rsync" scripts/test.sh && ! grep -q "저장소는 VirtioFS 공유 /repo" scripts/test.sh || { echo "scripts/test.sh 머리 주석이 저장소 공유 방식을 잘못 말한다"; return 1; }
}
check "TASK-64#7 README: 공유 추가 뒤 install.sh 재실행 명령·동시 실행 잠금·worktree·커밋 훅 시간 제한, test.sh 주석은 rsync 기본" t_readme_ops

echo "vm: $passes passed, $failures failed"
[ "$failures" -eq 0 ]
