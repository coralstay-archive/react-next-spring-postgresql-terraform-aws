---
id: DRAFT-25
title: '[비용] Infracost 로컬·CI'
status: Draft
assignee: []
created_date: '2026-10-29 01:21'
updated_date: '2026-10-29 01:22'
labels:
  - cost
  - platform
dependencies:
  - TASK-64
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
