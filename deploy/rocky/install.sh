#!/usr/bin/env bash
# Rocky Linux 9 호스트 설치 (TASK-64#2). 개발 VM(rocky-dev — make vm-create 의 cloud-init 이 seed 에 넣어 돌린다)과
# AWS Rocky EC2(TASK-52, user-data)가 같은 스크립트를 쓴다. root 로 실행한다. 다시 돌려도 된다(설치된 고정 버전은 건너뛴다).
#   --profile server   Podman(사용자 소켓)·git·make·rsync·jq 등 공통 — 운영 호스트
#   --profile dev      server + 개발 도구(Java 25 Temurin·Node·clang/llvm/libbpf-devel/bpftool/kernel-devel·kubectl·kubeconform·yq·
#                      ShellCheck·actionlint·terraform·graphviz) + 저장소 공유(VirtioFS → /repo) + VM 로컬 빌드 디렉터리
#   --user <이름>      Podman 사용자 소켓·linger 를 켤 사용자(필수)
#   --share-tag/--share-path  dev 의 VirtioFS 태그(UTM 기본 share)와 마운트 위치(기본 /repo)
# SELinux 는 enforcing 그대로 둔다 — 끄지 않고, enforcing 이 아니면 실패한다. 저장소 공유는 context=container_file_t 로 마운트해서
# Podman 볼륨으로 써도 거부되지 않게 한다.
# 고정 버전은 아래 *_VERSION·*_SHA256(aarch64 — 개발 VM·t4g EC2). 버전을 올릴 때는 sha256 도 같이 바꾸고, CI(ci.yml)에도 있는
# 도구(shellcheck·kubeconform·yq)는 같은 버전으로 맞춘다(scripts/test/vm_test.sh 가 비교한다).
set -euo pipefail

KUBECTL_VERSION="1.37.1"
KUBECTL_SHA256="ff749f4b78d9c4f1ec87307df9b50119ed819e2094aa9810cb9acffc3286c8c7"
KUBECONFORM_VERSION="0.8.0"
KUBECONFORM_SHA256="1f53fc8e81258197a35e8603054162a5af1de8c5af13746c71ab680d9534ed87"
YQ_VERSION="4.54.1"
YQ_SHA256="189088da0c6429ec5178dfaab1a114805f6cab0b61b165ab236efedf1d57a71b"
NODE_VERSION="24.22.1"
NODE_SHA256="dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e"
SHELLCHECK_VERSION="0.11.0"
SHELLCHECK_SHA256="12b331c1d2db6b9eb13cfca64306b1b157a86eb69db83023e261eaa7e7c14588"
ACTIONLINT_VERSION="1.7.12"
ACTIONLINT_SHA256="325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6"
TERRAFORM_VERSION="1.16.0"
TERRAFORM_SHA256="60f86cea49a653e22a93c000f16cbfa391ce41c6f6a11d36557c35da411f684c"
TEMURIN_PACKAGE="temurin-25-jdk-25.0.5.0.0.8"

PROFILE=""; TARGET_USER=""; SHARE_TAG="share"; SHARE_PATH="/repo"
STAMP_DIR="/usr/local/lib/rocky-install"

log() { echo "==> [install] $*" >&2; }
die() { echo "ERROR: [install] $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --user) TARGET_USER="$2"; shift 2 ;;
    --share-tag) SHARE_TAG="$2"; shift 2 ;;
    --share-path) SHARE_PATH="$2"; shift 2 ;;
    *) die "모르는 인자: $1 (사용: $0 --profile server|dev --user <이름> [--share-tag share --share-path /repo])" ;;
  esac
done
case "$PROFILE" in server | dev) ;; *) die "--profile server|dev 가 필요하다" ;; esac
[ -n "$TARGET_USER" ] || die "--user 가 필요하다"
[ "$(id -u)" = 0 ] || die "root 로 실행한다"
id "$TARGET_USER" >/dev/null 2>&1 || die "사용자가 없다: $TARGET_USER"
[ "$(uname -m)" = aarch64 ] || die "aarch64 만 고정했다(개발 VM·t4g EC2). 지금: $(uname -m)"
# shellcheck source=/dev/null
. /etc/os-release
[ "${ID:-}" = rocky ] && [ "${VERSION_ID%%.*}" = 9 ] || die "Rocky Linux 9 가 아니다: ${PRETTY_NAME:-?}"

# SELinux: enforcing 유지(끄지 않는다)
[ "$(getenforce)" = Enforcing ] || die "SELinux 가 Enforcing 이 아니다($(getenforce)) — 끄지 않는다. /etc/selinux/config 를 enforcing 으로 되돌리고 재부팅"
log "SELinux: Enforcing"

mkdir -p "$STAMP_DIR"
installed() { [ -f "$STAMP_DIR/$1" ]; }
mark() { : >"$STAMP_DIR/$1"; }
# fetch <url> <sha256> <파일> — 받고 sha256 이 다르면 실패
fetch() {
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c - >/dev/null || { rm -f "$3"; die "sha256 불일치: $1"; }
}

# ---------------------------------------------------------------- 패키지(공통)
log "dnf: podman·git·make·rsync 등"
dnf -y -q install podman container-selinux git make rsync tar unzip xz jq python3 curl dnf-plugins-core

# ---------------------------------------------------------------- Podman 사용자 소켓(Testcontainers·compose 가 DOCKER_HOST 로 쓴다)
# 사용자 유닛 활성화는 심볼릭 링크로 한다(cloud-init 도중 사용자 매니저를 기다리지 않게). linger 라 부팅 때 사용자 매니저가 뜨며 소켓을 연다.
uid="$(id -u "$TARGET_USER")"
home="$(getent passwd "$TARGET_USER" | cut -d: -f6)"
runuser -u "$TARGET_USER" -- mkdir -p "$home/.config/systemd/user/sockets.target.wants"
runuser -u "$TARGET_USER" -- ln -sfn /usr/lib/systemd/user/podman.socket "$home/.config/systemd/user/sockets.target.wants/podman.socket"
# API 서비스(소켓이 깨운다)는 기본으로 5초 유휴면 끝난다 — Testcontainers 가 붙잡은 연결이 끊겨 "Broken pipe" 로 컨테이너 시작이 실패했다
# (2026-10-24 실측). 유휴 종료를 끈다(--time=0).
runuser -u "$TARGET_USER" -- mkdir -p "$home/.config/systemd/user/podman.service.d"
runuser -u "$TARGET_USER" -- tee "$home/.config/systemd/user/podman.service.d/no-idle-exit.conf" >/dev/null <<'EOF'
# deploy/rocky/install.sh 가 쓴다(TASK-64#5)
[Service]
ExecStart=
ExecStart=/usr/bin/podman $LOGGING system service --time=0
EOF
# rootless Podman 은 subuid·subgid 범위가 있어야 이미지 레이어의 여러 uid 를 풀 수 있다. useradd 는 uid < 1000(개발 VM 은 맥과 같은 uid,
# 예: 501)이면 범위를 주지 않는다 — 없으면 겹치지 않는 범위를 준다.
if ! grep -q "^$TARGET_USER:" /etc/subuid 2>/dev/null; then
  start=$(( $(awk -F: '{ e = $2 + $3; if (e > m) m = e } END { print m + 0 }' /etc/subuid /etc/subgid 2>/dev/null) ))
  [ "$start" -ge 100000 ] || start=100000
  usermod --add-subuids "$start-$((start + 65535))" --add-subgids "$start-$((start + 65535))" "$TARGET_USER"
  log "subuid·subgid: $TARGET_USER $start-$((start + 65535))"
fi
loginctl enable-linger "$TARGET_USER"
# 사용자 매니저(linger 로 뜬다)의 버스가 생길 때까지 잠깐 기다렸다가 소켓을 켠다
for _ in $(seq 1 30); do [ -S "/run/user/$uid/bus" ] && break; sleep 1; done
runuser -u "$TARGET_USER" -- env XDG_RUNTIME_DIR="/run/user/$uid" podman system migrate >/dev/null 2>&1 || true
runuser -u "$TARGET_USER" -- env XDG_RUNTIME_DIR="/run/user/$uid" systemctl --user daemon-reload || true
runuser -u "$TARGET_USER" -- env XDG_RUNTIME_DIR="/run/user/$uid" systemctl --user stop podman.service >/dev/null 2>&1 || true
runuser -u "$TARGET_USER" -- env XDG_RUNTIME_DIR="/run/user/$uid" systemctl --user enable --now podman.socket \
  || log "WARNING: 지금 podman.socket 을 켜지 못했다(사용자 매니저 기동 중) — linger 로 다음 부팅 때 켜진다"
log "podman.socket 사용자 소켓: /run/user/$uid/podman/podman.sock ($TARGET_USER, linger)"

# 로그인 셸 환경: Testcontainers 가 Podman 사용자 소켓을 쓰고, rootless 라 Ryuk 는 privileged 로(소켓을 마운트해야 한다)
cat >/etc/profile.d/rocky-podman.sh <<'EOF'
# deploy/rocky/install.sh 가 쓴다(TASK-64#5). root 가 아닌 사용자는 rootless Podman 사용자 소켓을 Docker API 로 쓴다.
if [ "$(id -u)" != 0 ]; then
  export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/$(id -u)/podman/podman.sock
  export TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=true
fi
# 맥과 같이 쓰는 저장소(.git/index)를 git status 가 몰래 다시 쓰지 않게
export GIT_OPTIONAL_LOCKS=0
export PATH="/usr/local/bin:$PATH"
EOF

if [ "$PROFILE" = server ]; then log "완료(server)"; exit 0; fi

# ---------------------------------------------------------------- 개발 도구(dev)
log "dnf: clang·llvm·libbpf-devel·bpftool·kernel-devel·graphviz (CRB 사용)"
dnf config-manager --set-enabled crb
dnf -y -q install clang llvm libbpf-devel bpftool graphviz
dnf -y -q install "kernel-devel-$(uname -r)" || { log "kernel-devel-$(uname -r) 이 저장소에 없어 최신 kernel-devel 을 설치한다(eBPF CO-RE 는 BTF 를 쓴다)"; dnf -y -q install kernel-devel; }

if ! rpm -q "$TEMURIN_PACKAGE" >/dev/null 2>&1; then
  log "Java: $TEMURIN_PACKAGE (Adoptium RPM)"
  cat >/etc/yum.repos.d/adoptium.repo <<'EOF'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/9/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF
  dnf -y -q install "$TEMURIN_PACKAGE"
fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

if ! installed "node-$NODE_VERSION"; then
  log "Node v$NODE_VERSION"
  fetch "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz" "$NODE_SHA256" "$tmp/node.tar.xz"
  rm -rf "/usr/local/lib/node-v$NODE_VERSION-linux-arm64"
  tar -xJf "$tmp/node.tar.xz" -C /usr/local/lib
  for b in node npm npx corepack; do ln -sfn "/usr/local/lib/node-v$NODE_VERSION-linux-arm64/bin/$b" "/usr/local/bin/$b"; done
  mark "node-$NODE_VERSION"
fi
if ! installed "kubectl-$KUBECTL_VERSION"; then
  log "kubectl v$KUBECTL_VERSION"
  fetch "https://dl.k8s.io/release/v$KUBECTL_VERSION/bin/linux/arm64/kubectl" "$KUBECTL_SHA256" "$tmp/kubectl"
  install -m 0755 "$tmp/kubectl" /usr/local/bin/kubectl
  mark "kubectl-$KUBECTL_VERSION"
fi
if ! installed "kubeconform-$KUBECONFORM_VERSION"; then
  log "kubeconform v$KUBECONFORM_VERSION"
  fetch "https://github.com/yannh/kubeconform/releases/download/v$KUBECONFORM_VERSION/kubeconform-linux-arm64.tar.gz" "$KUBECONFORM_SHA256" "$tmp/kubeconform.tar.gz"
  tar -xzf "$tmp/kubeconform.tar.gz" -C "$tmp" kubeconform
  install -m 0755 "$tmp/kubeconform" /usr/local/bin/kubeconform
  mark "kubeconform-$KUBECONFORM_VERSION"
fi
if ! installed "yq-$YQ_VERSION"; then
  log "yq v$YQ_VERSION"
  fetch "https://github.com/mikefarah/yq/releases/download/v$YQ_VERSION/yq_linux_arm64" "$YQ_SHA256" "$tmp/yq"
  install -m 0755 "$tmp/yq" /usr/local/bin/yq
  mark "yq-$YQ_VERSION"
fi
if ! installed "shellcheck-$SHELLCHECK_VERSION"; then
  log "shellcheck v$SHELLCHECK_VERSION"
  fetch "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK_VERSION/shellcheck-v$SHELLCHECK_VERSION.linux.aarch64.tar.xz" "$SHELLCHECK_SHA256" "$tmp/shellcheck.tar.xz"
  tar -xJf "$tmp/shellcheck.tar.xz" -C "$tmp"
  install -m 0755 "$tmp/shellcheck-v$SHELLCHECK_VERSION/shellcheck" /usr/local/bin/shellcheck
  mark "shellcheck-$SHELLCHECK_VERSION"
fi
if ! installed "actionlint-$ACTIONLINT_VERSION"; then
  log "actionlint v$ACTIONLINT_VERSION"
  fetch "https://github.com/rhysd/actionlint/releases/download/v$ACTIONLINT_VERSION/actionlint_${ACTIONLINT_VERSION}_linux_arm64.tar.gz" "$ACTIONLINT_SHA256" "$tmp/actionlint.tar.gz"
  tar -xzf "$tmp/actionlint.tar.gz" -C "$tmp" actionlint
  install -m 0755 "$tmp/actionlint" /usr/local/bin/actionlint
  mark "actionlint-$ACTIONLINT_VERSION"
fi
if ! installed "terraform-$TERRAFORM_VERSION"; then
  log "terraform v$TERRAFORM_VERSION"
  fetch "https://releases.hashicorp.com/terraform/$TERRAFORM_VERSION/terraform_${TERRAFORM_VERSION}_linux_arm64.zip" "$TERRAFORM_SHA256" "$tmp/terraform.zip"
  unzip -o -q "$tmp/terraform.zip" terraform -d "$tmp"
  install -m 0755 "$tmp/terraform" /usr/local/bin/terraform
  mark "terraform-$TERRAFORM_VERSION"
fi

# ---------------------------------------------------------------- 저장소 공유(VirtioFS) + VM 로컬 빌드 디렉터리(dev)
# 맥의 저장소를 SHARE_PATH 에 마운트한다. context=container_file_t: SELinux 레이블이 없는 공유 파일을 Podman 볼륨으로 써도 거부되지 않게.
mkdir -p "$SHARE_PATH"
fstab_add() { grep -qF " $2 " /etc/fstab || echo "$1 $2 $3 $4 0 0" >>/etc/fstab; }
fstab_add "$SHARE_TAG" "$SHARE_PATH" virtiofs "rw,nofail,context=system_u:object_r:container_file_t:s0"
systemctl daemon-reload
# 공유는 선택이다: UTM 창에서 디렉터리를 직접 붙였을 때만 마운트된다. 없으면 SHARE_PATH 는 VM 로컬 디렉터리이고, 맥의 make test 가
# rsync 로 작업 트리를 맞춘다(scripts/_vm.sh vm_sync_repo) — 그래서 사용자 소유로 둔다.
if ! mountpoint -q "$SHARE_PATH" && ! mount "$SHARE_PATH" 2>/dev/null; then
  log "공유 디렉터리(VirtioFS 태그 $SHARE_TAG)가 없다 — $SHARE_PATH 는 VM 로컬(맥의 make test 가 rsync 로 맞춘다)"
  chown "$TARGET_USER:" "$SHARE_PATH"
fi

# 플랫폼이 다른 산출물은 맥과 섞이지 않게 VM 로컬 디렉터리를 bind 로 덮는다: frontend/node_modules(네이티브 모듈 darwin ↔ linux),
# backend/build·backend/.gradle(맥 IDE 빌드와 동시 실행 충돌). 맥 쪽에는 빈 마운트 지점 디렉터리만 생긴다(gitignore 대상).
# 공유가 마운트돼 있을 때만 설정된다 — VM 을 만든 뒤(cloud-init 이 이미 돈 뒤) UTM 창에서 공유를 붙였다면 VM 을 다시 켜고 한 번 다시 돌린다:
#   ssh rocky-dev sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo
# (맥의 make test 가 bind 가 빠진 것을 알아보고 이 명령을 안내한다 — scripts/_vm.sh vm_check_overlays)
overlay="$home/.local/share/repo-overlay"
if mountpoint -q "$SHARE_PATH"; then
  for d in frontend/node_modules backend/build backend/.gradle; do
    [ -d "$SHARE_PATH/${d%%/*}" ] || continue
    src="$overlay/${d//\//-}"
    runuser -u "$TARGET_USER" -- mkdir -p "$src" "$SHARE_PATH/$d"
    fstab_add "$src" "$SHARE_PATH/$d" none "bind,nofail,x-systemd.requires-mounts-for=$SHARE_PATH"
  done
  systemctl daemon-reload
  mount -a
fi
log "완료(dev): 저장소 $SHARE_PATH, Podman 소켓 /run/user/$uid/podman/podman.sock"
