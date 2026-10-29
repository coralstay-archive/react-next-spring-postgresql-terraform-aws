#!/usr/bin/env bash
# shellcheck disable=SC2016 # sh -c '...' 안의 $ 는 하위 셸에서 풀린다
# AWS 실배포 플랫폼 스택(infra/platform, TASK-65) 테스트. scripts/test.sh 가 호출한다.
# Terraform 동작은 tests/platform.tftest.hcl(mock_provider)이 보고, 여기서는 파일 배치·설정 파일·노드 스크립트를 본다.
# 실제 AWS·장치는 부르지 않는다(노드 스크립트는 PATH 앞에 둔 가짜 blkid·mkfs.xfs·mount 로 부른다).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

failures=0
passes=0

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

P=infra/platform

echo "platform tests"

# TASK-65#1 루트 스택: 파일 구성, bootstrap state 버킷 재사용(키 분리 platform/…), network 모듈 재사용, lock 파일(3플랫폼 해시) 커밋,
# make test 가 init(-backend=false)·validate·test 한다
for f in versions.tf providers.tf backend.tf variables.tf main.tf outputs.tf envs/prod.backend.hcl envs/prod.tfvars tests/platform.tftest.hcl; do
  check "TASK-65#1 $P/$f" test -f "$P/$f"
done
check "TASK-65#1 backend 는 S3 partial config" grep -q 'backend "s3" {}' "$P/backend.tf"
check "TASK-65#1 backend 키는 platform/terraform.tfstate(infra/main 과 분리) + use_lockfile" sh -c "
  grep -Eq '^key += \"platform/terraform.tfstate\"' $P/envs/prod.backend.hcl && grep -Eq '^use_lockfile += true' $P/envs/prod.backend.hcl
  ! grep -v '^#' $P/envs/prod.backend.hcl | grep -q 'main/prod/terraform.tfstate'"
check "TASK-65#1 network 는 infra/main/modules/network 재사용" grep -q 'source *= "../main/modules/network"' "$P/main.tf"
check "TASK-65#1 lock 파일 커밋 + darwin_arm64·linux_arm64·linux_amd64 해시(h1 3개 이상)" sh -c "
  git ls-files --error-unmatch $P/.terraform.lock.hcl >/dev/null && [ \"\$(grep -c '\"h1:' $P/.terraform.lock.hcl)\" -ge 3 ]"
check "TASK-65#1 gitignore 가 platform 모듈 lock 파일을 무시" git check-ignore -q --no-index "$P/modules/k3s_node/.terraform.lock.hcl"
check "TASK-65#1 scripts/test.sh 는 루트 스택 infra/platform 을 validate" grep -q 'infra/platform' scripts/test.sh
check "TASK-65#1 scripts/test.sh 가 이 테스트를 부른다" grep -q 'scripts/test/platform_test.sh' scripts/test.sh

# TASK-65#2 TASK-65#B1 k3s_node: 22 번 포트·SSH 키를 설정에 두지 않는다(접속은 SSM). 규칙 상세는 k3s_node.tftest.hcl
N=$P/modules/k3s_node
check "TASK-65#2 k3s_node 모듈 테스트 존재" test -f "$N/tests/k3s_node.tftest.hcl"
check "TASK-65#B1 k3s_node 설정에 22 번 포트가 없다" sh -c "! grep -Eq '(from|to)_port *= *22\\b' $N/*.tf"
check "TASK-65#B1 k3s_node 는 SSH 키(key_name)를 쓰지 않는다" sh -c "! grep -Eq '^\\s*key_name\\b' $N/*.tf"
check "TASK-65#B1 k3s_node 는 SSM 에이전트 권한(ssmmessages)으로 접속한다" grep -q 'ssmmessages:OpenDataChannel' "$N/main.tf"
check "TASK-65#2 노드·LB 는 managed policy(AmazonSSMManagedInstanceCore 등)를 붙이지 않는다 — 파라미터·KMS 가 * 로 열린다" sh -c "
  ! grep -q 'aws_iam_role_policy_attachment' $N/main.tf $P/modules/lb_node/main.tf"

# TASK-65#3 TASK-65#B2 데이터 볼륨 스크립트(files/data-volume.sh): DATA_VOLUME_LIB=1 로 함수만 읽고 가짜 blkid·mkfs.xfs·mount·mountpoint·
# systemctl 로 부른다. 장치는 볼륨 ID 의 by-id 링크로만 찾고(이름 기반 대체 없음 — OPS-11), blkid 종료 코드 2(빈 장치)일 때만 포맷하며,
# 지킬 서비스(k3s)에는 마운트 없이는 뜨지 않는 systemd 드롭인을 먼저 쓴다(OPS-9)
DV=$N/files/data-volume.sh
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAKE="$TMP/bin"; mkdir -p "$FAKE"
cat >"$FAKE/blkid" <<'SH'
#!/usr/bin/env bash
echo "blkid $*" >>"$DV_STATE/calls.log"
case " $* " in *" -s UUID "*) echo "1111-2222"; exit 0 ;; esac
exit "${FAKE_BLKID_RC:-0}"
SH
cat >"$FAKE/mkfs.xfs" <<'SH'
#!/usr/bin/env bash
echo "mkfs.xfs $*" >>"$DV_STATE/calls.log"
SH
cat >"$FAKE/mount" <<'SH'
#!/usr/bin/env bash
echo "mount $*" >>"$DV_STATE/calls.log"; [ "${FAKE_MOUNT_RC:-0}" = 0 ] && touch "$DV_STATE/mounted"; exit "${FAKE_MOUNT_RC:-0}"
SH
cat >"$FAKE/mountpoint" <<'SH'
#!/usr/bin/env bash
[ -f "$DV_STATE/mounted" ]
SH
cat >"$FAKE/systemctl" <<'SH'
#!/usr/bin/env bash
echo "systemctl $*" >>"$DV_STATE/calls.log"
SH
# SELinux: FAKE_SELINUX=0 이면 꺼진 것처럼. semanage 는 fcontext -a 를 $DV_STATE/fcontext 에 적고 -l 로 보여 준다
cat >"$FAKE/selinuxenabled" <<'SH'
#!/usr/bin/env bash
[ "${FAKE_SELINUX:-1}" = 1 ]
SH
cat >"$FAKE/semanage" <<'SH'
#!/usr/bin/env bash
echo "semanage $*" >>"$DV_STATE/calls.log"
case "$*" in
  "fcontext -l -C") cat "$DV_STATE/fcontext" 2>/dev/null || true ;;
  "fcontext -a -e "*) echo "$5 = $4" >>"$DV_STATE/fcontext" ;;
  "fcontext -a -t "*) echo "$5    all files    system_u:object_r:$4:s0" >>"$DV_STATE/fcontext" ;;
esac
SH
cat >"$FAKE/restorecon" <<'SH'
#!/usr/bin/env bash
echo "restorecon $*" >>"$DV_STATE/calls.log"
SH
cat >"$FAKE/xfs_growfs" <<'SH'
#!/usr/bin/env bash
echo "xfs_growfs $*" >>"$DV_STATE/calls.log"
SH
chmod +x "$FAKE"/*

# dv_run <blkid 종료 코드> <장치 링크 만들기 0|1> — 새 임시 루트에서 스크립트 main 을 부른다. 결과는 DV_RC·DV_STATE
dv_run() {
  DV_STATE="$(cd "$(mktemp -d "$TMP/s.XXXX")" && pwd -P)"; export DV_STATE
  mkdir -p "$DV_STATE/by-id" "$DV_STATE/systemd" "$DV_STATE/data"; : >"$DV_STATE/fstab"; : >"$DV_STATE/calls.log"
  [ "$2" = 1 ] && { : >"$DV_STATE/nvme1n1"; ln -s "$DV_STATE/nvme1n1" "$DV_STATE/by-id/nvme-Amazon_Elastic_Block_Store_vol0abc123def4567890"; }
  DV_RC=0
  PATH="$FAKE:$PATH" FAKE_BLKID_RC="$1" BY_ID_DIR="$DV_STATE/by-id" FSTAB="$DV_STATE/fstab" SYSTEMD_DIR="$DV_STATE/systemd" \
    WAIT_TRIES=2 WAIT_SECS=0 bash -c '
      DATA_VOLUME_LIB=1 . "$1"
      is_block() { [ -e "$1" ]; }   # 테스트에서는 보통 파일을 블록 장치로 본다
      main vol-0abc123def4567890 "$DV_STATE/data" k3s.service' _ "$DV" >"$DV_STATE/out" 2>&1 || DV_RC=$?
}
dv_called() { grep -qF -- "$1" "$DV_STATE/calls.log" || { echo "호출 없음: $1"; cat "$DV_STATE/calls.log" "$DV_STATE/out"; return 1; }; }
dv_not_called() { ! grep -qF -- "$1" "$DV_STATE/calls.log" || { echo "호출되면 안 됨: $1"; cat "$DV_STATE/calls.log"; return 1; }; }
dv_dropin() { cat "$DV_STATE/systemd/k3s.service.d/10-data-mount.conf" 2>/dev/null; }

# TASK-65#3 데이터 볼륨은 실수로 지우지 않는다(prevent_destroy), data_snapshot_id 를 나중에 바꿔도 기존 볼륨을 갈아엎지 않는다(ignore_changes).
# 복원은 문서 절차(새 볼륨 → 교체)로만
dv_block() { awk '/^resource "aws_ebs_volume" "data"/{f=1} f{print} f && /^}/{exit}' "$N/main.tf"; }
check "TASK-65#3 데이터 볼륨 lifecycle: prevent_destroy = true, ignore_changes = [snapshot_id]" sh -c "
  $(declare -f dv_block); N=$N; b=\$(dv_block); grep -q 'prevent_destroy *= *true' <<<\"\$b\" && grep -q 'ignore_changes *= *\\[snapshot_id\\]' <<<\"\$b\""
for w in "## 데이터 볼륨 복원" "prevent_destroy" "data_snapshot_id" "-replace=module.k3s_node.aws_ebs_volume.data" "board-node-ready"; do
  check "TASK-65#3 terraform-state.md 데이터 볼륨 복원 절차에 '$w'" grep -qF -- "$w" docs/terraform-state.md
done
check "TASK-65#3 data-volume.sh 는 실행 가능하고 bash 문법이 맞다" sh -c "test -x $DV && bash -n $DV"
check "TASK-65#3 장치는 볼륨 ID 의 by-id 링크로만 찾는다(/dev/sdf·xvdf 이름 대체 없음 — OPS-11)" sh -c "
  grep -q 'nvme-Amazon_Elastic_Block_Store_' $DV && ! grep -Eq '/dev/(sd|xvd)[a-z]' $DV"
t_dv_blank() {
  dv_run 2 1
  [ "$DV_RC" = 0 ] || { echo "rc=$DV_RC"; cat "$DV_STATE/out"; return 1; }
  dv_called "blkid -p $DV_STATE/nvme1n1" && dv_called "mkfs.xfs -L data $DV_STATE/nvme1n1" && dv_called "mount $DV_STATE/data" \
    && grep -qx "UUID=1111-2222 $DV_STATE/data xfs defaults,nofail,x-systemd.device-timeout=60s 0 2" "$DV_STATE/fstab"
}
check "TASK-65#3 blkid 종료 코드 2(빈 장치)면 xfs 로 포맷하고 fstab(UUID)·마운트" t_dv_blank
t_dv_existing() {
  dv_run 0 1
  [ "$DV_RC" = 0 ] && dv_not_called "mkfs.xfs" && dv_called "mount $DV_STATE/data"
}
check "TASK-65#3 파일시스템이 있으면(blkid 0) 포맷하지 않고 마운트만" t_dv_existing
t_dv_probe_error() {
  local rc
  for rc in 4 8 1; do
    dv_run "$rc" 1
    [ "$DV_RC" != 0 ] || { echo "blkid $rc 인데 성공"; return 1; }
    dv_not_called "mkfs.xfs" && dv_not_called "mount" || return 1
    [ ! -s "$DV_STATE/fstab" ] || { echo "fstab 이 바뀌었다"; return 1; }
    grep -q "blkid" "$DV_STATE/out" || { cat "$DV_STATE/out"; return 1; }
  done
}
check "TASK-65#3 blkid 가 2 도 0 도 아니면(4·8·1 — 오류·모호) 포맷·마운트 없이 실패(OPS-11)" t_dv_probe_error
t_dv_missing() {
  dv_run 2 0
  [ "$DV_RC" != 0 ] && dv_not_called "mkfs.xfs" && dv_not_called "blkid" && grep -q "vol-0abc123def4567890" "$DV_STATE/out"
}
check "TASK-65#3 볼륨이 붙지 않으면 기다린 뒤 다른 장치를 고르지 않고 실패" t_dv_missing
t_dv_rerun() {
  dv_run 2 1
  PATH="$FAKE:$PATH" FAKE_BLKID_RC=0 BY_ID_DIR="$DV_STATE/by-id" FSTAB="$DV_STATE/fstab" SYSTEMD_DIR="$DV_STATE/systemd" \
    bash -c 'DATA_VOLUME_LIB=1 . "$1"; is_block() { [ -e "$1" ]; }; main vol-0abc123def4567890 "$DV_STATE/data" k3s.service' _ "$DV" >/dev/null 2>&1 || return 1
  [ "$(grep -c "UUID=1111-2222" "$DV_STATE/fstab")" = 1 ] && [ "$(grep -c "mkfs.xfs" "$DV_STATE/calls.log")" = 1 ]
}
check "TASK-65#3 다시 돌려도 포맷·fstab 줄을 더하지 않는다" t_dv_rerun
t_dv_guard() {
  dv_run 2 1
  dv_dropin | grep -qx "RequiresMountsFor=$DV_STATE/data" && dv_dropin | grep -qx "ExecStartPre=/usr/bin/mountpoint -q $DV_STATE/data" \
    && dv_called "systemctl daemon-reload"
}
check "TASK-65#B2 k3s.service 드롭인: RequiresMountsFor=/data + 시작 전 mountpoint 확인 → 마운트되지 않으면 뜨지 않는다" t_dv_guard
t_dv_guard_first() {
  dv_run 2 0
  [ "$DV_RC" != 0 ] && dv_dropin | grep -q "RequiresMountsFor=$DV_STATE/data"
}
check "TASK-65#B2 볼륨을 못 찾아 실패해도 드롭인은 먼저 써 둔다(나중에 설치되는 k3s 가 빈 루트 디스크에 뜨지 않게)" t_dv_guard_first
t_dv_mount_fail() {
  DV_STATE="$(cd "$(mktemp -d "$TMP/s.XXXX")" && pwd -P)"; export DV_STATE
  mkdir -p "$DV_STATE/by-id" "$DV_STATE/systemd"; : >"$DV_STATE/fstab"; : >"$DV_STATE/calls.log"; : >"$DV_STATE/nvme1n1"
  ln -s "$DV_STATE/nvme1n1" "$DV_STATE/by-id/nvme-Amazon_Elastic_Block_Store_vol0abc123def4567890"
  ! PATH="$FAKE:$PATH" FAKE_BLKID_RC=0 FAKE_MOUNT_RC=32 BY_ID_DIR="$DV_STATE/by-id" FSTAB="$DV_STATE/fstab" SYSTEMD_DIR="$DV_STATE/systemd" \
    bash -c 'DATA_VOLUME_LIB=1 . "$1"; is_block() { [ -e "$1" ]; }; main vol-0abc123def4567890 "$DV_STATE/data" k3s.service' _ "$DV" >/dev/null 2>&1
}
check "TASK-65#B2 마운트에 실패하면 스크립트도 실패한다(cloud-init 오류로 드러난다)" t_dv_mount_fail
t_dv_layout() {
  dv_run 2 1
  [ "$DV_RC" = 0 ] || { cat "$DV_STATE/out"; return 1; }
  [ -d "$DV_STATE/data/k3s" ] && [ -d "$DV_STATE/data/local-path" ] || { echo "k3s·local-path 디렉터리 없음"; return 1; }
  dv_called "semanage fcontext -a -e /var/lib/rancher/k3s $DV_STATE/data/k3s" \
    && dv_called "semanage fcontext -a -t container_file_t $DV_STATE/data/local-path(/.*)?" \
    && dv_called "restorecon -R $DV_STATE/data" && dv_called "xfs_growfs $DV_STATE/data"
}
check "TASK-65#3 /data/k3s 는 /var/lib/rancher/k3s 와 같은 SELinux 레이블(equivalence), local-path 는 container_file_t, restorecon -R, xfs_growfs(볼륨을 키우면 반영)" t_dv_layout
t_dv_selinux_rerun() {
  dv_run 2 1
  PATH="$FAKE:$PATH" FAKE_BLKID_RC=0 BY_ID_DIR="$DV_STATE/by-id" FSTAB="$DV_STATE/fstab" SYSTEMD_DIR="$DV_STATE/systemd" \
    bash -c 'DATA_VOLUME_LIB=1 . "$1"; is_block() { [ -e "$1" ]; }; main vol-0abc123def4567890 "$DV_STATE/data" k3s.service' _ "$DV" >/dev/null 2>&1 || return 1
  [ "$(grep -c "semanage fcontext -a" "$DV_STATE/calls.log")" = 2 ] || { cat "$DV_STATE/calls.log"; return 1; }
  [ "$(grep -c "restorecon -R" "$DV_STATE/calls.log")" = 2 ]
}
check "TASK-65#3 다시 돌려도 fcontext 규칙을 더하지 않는다(restorecon 은 매번)" t_dv_selinux_rerun
t_dv_selinux_off() {
  DV_STATE="$(cd "$(mktemp -d "$TMP/s.XXXX")" && pwd -P)"; export DV_STATE
  mkdir -p "$DV_STATE/by-id" "$DV_STATE/systemd"; : >"$DV_STATE/fstab"; : >"$DV_STATE/calls.log"; : >"$DV_STATE/nvme1n1"
  ln -s "$DV_STATE/nvme1n1" "$DV_STATE/by-id/nvme-Amazon_Elastic_Block_Store_vol0abc123def4567890"
  PATH="$FAKE:$PATH" FAKE_SELINUX=0 FAKE_BLKID_RC=0 BY_ID_DIR="$DV_STATE/by-id" FSTAB="$DV_STATE/fstab" SYSTEMD_DIR="$DV_STATE/systemd" \
    bash -c 'DATA_VOLUME_LIB=1 . "$1"; is_block() { [ -e "$1" ]; }; main vol-0abc123def4567890 "$DV_STATE/data" k3s.service' _ "$DV" >/dev/null 2>&1 || return 1
  dv_not_called "semanage" && dv_not_called "restorecon"
}
check "TASK-65#3 SELinux 가 꺼져 있으면 레이블을 건너뛴다" t_dv_selinux_off
t_dv_reload_after_fstab() {
  dv_run 2 1
  # fstab 줄을 더한 뒤 mount 전에 daemon-reload(systemd 가 새 mount 유닛을 알게)
  awk '/^mkfs.xfs/{m=1} m && /^systemctl daemon-reload/{r=1} r && /^mount /{ok=1} END{exit !ok}' "$DV_STATE/calls.log" || { cat "$DV_STATE/calls.log"; return 1; }
}
check "TASK-65#3 fstab 에 줄을 더하면 mount 전에 systemctl daemon-reload" t_dv_reload_after_fstab
if command -v shellcheck >/dev/null 2>&1; then
  check "TASK-65#3 data-volume.sh shellcheck" shellcheck "$DV"
fi

# TASK-65#4 노드 준비 확인(files/node-ready.sh → board-node-ready, OPS-10): cloud-init 이 성공(status: done)으로 끝나고
# user_data 끝 표식이 있을 때만 0. 가짜 cloud-init·timeout 으로 부른다
NR=$N/files/node-ready.sh
cat >"$FAKE/cloud-init" <<'SH'
#!/usr/bin/env bash
echo "cloud-init $*" >>"$DV_STATE/calls.log"
case " $* " in *" --wait "*) echo "status: ${FAKE_CI_STATUS:-done}"; exit "${FAKE_CI_RC:-0}" ;; esac
echo "status: ${FAKE_CI_STATUS:-done}"; echo "detail: fake"
SH
cat >"$FAKE/timeout" <<'SH'
#!/usr/bin/env bash
shift; exec "$@"
SH
chmod +x "$FAKE"/*
# nr_run <cloud-init 종료 코드> <상태> <표식 만들기 0|1>
nr_run() {
  DV_STATE="$(cd "$(mktemp -d "$TMP/r.XXXX")" && pwd -P)"; export DV_STATE; : >"$DV_STATE/calls.log"
  [ "$3" = 1 ] && echo "0123abc" >"$DV_STATE/bootstrap.done"
  NR_RC=0
  NR_OUT="$(PATH="$FAKE:$PATH" FAKE_CI_RC="$1" FAKE_CI_STATUS="$2" MARKER="$DV_STATE/bootstrap.done" bash "$NR" 2>&1)" || NR_RC=$?
}
check "TASK-65#4 node-ready.sh 는 실행 가능하고 bash 문법이 맞다" sh -c "test -x $NR && bash -n $NR"
t_nr_ok() { nr_run 0 "done" 1; [ "$NR_RC" = 0 ] && grep -q "0123abc" <<<"$NR_OUT" && dv_called "cloud-init status --wait"; }
check "TASK-65#4 cloud-init done + 끝 표식 → 준비됨(0)" t_nr_ok
t_nr_error() { nr_run 1 error 1; [ "$NR_RC" != 0 ] && grep -q "cloud-init" <<<"$NR_OUT" && dv_called "cloud-init status --long"; }
check "TASK-65#4 cloud-init error → 실패 + status --long 을 보인다" t_nr_error
t_nr_degraded() { nr_run 2 degraded 1; [ "$NR_RC" != 0 ]; }
check "TASK-65#4 cloud-init degraded(복구된 오류도) → 실패" t_nr_degraded
t_nr_no_marker() { nr_run 0 "done" 0; [ "$NR_RC" != 0 ] && grep -q "bootstrap.done" <<<"$NR_OUT"; }
check "TASK-65#4 cloud-init 은 done 이어도 user_data 끝 표식이 없으면 실패" t_nr_no_marker
if command -v shellcheck >/dev/null 2>&1; then
  check "TASK-65#4 node-ready.sh shellcheck" shellcheck "$NR"
fi

# TASK-65#5 토글 모듈: lb_node(테스트 있음, 기본 꺼짐), 스냅샷·Budgets 토글, cognito 는 레거시 모듈 재사용(복사하지 않는다)
check "TASK-65#5 lb_node 모듈 테스트 존재" test -f "$P/modules/lb_node/tests/lb_node.tftest.hcl"
check "TASK-65#5 enable_lb_node 기본 false" sh -c "grep -A3 'variable \"enable_lb_node\"' $P/variables.tf | grep -q 'default *= false'"
check "TASK-65#5 cognito 는 ../main/modules/cognito 재사용" sh -c "grep -q 'source *= \"../main/modules/cognito\"' $P/main.tf && test ! -d $P/modules/cognito"
for k in enable_lb_node enable_snapshots budget_monthly_usd budget_email cognito_domain_prefix; do
  check "TASK-65#5 envs/prod.tfvars 에 $k" grep -Eq "^$k += " "$P/envs/prod.tfvars"
done

# TASK-65#6 TASK-65#B3 관리형 서비스 리소스를 만들지 않는다: infra/platform 과 그것이 부르는 모듈(../main/modules/*)의 aws 리소스 종류가
# 모두 허용 목록 안이어야 한다(EC2·VPC·IAM·EBS·DLM 스냅샷·Budgets·Cognito). 금지 목록(NAT·ALB/NLB·CloudFront·RDS·MSK·ECR·CloudWatch 알람)은 따로 본다
B3_ALLOWED='^aws_(vpc|subnet|internet_gateway|route|route_table|route_table_association|security_group|vpc_security_group_(ingress|egress)_rule|instance|eip|ebs_volume|volume_attachment|iam_role|iam_role_policy|iam_role_policy_attachment|iam_instance_profile|dlm_lifecycle_policy|budgets_budget|cognito_[a-z_]+)$'
B3_FORBIDDEN='^aws_(nat_gateway|lb|alb|elb|lb_listener|lb_target_group|cloudfront_[a-z_]+|db_instance|rds_[a-z_]+|msk_[a-z_]+|ecr_[a-z_]+|cloudwatch_metric_alarm|cloudwatch_composite_alarm|elasticache_[a-z_]+|mq_broker|opensearch_domain)$'
# b3_types <스택 디렉터리> — 스택과 그것이 source 로 부르는 로컬 모듈(재귀)의 resource 종류
b3_types() {
  local seen=" " queue=("$1") d src
  while [ ${#queue[@]} -gt 0 ]; do
    d="$(cd "${queue[0]}" && pwd -P)"; queue=("${queue[@]:1}")
    case "$seen" in *" $d "*) continue ;; esac
    seen+="$d "
    grep -hoE '^resource "aws_[a-z0-9_]+"' "$d"/*.tf | sed -E 's/^resource "(.*)"/\1/'
    while IFS= read -r src; do queue+=("$d/$src"); done < <(grep -hoE '^\s*source\s*=\s*"\.[^"]*"' "$d"/*.tf | sed -E 's/.*"(.*)"/\1/')
  done | sort -u
}
b3_check() {
  local types bad
  types="$(b3_types "$1")"
  [ -n "$types" ] || { echo "리소스를 못 찾았다"; return 1; }
  bad="$(grep -vE "$B3_ALLOWED" <<<"$types")"; [ -z "$bad" ] || { echo "허용 목록 밖: $bad"; return 1; }
  bad="$(grep -E "$B3_FORBIDDEN" <<<"$types")"; [ -z "$bad" ] || { echo "관리형: $bad"; return 1; }
}
check "TASK-65#B3 infra/platform(+ 부르는 모듈)의 리소스 종류는 허용 목록 안이고 관리형(NAT·LB·CloudFront·RDS·MSK·ECR·알람)이 없다" b3_check "$P"
t_b3_follows() {
  local t r; t="$(b3_types "$P")"
  for r in aws_vpc aws_cognito_user_pool aws_ebs_volume aws_dlm_lifecycle_policy aws_budgets_budget aws_instance; do
    grep -qx "$r" <<<"$t" || { echo "없음: $r"; echo "$t"; return 1; }
  done
}
check "TASK-65#6 b3_types 는 network·cognito·k3s_node·lb_node 모듈까지 따라간다" t_b3_follows
t_b3_red() {
  local d; d="$(mktemp -d "$TMP/b3.XXXX")"
  cp -R "$P" "$d/platform"; mkdir -p "$d/main"; cp -R infra/main/modules "$d/main/"
  rm -rf "$d/platform/.terraform" "$d"/platform/modules/*/.terraform
  printf 'resource "aws_nat_gateway" "x" {}\n' >>"$d/main/modules/network/main.tf"
  ! b3_check "$d/platform" >/dev/null 2>&1 || { echo "모듈 안의 NAT 를 못 잡았다"; return 1; }
  sed -i.bak '/aws_nat_gateway/d' "$d/main/modules/network/main.tf"
  printf 'resource "aws_cloudwatch_metric_alarm" "x" {}\n' >>"$d/platform/main.tf"
  ! b3_check "$d/platform" >/dev/null 2>&1 || { echo "알람을 못 잡았다"; return 1; }
}
check "TASK-65#B3 검사는 부르는 모듈 안의 NAT·스택의 CloudWatch 알람을 잡는다(red)" t_b3_red

# TASK-65#6 OPS-5 state 운영 문서: force-unlock(고착된 S3 lockfile) 절차, bootstrap 로컬 state 백업·복구
check "TASK-65#6 docs/terraform-state.md 존재" test -f docs/terraform-state.md
for w in "force-unlock <LOCK_ID>" "use_lockfile" ".tflock" "platform/terraform.tfstate" "main/prod/terraform.tfstate" "infra/bootstrap" "백업" "복구" "terraform import" "list-object-versions"; do
  check "TASK-65#6 terraform-state.md 에 '$w'" grep -qF -- "$w" docs/terraform-state.md
done
check "TASK-65#6 platform backend.tf 가 terraform-state.md 를 가리킨다" grep -q 'docs/terraform-state.md' "$P/backend.tf"

# TASK-65#7 README 'AWS 실배포 설계' 절: 구성도(diagrams/platform-aws.dot → docs/diagrams/platform-aws.svg), 비용 표(decision-11),
# apply 하지 않는 이유, TASK-52 로 넘기는 항목. SVG 최신 여부는 repo_test 의 build-diagrams.py --check 가 본다
check "TASK-65#7 diagrams/platform-aws.dot 과 docs/diagrams/platform-aws.svg" sh -c 'test -f diagrams/platform-aws.dot && test -f docs/diagrams/platform-aws.svg'
check "TASK-65#7 platform-aws.dot 은 alt 줄과 센티넬 색만" sh -c '
  grep -q "^// alt: " diagrams/platform-aws.dot && ! grep -hoE "(color|fillcolor|fontcolor)=\"#[0-9a-fA-F]{6}\"" diagrams/platform-aws.dot | grep -viE "\"#f000[0-9a-f]{2}\""'
readme_sec() { awk '/^## AWS 실배포 설계/{f=1; print; next} f && /^## /{exit} f' README.md; }
check "TASK-65#7 README 에 '## AWS 실배포 설계' 절" sh -c 'grep -q "^## AWS 실배포 설계" README.md'
for w in "<!--dot:platform-aws-->" "decision-11" "t4g.large" "스팟" "EIP" "합계" "apply 하지 않는" "TASK-52" "infra/platform" "enable_lb_node" "user_data_replace_on_change" "board-node-ready" "docs/terraform-state.md" "sslip.io" "WAF"; do
  check "TASK-65#7 'AWS 실배포 설계' 절에 '$w'" sh -c "$(declare -f readme_sec); readme_sec | grep -qF -- '$w'"
done
check "TASK-65#7 구성 표에 infra/platform" sh -c 'grep -F "| \`infra/\`" README.md | grep -q "infra/platform"'

echo "platform tests: ${passes} passed, ${failures} failed"
[ "$failures" -eq 0 ]
