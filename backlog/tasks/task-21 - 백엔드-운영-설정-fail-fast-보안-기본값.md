---
id: TASK-21
title: '백엔드: 운영 설정 fail-fast + 보안 기본값'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 10:04'
labels:
  - backend
  - security
dependencies:
  - TASK-20
references:
  - decision-5
  - decision-7
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-8, BE-9, BE-14, BE-26, BE-12.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 운영에서 공개된 dev 비밀값으로는 앱이 기동하지 않는다
- B2 (AC #3) 가입만으로 ADMIN 권한을 얻을 수 없다
- B3 (AC #4) 운영 로그에 OTP 원문이 남지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 BE-8 운영 모드(PUBLIC_BASE_URL이 https)에서 dev JWT_SECRET·OTP_HASH_KEY·COOKIE_SECURE=false·기본 DB 비밀번호면 기동 실패
- [x] #2 BE-9 운영 모드에서 CLIENT_IP_HEADER 미설정이면 기동 실패, 헤더가 빠진 요청은 경고 로그
- [x] #3 BE-14 ADMIN_EMAIL 자동 승격은 기동 시 이미 있는 계정에만 하고, 가입 시 자동 승격은 하지 않는다
- [x] #4 BE-26 운영 모드에서 SMS_PROVIDER=console이면 기동 실패, console 출력은 번호 마스킹
- [x] #5 BE-12 pg_trgm 확장 권한 요구사항을 문서화하고 기동 실패 메시지를 개선
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (Plan-Review-Go)
0. 운영 모드 판정: app.public-base-url이 https://이면 운영으로 본다(설정 app.security.production-mode=auto|true|false로 강제 가능). 판정과 검사는 기동 시 한 곳(ProductionSafetyCheck, ApplicationRunner 또는 @PostConstruct 검증 빈)에서 하고, 실패하면 무엇을 고쳐야 하는지 모두 나열한 메시지로 기동을 중단한다.
1. AC#1 BE-8: 운영 모드에서 JWT_SECRET이 dev 기본값(또는 저장소 공개 값 목록), OTP_HASH_KEY 기본값, COOKIE_SECURE=false, DB_PASSWORD가 기본값(board)이면 기동 실패. 로컬·테스트(http)는 경고만 유지.
2. AC#2 BE-9: 운영 모드에서 CLIENT_IP_HEADER가 비어 있으면 기동 실패. 설정돼 있는데 요청에 그 헤더가 없으면 WARN 로그(요청 폭주 시 로그 폭주 방지를 위해 샘플링 또는 rate-limited 로깅).
3. AC#3 BE-14: 가입 시 자동 승격 제거. AdminBootstrap은 기동 시 이미 있는 LOCAL 계정만 승격한다. 남는 위험(재시작 전에 누가 그 이메일로 먼저 가입)을 막기 위해 승격 조건에 해당 계정 생성 시각이 ADMIN_EMAIL을 설정한 배포 이후일 것을 둘 수 없으므로, 대신 운영 절차로 고정한다: (a) 관리자가 직접 가입 → (b) 운영자가 계정 소유를 확인 → (c) 재배포 또는 재시작. docs/first-deploy.md와 README에 반영. 승격 시 INFO 로그(계정 id). 같은 이메일 LOCAL 계정이 없으면 아무것도 하지 않는다.
   - E2E 영향: scripts/e2e.sh와 frontend/e2e는 ADMIN_EMAIL 가입 즉시 ADMIN을 가정한다. E2E는 관리자 계정을 백엔드 기동 전에 시드하거나(예: e2e 전용 시드 SQL 또는 기동 후 재시작) 흐름을 바꾸고 make e2e 녹색을 확인한다.
4. AC#4 BE-26: 운영 모드에서 SMS_PROVIDER가 console(또는 빈 값)이면 기동 실패. ConsoleSmsSender 로그는 번호를 마스킹(+82 10-****-5678)하고, 인증번호 원문은 로컬에서만 출력하도록 유지하되 운영 모드에서는 어떤 경우에도 출력 금지. E2E가 로그에서 코드를 읽는 방식은 그대로 동작해야 한다(로컬은 http).
5. AC#5 BE-12: pg_trgm 확장 생성 권한 요구사항을 README·first-deploy에 기록. V8 실패 시 원인을 알 수 있도록 기동 실패 메시지 개선(Flyway 콜백 또는 마이그레이션 SQL 주석 + 문서).
6. TASK-20 이월: Hibernate SqlExceptionHelper가 DB 상세(개인정보)를 ERROR 로그로 남기는 문제. 운영 모드에서 해당 로거를 WARN 이상 요약으로 낮추거나 마스킹하는 방안을 결정하고 적용(테스트로 확인).
- 각 AC는 red 실행으로 실패를 확인한 뒤 green 커밋(red 증거를 메시지에), 필요하면 refactor 커밋. 표식 TASK-21#n, #B1~#B3.
- 리뷰: 구현 후 독립 서브에이전트 리뷰 → fix·refactor 커밋.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
TASK-20 리뷰 이월(2026-10-12): Hibernate SqlExceptionHelper가 JPA 경로의 SQL 오류를 자체 ERROR로 로깅하고, 여기에 DB 상세 'Key (email)=(...)'(개인정보)가 포함될 수 있다. 앱 처리기는 더 이상 메시지를 로깅하지 않는다. 운영 로그 개인정보 정책과 함께 이 로거 레벨이나 마스킹을 결정한다.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
운영 설정 기동 차단과 보안 기본값. 운영 모드는 PUBLIC_BASE_URL이 https면 켜지고 PRODUCTION_MODE로 강제할 수 있다. 배포 경로의 render-env는 PRODUCTION_MODE=true가 기본이다. ProductionSafetyCheck(PriorityOrdered BFPP)가 DB·Flyway·Tomcat보다 먼저 검사하고, 고칠 항목을 비밀값 노출 없이 모두 나열하며 기동을 멈춘다. 검사 대상: 공개 prefix(dev-only-, change-me, e2e-only-) 비밀값, JWT_SECRET=OTP_HASH_KEY, 서로 다른 문자 10개 미만 비밀값, COOKIE_SECURE≠true, DB_PASSWORD 기본값, CLIENT_IP_HEADER 미설정, SMS_PROVIDER≠aws(B1). https인데 검사를 끄면 WARN. 헤더가 빠진 요청은 1분에 한 번 WARN(PII 없음). ADMIN 부트스트랩: 가입 시 자동 승격 제거, ADMIN이 하나도 없을 때 기동 시 한 번만 기존 LOCAL 계정을 승격(B2). 절차는 가입 → 운영자 확인 → ADMIN_EMAIL 설정 후 재배포 → 확인 후 반드시 삭제(문서 반영). SMS console은 항상 번호 마스킹, 운영에서는 본문 미출력(B3). pg_trgm 권한 문서와 FailureAnalyzer(확장 생성 실패 SQLException일 때만). 개인정보 로그: pgjdbc logServerErrorDetail=false(앱·Hibernate)와 postgres log_error_verbosity=terse·log_min_error_statement=panic(서버 로그). Terraform app_env에 SMS_PROVIDER=aws. E2E는 관리자를 시드 가입 후 재시작해 승격(로그 30초 대기). 진행: Plan → AC마다 Red → Green → 독립 리뷰(중상 1, 중 2, 경미 5) → fix 커밋 5개로 전부 반영. 검증: cleanTest 후 REQUIRE_LINTERS=1 make test(backend 643 스킵 0, frontend 160, repo 208, deploy 135, terraform 전부) 통과, make e2e 8/8.
<!-- SECTION:FINAL_SUMMARY:END -->
