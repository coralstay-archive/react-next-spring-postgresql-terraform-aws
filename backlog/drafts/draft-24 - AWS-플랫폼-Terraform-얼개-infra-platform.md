---
id: DRAFT-24
title: '[AWS] 플랫폼 Terraform 얼개 (infra/platform)'
status: Draft
assignee: []
created_date: '2026-10-29 01:21'
updated_date: '2026-10-29 01:22'
labels:
  - aws
  - platform
dependencies:
  - TASK-64
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
