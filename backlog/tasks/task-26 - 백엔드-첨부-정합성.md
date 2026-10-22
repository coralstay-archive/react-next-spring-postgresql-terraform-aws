---
id: TASK-26
title: '백엔드: 첨부 정합성'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-22 11:11'
labels:
  - backend
  - attachments
dependencies:
  - TASK-20
references:
  - decision-3
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-32, BE-33, BE-35, BE-36, BE-37.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #2) CONFIRMED 첨부는 항상 실제 객체가 있다
- B2 (AC #4) 삭제된 글의 이미지는 정책 기간 뒤 공개 URL로 열리지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-32 confirm·head·태그 제거의 S3 오류는 503 storage_unavailable + 로그
- [ ] #2 BE-33 pendingTtl이 지난 confirm 거부, cleanup은 행 삭제 후 객체 삭제, markConfirmed 0행이면 객체 정리
- [ ] #3 BE-35 presign을 트랜잭션 밖에서, S3Client apiCallTimeout
- [ ] #4 BE-36 연결 해제되거나 글이 삭제된 첨부의 객체 정리(지연 삭제 정책)
- [ ] #5 BE-37 스케줄 작업 분산 락(ShedLock 등)
<!-- AC:END -->
