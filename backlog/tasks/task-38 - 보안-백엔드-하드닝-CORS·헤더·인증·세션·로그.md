---
id: TASK-38
title: '[보안] 백엔드 하드닝: CORS·헤더·인증·세션·로그'
status: To Do
assignee: []
created_date: '2026-10-24 03:46'
updated_date: '2026-10-24 09:05'
labels:
  - backend
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
doc-3 보안 현황 조사의 백엔드 갭을 메운다. 유저 요청: CORS와 주요 취약점 방어를 앞에서 넣는다.
선행: 프론트 Next 서버 분리.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #1) 허용 목록에 없는 오리진은 자격 증명이 실린 교차 출처 요청을 할 수 없다
- B2 (AC #6) 보안 로그에는 비밀번호·토큰·전화번호 원문이 남지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 CORS: 명시적 CorsConfigurationSource(허용 오리진 설정 목록, credentials, 최소 메서드·헤더), 목록 밖 오리진 거부 테스트. WebSocket·SSE·GraphQL·gRPC-Web이 같은 허용 목록을 쓸 수 있는 공통 컴포넌트
- [ ] #2 헤더: 신뢰 프록시 기준 HSTS(forward-headers), /api에 nosniff·frame-ancestors·Referrer-Policy·no-store, /uploads에 nosniff·CSP default-src 'none'·Content-Disposition
- [ ] #3 업로드 확정 시 매직바이트 검사로 선언 타입과 실제 내용 불일치 거부
- [ ] #4 인증: 계정 단위 전역 실패 한도·지연, 유출 비밀번호 검사(HIBP k-anonymity, 설정으로 끔), OAuth PKCE 명시, JWT aud 검증
- [ ] #5 세션: 유휴 타임아웃, 내 세션 목록 조회·폐기 API
- [ ] #6 보안 이벤트 로그(로그인 성공·실패, 권한 거부, CSRF 실패, 레이트리밋)를 구조화해 남기고 PII 마스킹·로그 인젝션 방지
- [ ] #7 요청 제한: Jackson StreamReadConstraints(깊이·길이), Tomcat 연결·keep-alive 타임아웃 명시, 클라이언트 IP 헤더는 신뢰 프록시만 덮어씀
<!-- AC:END -->
