---
id: TASK-65
title: '[AWS] 플랫폼 Terraform 얼개 (infra/platform)'
status: In Progress
assignee: []
created_date: '2026-10-29 01:21'
updated_date: '2026-10-29 05:56'
labels:
  - aws
  - platform
dependencies:
  - TASK-64
references:
  - decision-11
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
실배포 구조(decision-11: 관리형 미사용, t4g.large 스팟 1대, LB·CloudFront 없음)의 Terraform 얼개를 새 루트 스택 infra/platform 에 잡는다. 레거시 infra/main 은 TASK-52 AC #5(철거)까지 손대지 않는다. apply 하지 않고 tftest(mock_provider, 오프라인)로만 검증한다.
선행: TASK-64. 후행: TASK-52, TASK-66.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9.8 VM rocky-dev, Podman, VM 5GB — Docker 미사용). 실제 AWS 호출·apply 없음.
- 근거: decision-11(실배포 인프라), doc-4, 계획 승인(2026-10-29).

## 비즈니스 규칙
- B1 (AC #2) 노드는 22번 포트를 열지 않는다(접속은 SSM)
- B2 (AC #3) 데이터 볼륨이 마운트되지 않으면 DB·k3s 데이터 디렉터리를 쓰는 서비스가 뜨지 않는다
- B3 (AC #6) 관리형 서비스 리소스(NAT·ALB/NLB·CloudFront·RDS·MSK·ECR·CloudWatch 알람)를 만들지 않는다 — Cognito·SMS·SSM·Budgets·EBS 스냅샷만 예외
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 infra/platform 루트 스택(versions·providers·variables·main·outputs): bootstrap S3 state 재사용(키 분리), network 모듈(infra/main/modules/network) 재사용, tests/platform.tftest.hcl 을 mock_provider 로 오프라인 실행(make test 에 자동 포함)
- [ ] #2 modules/k3s_node: Rocky 9.8 arm64 AMI 조회, t4g.large 스팟(persistent, 회수 시 stop), IMDSv2 필수, 루트·데이터 EBS 암호화, EIP 1개, SG 는 80·443(IPv4·IPv6)만 — 22 없음, IAM 은 SSM Core + /board/* 파라미터 읽기
- [ ] #3 데이터 볼륨: gp3 별도 볼륨 attach, 첫 포맷은 blkid 종료 코드 2(빈 장치)일 때만(OPS-11), 마운트되지 않으면 데이터 서비스 기동 차단(OPS-9), data_snapshot_id 로 스냅샷에서 만들 수 있음(복원 리허설은 TASK-52)
- [ ] #4 user_data(cloud-init): deploy/rocky/install.sh --profile server 실행, cloud-init 상태 확인 필수·dnf 재시도(OPS-10), user_data 변경 시 정책(교체 또는 무시) 명시(OPS-2). k3s 설치 자리는 TASK-36 스크립트를 부르도록 비워 둠
- [ ] #5 토글 모듈: lb_node(HAProxy, enable_lb_node=false 기본), 데이터 볼륨 스냅샷(enable_snapshots), AWS Budgets 알림(budget_monthly_usd·이메일). 기존 cognito 모듈 재사용
- [ ] #6 관리형 리소스 부재를 tftest 로 고정: NAT·LB·CloudFront·RDS·MSK·ECR·CloudWatch 알람이 없고, lb_node 를 켜면 생긴다. force-unlock·bootstrap state 백업 절차 문서(OPS-5)
- [ ] #7 README 'AWS 실배포 설계' 절: 구성도(diagrams/*.dot → SVG), 비용 표(decision-11), apply 하지 않는 이유와 TASK-52 로 넘기는 항목
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (승인된 계획 2026-10-29, decision-11)
1. AC#1 infra/platform 루트 스택: versions(>=1.10, aws ~>6.0, lock 은 3플랫폼 해시 — darwin_arm64·linux_arm64·linux_amd64), providers(us-east-1, default_tags), backend.tf(bootstrap S3 state 재사용, key=platform/terraform.tfstate, use_lockfile), variables·main·outputs. network 는 ../main/modules/network 재사용. tests/platform.tftest.hcl(mock_provider, AMI 등 data 는 override_data) — scripts/test.sh 가 tests 디렉터리를 자동으로 찾는다.
2. AC#2 modules/k3s_node: data aws_ami(owner 792107900819, Rocky-9-EC2-Base-9.8-*aarch64*), aws_instance(t4g.large 기본, instance_market_options spot persistent + interruption stop, metadata_options http_tokens=required, root gp3 암호화), aws_eip 1개, SG 80·443 ingress IPv4·IPv6만(22 없음, egress all), IAM role + AmazonSSMManagedInstanceCore + ssm:GetParameter(s)ByPath on parameter/board/*.
3. AC#3 데이터 볼륨: aws_ebs_volume gp3 암호화(snapshot_id = var.data_snapshot_id, null 허용) + aws_volume_attachment. cloud-init 스크립트(templatefile)가 NVMe 장치를 볼륨 ID 로 찾고(/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_vol...), blkid 종료 코드 2 일 때만 mkfs.xfs, 그 밖의 오류면 실패, /data 에 마운트(fstab UUID, nofail 아님). 데이터 서비스(k3s 자리)는 systemd RequiresMountsFor=/data 드롭인으로 마운트 없이는 기동하지 않는다. 템플릿 렌더 결과를 tftest 와 셸 테스트로 확인.
4. AC#4 user_data: cloud-init 이 저장소 고정 커밋의 deploy/rocky/install.sh --profile server 를 실행(dnf 재시도 3회), 끝에 cloud-init status 확인 필수. user_data 변경 정책: user_data_replace_on_change=true(노드는 가축 — 데이터는 별도 볼륨) 를 명시하고 README 에 이유. k3s 설치 자리는 TODO 대신 'TASK-36 스크립트 호출' 훅 변수(k3s_install_cmd, 기본 빈 값)로 둔다.
5. AC#5 modules/lb_node(HAProxy, count = enable_lb_node ? 1 : 0, 기본 false, t4g.small), aws_dlm_lifecycle_policy(enable_snapshots), aws_budgets_budget(budget_monthly_usd·budget_email, 이메일 없으면 만들지 않음), cognito 모듈 재사용(콜백 URL 은 변수).
6. AC#6 tftest: plan 결과에 aws_nat_gateway·aws_lb·aws_cloudfront_distribution·aws_db_instance·aws_msk_cluster·aws_ecr_repository·aws_cloudwatch_metric_alarm 이 없음을 고정(설정 파일 grep 셸 테스트 + tftest assert), enable_lb_node=true 면 생긴다. docs/terraform-state.md 에 force-unlock·bootstrap state 백업 절차(OPS-5).
7. AC#7 README 'AWS 실배포 설계' 절 + diagrams/platform-aws.dot → docs/diagrams/platform-aws.svg(scripts/build-diagrams.py), 비용 표(decision-11), apply 하지 않는 이유와 TASK-52 로 넘기는 항목.
- 실제 AWS 호출·apply·plan(자격 증명) 없음. 구현은 서브에이전트, 부모가 make test 재검증 → 독립 리뷰 → Done.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
AC#1 (0c138a9): infra/platform 루트 스택(versions·providers·backend·variables·main·outputs·envs), bootstrap 버킷 재사용 key=platform/terraform.tfstate, network 모듈 재사용, lock 3플랫폼 해시(aws 6.68.0 — infra/main 과 같은 버전), tests/platform.tftest.hcl + scripts/test/platform_test.sh(test.sh 가 부른다), test.sh 루트 목록에 infra/platform

AC#2: modules/k3s_node — Rocky 9.8 arm64 AMI(792107900819), t4g.large 스팟 persistent/stop, IMDSv2, 루트 gp3 암호화, EIP 1개, SG 80·443 IPv4·IPv6 만(B1: 22 없음, key_name 없음), IAM SSM Core + /board/* 읽기(+SSM 경유 kms:Decrypt). 모듈 테스트 tests/k3s_node.tftest.hcl

AC#3: k3s_node 데이터 볼륨 gp3 암호화(data_snapshot_id 로 스냅샷 복원, final_snapshot, Snapshot 태그), /dev/sdf attach(stop 후 분리). files/data-volume.sh — 볼륨 ID by-id 링크로만 장치 탐색, blkid -p 2 일 때만 mkfs.xfs, 그 밖 코드는 실패(OPS-11), k3s.service 드롭인 RequiresMountsFor + ExecStartPre mountpoint 를 먼저 씀(B2, OPS-9). fstab 은 nofail(+device-timeout) — 계획의 'nofail 아님'과 다름: 볼륨이 없을 때 비상 모드로 빠져 SSM 도 못 쓰게 되는 것을 피하고, 서비스 차단은 드롭인이 맡는다(리뷰 확인 필요). k3s data-dir·local-path 를 /data 위로(config.yaml.d 드롭인). 셸 테스트는 platform_test.sh 가짜 명령

AC#4: user_data — 저장소 고정 커밋(repo_commit 40자리 검증)을 git fetch --depth 1 로 받아 deploy/rocky/install.sh --profile server --user rocky, dnf·fetch·install.sh 재시도 3회, set -euo pipefail, 끝 표식 /var/lib/board-node/bootstrap.done. files/node-ready.sh → board-node-ready(cloud-init status --wait 가 done + 표식일 때만 0; error·degraded 실패), 출력 node_ready_check. user_data_replace_on_change=true(OPS-2). k3s_install_cmd(기본 빈 값 — TASK-36 스크립트 자리)

AC#5: modules/lb_node(HAProxy t4g.small 온디맨드, 80·443 TCP 패스스루 → 노드 사설 IP, SSM, EIP, count=enable_lb_node 기본 false), snapshots.tf(DLM, enable_snapshots 기본 true, 7개), budgets.tf(budget_email 없으면 안 만듦, ACTUAL 80%·FORECASTED 100%), cognito 는 ../main/modules/cognito 재사용(공개 주소 = domain_name 또는 <EIP>.sslip.io, 추가 콜백 변수)
<!-- SECTION:NOTES:END -->
