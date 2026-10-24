---
id: TASK-37
title: '[플랫폼] 프론트 Next 서버 분리'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 05:06'
labels:
  - frontend
  - platform
dependencies:
  - TASK-36
references:
  - decision-3
  - decision-10
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
프론트를 Next.js standalone 서버 컨테이너로 바꿔 backend와 별도 서비스로 배포한다. 둘은 API로만 대화한다. 인그레스 한 호스트(board.localhost)에서 / → frontend, /api → backend로 나눠 SESSION·XSRF 쿠키를 유지한다.
선행: k3d 로컬 플랫폼 부트스트랩.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #3) 브라우저 입장에서 프론트와 API는 같은 오리진이다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 NEXT_OUTPUT=export|standalone 빌드 모드, frontend Dockerfile(arm64/amd64), standalone이 150MB 미만 메모리로 기동
- [ ] #2 SSR은 클러스터 내부 주소로만 backend를 호출하고, 넘기는 쿠키·헤더는 허용 목록만
- [ ] #3 ingress 경유 로그인 → 글쓰기 → CSRF 흐름이 Playwright(E2E_TARGET=k3d)로 통과
- [ ] #4 export 빌드와 기존 vitest·E2E가 계속 통과(AWS 레거시 경로 유지)
- [ ] #5 /api 경로에 trailing-slash 리다이렉트가 걸리지 않는다
<!-- AC:END -->
