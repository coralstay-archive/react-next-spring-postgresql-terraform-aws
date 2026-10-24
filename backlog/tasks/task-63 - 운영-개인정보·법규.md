---
id: TASK-63
title: '[운영] 개인정보·법규'
status: To Do
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 09:05'
labels:
  - compliance
  - backend
dependencies:
  - TASK-38
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
실사용자를 받으면 생기는 법적 의무(개인정보보호법 기준).
선행: 없음(백엔드 하드닝 이후 권장).

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 개인정보 항목별 보관 기간과 파기(탈퇴·휴면, 로그의 개인정보 보관 기간)
- [ ] #2 개인정보 처리방침 페이지와 동의 이력
- [ ] #3 개인정보 접근 기록(관리자 조회 기록), 보관과 열람
- [ ] #4 내 정보 내려받기·삭제 요청 흐름
<!-- AC:END -->
