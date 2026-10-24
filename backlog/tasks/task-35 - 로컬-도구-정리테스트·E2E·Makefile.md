---
id: TASK-35
title: 로컬 도구 정리(테스트·E2E·Makefile)
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-24 03:05'
labels:
  - dx
dependencies:
  - TASK-19
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: OPS-38, OPS-39, OPS-40, OPS-41. AWS 불필요.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 OPS-38 build-diagrams --check는 고정 환경에서만 비교하고 차이 목록 출력
- [x] #2 OPS-39 e2e.sh PID 기반 종료와 KILL 격상
- [x] #3 OPS-40 lock 파일이 바뀌면 npm ci
- [x] #4 OPS-41 배포 타깃에 .env 전역 export가 섞이지 않게
- [x] #5 Next.js를 보안 패치가 반영된 버전으로 올리고 전체 테스트·빌드·E2E 통과 확인(16.4.0 빌드 시 취약점 안내, TASK-29에서 발견)
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. (#1 OPS-38) 렌더 환경 고정값 파일 diagrams/render-env.txt(graphviz 버전·OS)를 둔다. build-diagrams.py --check 는 현재 환경(dot -V, OS)이 고정값과 같을 때만 SVG 를 바이트 비교하고, 다르면 바이트 비교를 SKIP 하되 글자(<title>·<text>) 구조 비교·문서 블록·SVG 존재/잔여 검사는 계속한다. 다르면 파일 목록과 unified diff 일부를 출력한다. --env 로 현재 환경을 출력. 테스트: scripts/test/tools_test.sh 에서 가짜 dot 으로 임시 트리에 대해 일치/좌표만 다름/글자 다름/문서 블록 다름을 검사.
2. (#2 OPS-39) 프로세스 관리를 scripts/_proc.sh 로 분리: 백그라운드 프로세스를 자기 프로세스 그룹(set -m)으로 띄워 PGID 를 기록하고, 정리 때 그룹에 TERM → E2E_STOP_TIMEOUT(기본 10초) 안에 안 끝나면 KILL. 포트 기반 lsof kill 은 없앤다. E2E_KEEP=1 은 PGID·명령을 상태 파일에 남기고, 다음 실행이 시작할 때 명령이 일치하는 그룹만 정리한 뒤 compose down -v. 테스트: TERM 을 무시하는 그룹이 KILL 로 끝나는지, 손자 프로세스까지 끝나는지, 관계없는 프로세스는 살아 있는지, 상태 파일의 PID 가 다른 명령이면 죽이지 않는지.
3. (#3 OPS-40) scripts/frontend-deps.sh: package.json·package-lock.json 해시를 node_modules/.deps-stamp 와 비교해 다르거나 없으면 npm ci 후 스탬프 기록(실패 시 스탬프 안 씀). test.sh, Makefile frontend-dev/test/build/install(--force), e2e.sh 가 이걸 쓴다. 테스트: 가짜 npm 으로 처음·같음·lock 변경·실패 경우.
4. (#4 OPS-41) Makefile 의 루트 env 파일 -include + 전역 export 를 없애고, 로컬 실행 타깃(dev-db, dev-storage, dev-db-down, backend-run, frontend-dev)만 레시피 안에서 env 파일을 읽는다(set -a; . 로 읽기). 배포·put-secrets·tf-outputs·smoke·test·e2e 에는 섞이지 않는다. 테스트: 임시 디렉터리에 Makefile 복사 + env 파일 마커, 가짜 terraform 이 본 환경에 마커가 없고 가짜 gradlew(backend-run)는 마커를 본다.
5. (#5) npm 레지스트리에서 16.x 최신 패치(16.4.3) 확인, next·eslint-config-next 를 같은 버전으로 npm install 해 lock 갱신. make test, npm run build(보안 안내 사라짐 확인), make e2e.
6. README·docs 갱신, 마지막에 make test·make e2e.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
TASK-23 진행 중 관찰(2026-10-22): 머신 부하가 높을 때(load avg 약 45) 커밋 훅 make test 가 간헐 실패 — 프론트 vitest 5초 타임아웃(post-editor, post-detail), DbFailureTest 컨텍스트 기동 중 Flyway 첫 접속 EOF(Docker 포트 프록시 추정). 재실행하면 통과. loginSlidingWindow(Retry-After 891)는 TASK-23 브랜치 f53619f 에서 고침.

독립 리뷰(2026-10-24) 반영 결정(유저): (1) 다이어그램 --check는 환경이 달라도 구조(요소 태그 순서, var(--d-*) 색 토큰 순서, 모양·화살표 종류 개수)까지 비교하고, CI에 graphviz를 설치해 검사를 돌린다. (2) 로컬 env 파일 로더는 이미 정의된 변수(명령줄·환경변수)를 덮지 않는다, 테스트 타깃은 env 파일을 읽지 않는 것 유지. (3) frontend-deps.sh의 npm ci에 동시 실행 잠금과 안내. (4) 남은 npm audit(high 5·moderate 2)은 새 draft로. 참고: 이 태스크의 구현 커밋들은 서브에이전트가 frontend/ 에서 커밋해 커밋 훅이 건너뛰어졌다(훅이 cwd의 .git·backlog만 확인) — 부모가 루트에서 make test로 따로 검증, 훅 자체는 유저 승인으로 별도 수정.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
로컬 도구를 정리하고 Next.js 보안 업그레이드를 했다.
- AC#1 OPS-38: build-diagrams --check는 diagrams/render-env.txt(graphviz 버전·OS)와 같을 때만 바이트 비교, 다르면 구조(글자·요소 태그·var(--d-*) 색 토큰·모양 종류 개수)를 비교하고 차이 파일 목록과 diff 일부 출력. CI에 graphviz 설치(Linux 2.43에서도 통과 확인).
- AC#2 OPS-39: e2e.sh가 백엔드·next dev를 프로세스 그룹으로 띄우고 TERM 후 E2E_STOP_TIMEOUT(10초)에 KILL, 포트 기반 kill 제거, E2E_KEEP 그룹은 시작 시각으로 PID 재사용을 구분해 다음 실행이 정리.
- AC#3 OPS-40: scripts/frontend-deps.sh가 package.json·lock 해시가 바뀌면 npm ci(동시 실행 잠금, 죽은 잠금 정리, node_modules 재생성 안내).
- AC#4 OPS-41: Makefile 전역 env 파일 include·export 제거 — 로컬 실행 타깃에서만 읽고, 이미 정의된 변수(명령줄·환경변수)는 덮지 않음. 테스트·배포 타깃은 읽지 않음(DRY_RUN 덮어쓰기 위험을 테스트로 재현·차단).
- AC#5: next·eslint-config-next 16.4.0 → 16.4.3(critical 권고 3건 해결, 빌드 보안 안내 사라짐).
추가: 부하에서 간헐 실패하던 백엔드 테스트(DbFailureTest 2건, OAuth 읽기 타임아웃) 안정화 — load avg 60~77에서 3회 연속 통과.
리뷰 중 발견: 커밋 훅이 cwd의 .git·backlog만 확인해 하위 디렉터리 세션에서 게이트가 꺼지던 결함 — 유저 승인으로 ~/.claude 훅 8개에 project_root(git toplevel) 적용(백업은 스크래치패드).
검증: 루트에서 cleanTest 후 make test 통과(backend 884, frontend 577, repo 236, deploy 139, tools 42, terraform 58), make e2e 8/8.
후속: DRAFT-24(npm audit 남은 high 5·moderate 2).
<!-- SECTION:FINAL_SUMMARY:END -->
