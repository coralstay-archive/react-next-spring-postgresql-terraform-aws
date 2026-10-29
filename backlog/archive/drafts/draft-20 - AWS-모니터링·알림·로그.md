---
id: DRAFT-20
title: '[AWS] 모니터링·알림·로그'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-29 01:21'
labels:
  - infra
  - aws
  - ops
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-35, OPS-34, OPS-37, OPS-8, OPS-7.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 OPS-35 SNS 토픽과 알람 세트(StatusCheck, CPUCredit, 5xx, 디스크, 메모리, DLM 실패)
- [ ] #2 OPS-34 배포 실패 알림, 롤백과 다운 구분
- [ ] #3 OPS-37 cloud-init·journal·SSM 출력 로그 수집
- [ ] #4 OPS-8 CloudFront 액세스 로그
- [ ] #5 OPS-7 /api 5xx 오류 캐시 TTL 0, 배포 다운타임 문서화
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
[2026-10-29 보관] 유저 결정(decision-11: 관리형 미사용, 스팟 1대, CloudFront 없음, 임시 배포 계획 없음)에 따라 나눠 흡수하고 이 draft는 보관한다. OPS-34 → TASK-61, OPS-37 → TASK-52 AC #3. OPS-35(SNS·CloudWatch 알람)·OPS-7·OPS-8(CloudFront)은 관리형 미사용·CloudFront 없음으로 폐기 — 알림은 Alertmanager(TASK-61).
<!-- SECTION:NOTES:END -->
