---
id: DRAFT-13
title: '프론트: 편집기·업로드 견고성 + PRG'
status: Draft
assignee: []
created_date: '2026-10-12 04:09'
labels:
  - frontend
  - board
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-19, FE-21, FE-24, FE-25, FE-26, FE-27, FE-28, FE-29, FE-30. TASK-18 이슈 중 '작성 직후 조회수 0' 포함.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
- PRG: 폼 제출 성공 시 결과 페이지로 replace 이동, 새로고침·뒤로가기 재제출과 이중 제출을 차단한다.

## 비즈니스 규칙
- B1 (AC #2) 한 번의 저장 의도는 글 하나만 만든다
- B2 (AC #7) 저장하지 않은 입력이 경고 없이 버려지지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 FE-19 작성 후 상세는 캐시 대신 재조회(조회수 반영)
- [ ] #2 FE-29·FE-21 성공 후 버튼 비활성 유지 + replace 이동(PRG), 404·403 시 상세 무효화
- [ ] #3 FE-24 표시되지 않는 서버 필드 오류 키 매핑(categorySlug, attachmentIds*), 미매핑 키가 있으면 상단 알림
- [ ] #4 FE-25 첨부 연결 오류 시 문제 첨부를 표시하고 그것만 빼고 다시 저장
- [ ] #5 FE-26 업로드 timeout·취소·삭제, 언마운트 시 abort
- [ ] #6 FE-27 confirm만 다시 시도
- [ ] #7 FE-28 작성·업로드 중 이탈 경고, 히스토리가 없을 때 취소는 /로
- [ ] #8 FE-30 카테고리 목록 오류 표시, 현재 카테고리를 선택지에 유지
<!-- AC:END -->
