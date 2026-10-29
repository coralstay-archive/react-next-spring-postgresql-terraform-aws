---
id: TASK-66
title: '[비용] Infracost 로컬·CI'
status: In Progress
assignee: []
created_date: '2026-10-29 01:21'
updated_date: '2026-10-29 07:05'
labels:
  - cost
  - platform
dependencies:
  - TASK-65
references:
  - decision-11
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Terraform 변경의 월 비용을 로컬(make cost, VM 안)과 CI(PR 코멘트)에서 본다. 가격 조회는 Infracost 호스팅 API(무료 키, 리소스 메타데이터만 전송 — 유저 승인 2026-10-29). 상한 검사는 오프라인 셸 테스트로 고정한다.
선행: TASK-65.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9.8 VM rocky-dev, Podman, VM 5GB — Docker 미사용). 실제 AWS 호출·apply 없음.
- 근거: decision-11(실배포 인프라), doc-4, 계획 승인(2026-10-29).

## 비즈니스 규칙
- B1 (AC #4) 월 추정 비용이 상한(기본 $30)을 넘으면 실패한다
- B2 (AC #3) API 키는 저장소·.env 에 두지 않고, 없으면 경고 후 건너뛴다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 infracost CLI 버전 고정 설치: VM 은 install.sh dev 프로필, CI 는 체크섬 검증 다운로드
- [ ] #2 infracost.yml(infra/platform, 비교용 infra/main) + infracost-usage.yml(가동 시간·스냅샷 크기 등). 스팟 가격이 반영되는지 실측하고, 안 되면 usage 파일·문서로 보정
- [ ] #3 make cost·make cost-diff(main 대비)가 맥에서 실행하면 VM 안에서 돈다(vm_proxy_if_mac 재사용). API 키가 없으면 경고 후 건너뜀, 키 등록 방법(VM 에서 infracost configure set api_key)을 README 에
- [ ] #4 scripts/cost-guard.sh: breakdown JSON 의 totalMonthlyCost 가 상한(기본 30 USD, 변수로 조정)을 넘으면 실패. 샘플 JSON 으로 셸 테스트를 고정해 make test 에서 오프라인으로 돈다
- [ ] #5 CI: infra 변경 PR 에 비용 표·차이를 코멘트하고 cost-guard 실행(GitHub secret INFRACOST_API_KEY, 없으면 건너뜀), actionlint 통과
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (승인된 계획 2026-10-29)
1. AC#1 infracost CLI 고정 버전: deploy/rocky/install.sh dev 프로필의 고정 버전 목록에 추가(arm64 릴리스 tarball + sha256 검증, 이미 같은 버전이면 건너뜀). CI 는 같은 버전·체크섬으로 내려받는 스크립트(scripts/ci/install-infracost.sh)를 공유.
2. AC#2 infracost.yml(projects: infra/platform — envs/prod.tfvars, 비교용 infra/main) + infracost-usage.yml(월 가동 시간 730, 데이터·루트 볼륨 스냅샷 크기, 데이터 전송량). 스팟 반영은 키를 등록한 뒤 실측해서 노트에 기록하고, 반영되지 않으면 usage 파일·README 로 보정.
3. AC#3 make cost·make cost-diff: scripts/cost.sh(맥에서는 vm_proxy_if_mac 로 VM 안 실행). 키가 없으면(infracost configure get api_key 비어 있음, INFRACOST_API_KEY 없음) 경고 후 0 으로 건너뜀. cost-diff 는 main 브랜치 기준 breakdown JSON 과 비교. 키 등록 방법 README.
4. AC#4 scripts/cost-guard.sh <breakdown.json> [상한, 기본 30]: totalMonthlyCost 를 jq 로 읽어 상한 초과면 실패, 형식 오류도 실패. scripts/test/cost_test.sh 가 샘플 JSON(fixtures)으로 고정, test.sh 가 부른다(오프라인).
5. AC#5 .github/workflows/cost.yml: infra/** 변경 PR 에서 breakdown·diff → infracost comment github(PR 코멘트), cost-guard. 키(secret INFRACOST_API_KEY) 없으면 건너뜀. permissions 최소(pull-requests: write, contents: read), actionlint 통과.
- 실제 AWS 호출 없음. Infracost 는 리소스 메타데이터만 가격 API 로 보낸다(유저 승인). 구현은 서브에이전트, 부모가 make test 재검증 → 독립 리뷰 → Done.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
AC#1: infracost v0.10.47 고정 — install.sh dev 프로필(arm64 tarball + sha256, installed/mark 로 같은 버전 건너뜀), CI 는 scripts/ci/install-infracost.sh(arm64·amd64 sha256, 같은 버전이면 건너뜀). sha256 은 릴리스의 .sha256 파일과 내려받은 파일 해시가 같음을 확인. VM 재설치 후 infracost --version = v0.10.47 확인. cost_test.sh 10개(가짜 curl·uname: sha 불일치 실패·arch 선택·건너뜀·shellcheck). 참고: infracost 가 v2.x(infracost/cli) 업데이트를 안내하지만 계획대로 v0.10.x 고정.

AC#2: infracost.yml(platform=infra/platform, 비교용 main=infra/main, envs/prod.tfvars, 공통 usage 파일) + infracost-usage.yml. 스팟: Infracost v0.10.47 소스(internal/providers/terraform/aws/instance.go)는 spot_price 가 있을 때만 purchaseOption=spot — 노드는 instance_market_options 라 온디맨드로 계산됨(소스 확인, 실측 아님). 보정: module.k3s_node.aws_instance.this monthly_hrs=282(=730×0.026/0.0672, 주석 단가와 산식을 cost_test 가 확인). DLM 스냅샷은 미지원 리소스라 README 에 따로 적음. aws_data_transfer 10GB. **실측 미완**: API 키가 없어 breakdown 을 돌리지 못함 — 유저가 VM 에서 키를 등록한 뒤 make cost 로 (1) 노드가 on_demand 로 찍히는지 (2) 보정 후 합계, (3) aws_data_transfer·main 프로젝트 파싱을 확인해 노트에 남겨야 함. cost_test #2 11개.
<!-- SECTION:NOTES:END -->
