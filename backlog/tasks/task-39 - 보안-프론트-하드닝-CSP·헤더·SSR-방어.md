---
id: TASK-39
title: '[보안] 프론트 하드닝: CSP·헤더·SSR 방어'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - frontend
  - security
dependencies:
  - TASK-37
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Next 서버에서 응답 보안 헤더와 nonce CSP를 만든다(현재 HTML에 보안 헤더가 없어 클릭재킹 가능 — doc-3).
선행: 프론트 Next 서버 분리.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #1) 사이트는 다른 오리진의 iframe 안에 표시되지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 proxy.ts(미들웨어)에서 요청마다 nonce CSP: script-src 'nonce' 'strict-dynamic', object-src 'none', base-uri 'none', frame-ancestors 'none', connect-src self + WS·MQTT 경로
- [ ] #2 HSTS·nosniff·Referrer-Policy·Permissions-Policy·COOP/CORP 헤더
- [ ] #3 Trusted Types 검토, CSP 위반은 report-only 엔드포인트로 수집
- [ ] #4 SSR fetch는 backend 내부 주소만(SSRF 방지), Server Actions 미사용 또는 Origin 검사 유지
- [ ] #5 렌더하는 사용자 URL 스킴 검사(javascript: 등 차단), next/image 원격 허용 목록
- [ ] #6 Playwright로 헤더·CSP 위반 없음·iframe 차단을 회귀 테스트
<!-- AC:END -->
