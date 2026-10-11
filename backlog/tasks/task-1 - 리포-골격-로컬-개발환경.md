---
id: TASK-1
title: 리포 골격 + 로컬 개발환경
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:31'
labels:
  - infra
  - dx
dependencies: []
references:
  - decision-1
  - decision-2
  - decision-10
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
모노레포 구조와 로컬 실행 환경, agile-hooks 설정을 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 backend/(Gradle Kotlin DSL, Java 25, Spring Boot 최신 안정판)와 frontend/(Next.js, output:'export', TS, Tailwind, shadcn/ui) 스캐폴드가 각각 빌드·테스트 통과
- [x] #2 docker-compose.yml로 로컬 PostgreSQL 기동, 백엔드가 접속해 /api/actuator/health UP
- [x] #3 Makefile + scripts/test.sh로 백엔드·프론트 테스트 일괄 실행
- [x] #4 .claude-code-agile-hooks.json에 testCommand와 testFirst(sourceGlobs·testGlobs) 설정
- [x] #5 gitignore, env 예시 파일, README 초안
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
모노레포 골격 완성. backend: Spring Boot 4.1.1 / Gradle 9.7.1 KTS / Java 25, context-path /api, Flyway 베이스라인, Testcontainers(postgres:18-alpine). frontend: Next.js 16.4 + React 19.3, output:'export'+trailingSlash, Tailwind v4, shadcn, Vitest. 루트: docker-compose.yml(로컬 PG), Makefile, scripts/test.sh(+scripts/test/repo_test.sh 41개 검사), .claude-code-agile-hooks.json(testCommand=make test, testFirst), example.env(.env* 이름은 보호 훅 때문에 사용 불가), README. 검증: make test 전부 통과(repo 41, backend 3, frontend 1), npm run build로 out/ 생성, 실제 compose PG + bootRun에서 /api/actuator/health UP. 메모: Next16의 cacheComponents/partialPrefetching은 export 모드와 충돌해 제거. 후속: prod health 상세 노출 제한(TASK-2/17), next dev /api 프록시(TASK-13).
<!-- SECTION:FINAL_SUMMARY:END -->
