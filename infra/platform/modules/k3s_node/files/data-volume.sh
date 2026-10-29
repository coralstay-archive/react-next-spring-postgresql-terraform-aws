#!/usr/bin/env bash
# k3s 노드 데이터 볼륨 준비 (TASK-65#3). user_data 가 /usr/local/sbin/board-data-volume.sh 로 두고 부른다. 다시 돌려도 된다.
#   board-data-volume.sh <볼륨 ID> <마운트 경로> [지킬 서비스...]
# 1) 지킬 서비스(k3s.service)에 systemd 드롭인을 먼저 쓴다: RequiresMountsFor + 시작 전 mountpoint 확인 — 마운트 없이는 뜨지 않는다(B2, OPS-9)
# 2) 장치는 볼륨 ID 의 by-id 링크로만 찾는다(nitro 는 연결 장치 이름과 다른 nvmeXn1 로 보인다). 이름으로 다른 장치를 고르지 않는다(OPS-11)
# 3) blkid -p 종료 코드 2(아무것도 없음)일 때만 포맷한다. 0 이면 그대로, 그 밖(오류·모호)이면 손대지 않고 실패한다(OPS-11)
# 4) fstab(UUID)에 넣고 마운트한다. nofail: 볼륨이 없을 때 부팅이 비상 모드에 빠지지 않게(SSM 으로 들어갈 수 있게) — 서비스는 1)이 막는다.
#    볼륨을 키웠으면(data_volume_size) xfs_growfs 로 파일시스템도 늘린다
# 5) k3s data-dir(<마운트>/k3s)·local-path PV(<마운트>/local-path)를 만들고 SELinux 레이블을 맞춘다 — 새 XFS 는 default_t 라
#    k3s·컨테이너가 거부된다. k3s 는 /var/lib/rancher/k3s 와 같은 규칙(equivalence, k3s-selinux 정책), PV 는 container_file_t
# DATA_VOLUME_LIB=1 이면 함수만 읽는다(scripts/test/platform_test.sh 가 가짜 명령으로 부른다).
set -euo pipefail

BY_ID_DIR="${BY_ID_DIR:-/dev/disk/by-id}"
FSTAB="${FSTAB:-/etc/fstab}"
SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"
WAIT_TRIES="${WAIT_TRIES:-120}"
WAIT_SECS="${WAIT_SECS:-5}"

log() { echo "==> [data-volume] $*" >&2; }
die() { echo "ERROR: [data-volume] $*" >&2; exit 1; }

is_block() { [ -b "$1" ]; }

guard_service() {
  local svc="$1" mnt="$2" dir="$SYSTEMD_DIR/$1.d"
  mkdir -p "$dir"
  printf '[Unit]\nRequiresMountsFor=%s\n\n[Service]\nExecStartPre=/usr/bin/mountpoint -q %s\n' "$mnt" "$mnt" >"$dir/10-data-mount.conf"
  log "$svc: $mnt 가 마운트돼야 뜬다"
}

# 연결이 늦을 수 있어 기다린다. 볼륨 ID 링크만 본다
find_device() {
  local link="$BY_ID_DIR/nvme-Amazon_Elastic_Block_Store_${1//-/}" i
  for ((i = 0; i < WAIT_TRIES; i++)); do
    if is_block "$link"; then readlink -f "$link"; return 0; fi
    sleep "$WAIT_SECS"
  done
  return 1
}

ensure_fs() {
  local dev="$1" rc=0
  blkid -p "$dev" >/dev/null 2>&1 || rc=$?
  case "$rc" in
    0) log "$dev 에 이미 파일시스템이 있다 — 포맷하지 않는다" ;;
    2) log "$dev 가 비어 있다(blkid 2) — xfs 로 포맷"; mkfs.xfs -L data "$dev" ;;
    *) die "blkid -p $dev 종료 코드 $rc — 장치 상태를 알 수 없어 포맷·마운트하지 않는다" ;;
  esac
}

mount_data() {
  local dev="$1" mnt="$2" uuid
  uuid="$(blkid -s UUID -o value "$dev")"
  [ -n "$uuid" ] || die "$dev 의 UUID 를 읽지 못했다"
  mkdir -p "$mnt"
  if ! grep -q "^UUID=$uuid " "$FSTAB"; then
    echo "UUID=$uuid $mnt xfs defaults,nofail,x-systemd.device-timeout=60s 0 2" >>"$FSTAB"
    systemctl daemon-reload # systemd 가 새 mount 유닛(RequiresMountsFor 대상)을 알게
  fi
  mountpoint -q "$mnt" || mount "$mnt" || die "$mnt 마운트 실패"
  mountpoint -q "$mnt" || die "$mnt 가 마운트되지 않았다"
  xfs_growfs "$mnt" >/dev/null
}

# fcontext 규칙은 없을 때만 더한다(다시 돌려도 그대로). restorecon 은 매번
label_data() {
  local mnt="$1" rules
  install -d -m 700 "$mnt/k3s"
  install -d -m 755 "$mnt/local-path"
  if ! selinuxenabled; then log "SELinux 꺼짐 — 레이블 건너뜀"; return 0; fi
  rules="$(semanage fcontext -l -C)"
  grep -qxF "$mnt/k3s = /var/lib/rancher/k3s" <<<"$rules" || semanage fcontext -a -e /var/lib/rancher/k3s "$mnt/k3s"
  grep -qF "$mnt/local-path(/.*)? " <<<"$rules" || semanage fcontext -a -t container_file_t "$mnt/local-path(/.*)?"
  restorecon -R "$mnt"
}

main() {
  [ $# -ge 2 ] || die "사용: $0 <볼륨 ID> <마운트 경로> [서비스...]"
  local vol="$1" mnt="$2" svc dev
  shift 2
  for svc in "$@"; do guard_service "$svc" "$mnt"; done
  [ $# -eq 0 ] || systemctl daemon-reload
  dev="$(find_device "$vol")" || die "데이터 볼륨 $vol 이 붙지 않았다($BY_ID_DIR) — 다른 장치를 고르지 않는다"
  ensure_fs "$dev"
  mount_data "$dev" "$mnt"
  label_data "$mnt"
  log "$vol($dev) → $mnt"
}

if [ "${DATA_VOLUME_LIB:-0}" != 1 ]; then main "$@"; fi
