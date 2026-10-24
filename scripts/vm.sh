#!/usr/bin/env bash
# Rocky 개발 VM(UTM, rocky-dev) (TASK-64). make vm-* 가 부른다. 공통 함수는 scripts/_vm.sh.
#   create      이미지 받기(sha256)·raw 변환·seed ISO·AppleScript 로 UTM VM 생성 → 시작 → ssh 설정 → cloud-init 완료 대기.
#               이미 있으면 거부한다. 맥 권한 창(UTM 자동화 허용)이 뜨는 유일한 단계 — 실행 전에 안내한다(B1)
#   start       켜고(이미 켜져 있으면 그대로) IP 를 읽어 ~/.ssh/config.d/rocky-dev 를 다시 쓰고 ssh 가 될 때까지 기다린다
#   stop        게스트에 종료를 요청한다(utmctl stop --request). 시간 안에 안 꺼지면 강제 종료
#   status      상태·IP·메모리(VM·맥)
#   ssh [명령]  ssh rocky-dev. 꺼져 있으면 켜지 않고 make vm-start 를 안내한다(B2)
#   ssh-config  켜진 VM 의 IP 로 ssh 설정만 다시 쓴다
#   preflight   사전 검사(B2) — 테스트·k3d 진입점이 부르는 것과 같다
#   run <명령>  맥에서 VM 안의 <명령>(사전 검사 포함). Makefile 의 backend-test·backend-build 가 on-mac 일 때 부른다
#   on-mac      맥에서 VM 을 쓰는 경우 0
set -euo pipefail

# shellcheck source=scripts/_vm.sh
source "$(dirname "${BASH_SOURCE[0]}")/_vm.sh"

cmd_start() {
  local state; state="$(vm_state)"
  case "$state" in
    absent) vm_die "$(vm_not_running_msg absent)" ;;
    started) ;;
    *) vm_log "VM '$VM_NAME' 시작(상태: $state)"; vm_start_checked ;;
  esac
  vm_connect
  vm_log "준비됐다: ssh $VM_SSH_HOST · 끄기: make vm-stop"
}

cmd_stop() {
  local state deadline; state="$(vm_state)"
  if [ "$state" != started ]; then vm_log "VM '$VM_NAME' 은 켜져 있지 않다(상태: $state)"; return 0; fi
  vm_log "VM '$VM_NAME' 에 종료 요청(게스트 OS 정상 종료)"
  "$UTMCTL" stop --hide "$VM_NAME" --request
  deadline=$(( $(date +%s) + ${VM_STOP_TIMEOUT:-90} ))
  while [ "$(vm_state)" = started ] || [ "$(vm_state)" = stopping ]; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      vm_warn "${VM_STOP_TIMEOUT:-90}초 안에 꺼지지 않아 강제로 끈다"
      "$UTMCTL" stop --hide "$VM_NAME" --force
      break
    fi
    sleep 2
  done
  vm_log "꺼졌다(메모리 회수). 다시: make vm-start"
}

cmd_status() {
  local state ip kb mac; state="$(vm_state)"
  if [ "$state" = absent ]; then echo "VM '$VM_NAME': 없다 — make vm-create"; return 0; fi
  echo "VM '$VM_NAME': $state (설정 ${VM_MEMORY_MIB} MiB, CPU $VM_CPUS)"
  mac="$(vm_mac_free_mib)"
  if [ "$state" = started ]; then
    ip="$(vm_ip)"; echo "  IP: ${ip:-(모름)}"
    if kb="$(vm_ssh awk "'/^(MemTotal|MemAvailable):/ { printf \"%s \", int(\$2 / 1024) }'" /proc/meminfo 2>/dev/null)"; then
      read -r total avail <<<"$kb"
      echo "  VM 메모리: 전체 ${total:-?} MiB, 사용 가능 ${avail:-?} MiB"
    else
      echo "  ssh $VM_SSH_HOST 안 됨 — make vm-start 로 ssh 설정을 다시 쓴다"
    fi
  fi
  echo "  맥 여유 메모리: ${mac:-?} MiB"
}

cmd_ssh() {
  local state; state="$(vm_state)"
  [ "$state" = started ] || vm_die "$(vm_not_running_msg "$state")"
  exec ssh "$VM_SSH_HOST" "$@"
}

cmd_ssh_config() {
  local state ip; state="$(vm_state)"
  [ "$state" = started ] || vm_die "$(vm_not_running_msg "$state")"
  ip="$(vm_ip)"
  [ -n "$ip" ] || vm_die "VM '$VM_NAME' 의 IP 를 찾지 못했다(utmctl ip-address·$VM_DHCP_LEASES)"
  vm_write_ssh_config "$ip"
}

case "${1:-}" in
  create) vm_create ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  ssh) shift; cmd_ssh "$@" ;;
  ssh-config) cmd_ssh_config ;;
  preflight) vm_preflight ;;
  run) shift; vm_on_mac || vm_die "vm.sh run 은 맥에서만 쓴다(리눅스에서는 명령을 그대로 실행한다)"; vm_proxy_if_mac "$*" ;;
  on-mac) vm_on_mac ;;
  *) echo "사용: $0 create|start|stop|status|ssh [명령]|ssh-config|preflight|run <명령>|on-mac" >&2; exit 2 ;;
esac
