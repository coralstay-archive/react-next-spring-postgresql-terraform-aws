#!/usr/bin/env bash
# 노드 준비 확인 (TASK-65#4, OPS-10). user_data 가 /usr/local/sbin/board-node-ready 로 둔다. 배포(TASK-52) 전에 반드시 돌린다:
#   aws ssm start-session --target <node_instance_id> → sudo /usr/local/sbin/board-node-ready
# cloud-init 이 성공(status: done)으로 끝나고 user_data 가 끝까지 돌아 표식을 남겼을 때만 0. error·degraded·표식 없음은 실패.
set -uo pipefail

MARKER="${MARKER:-/var/lib/board-node/bootstrap.done}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-1800}"

rc=0
out="$(timeout "$WAIT_TIMEOUT" cloud-init status --wait 2>&1)" || rc=$?
if [ "$rc" -ne 0 ] || ! grep -q '^status: done' <<<"$out"; then
  echo "ERROR: cloud-init 이 성공으로 끝나지 않았다(rc=$rc): $out" >&2
  cloud-init status --long >&2 || true
  echo "로그: /var/log/cloud-init-output.log" >&2
  exit 1
fi
[ -f "$MARKER" ] || { echo "ERROR: user_data 가 끝까지 돌지 않았다($MARKER 없음) — /var/log/cloud-init-output.log" >&2; exit 1; }
echo "ready: $(cat "$MARKER")"
