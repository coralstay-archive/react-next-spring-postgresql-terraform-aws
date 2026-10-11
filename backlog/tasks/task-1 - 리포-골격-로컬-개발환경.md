---
id: TASK-1
title: 리포 골격 + 로컬 개발환경
status: To Do
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 13:19'
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
- [ ] #1 backend/(Gradle Kotlin DSL, Java 25, Spring Boot 최신 안정판)와 frontend/(Next.js, output:'export', TS, Tailwind, shadcn/ui) 스캐폴드가 각각 빌드·테스트 통과
- [ ] #2 docker-compose.yml로 로컬 PostgreSQL 기동, 백엔드가 접속해 /api/actuator/health UP
- [ ] #3 Makefile + scripts/test.sh로 백엔드·프론트 테스트 일괄 실행
- [ ] #4 .claude-code-agile-hooks.json에 testCommand와 testFirst(sourceGlobs·testGlobs) 설정
- [ ] #5 gitignore, env 예시 파일, README 초안
<!-- AC:END -->
