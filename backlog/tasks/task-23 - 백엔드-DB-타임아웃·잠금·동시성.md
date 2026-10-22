---
id: TASK-23
title: '백엔드: DB 타임아웃·잠금·동시성'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-22 05:03'
labels:
  - backend
  - data
dependencies:
  - TASK-20
references:
  - decision-2
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-3, BE-4, BE-27, BE-39, BE-29, BE-30, BE-7.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #3) 동시 수정이 다른 사람의 숨김·삭제를 되돌리지 못한다
- B2 (AC #1) DB 장애 중에도 응답은 30초 안에 JSON 오류로 끝난다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 BE-3 hikari connection-timeout, lock_timeout·statement_timeout 설정, DB 장애는 503 db_unavailable, 락 실패는 409 lock_timeout
- [x] #2 BE-4 요청 본문 크기 상한(약 64KB, 첨부 제외) 초과 시 413 JSON
- [x] #3 BE-27·BE-39 글에 @Version 낙관적 잠금, 충돌 시 409 conflict(작성자 수정·관리자 숨김·복구 경쟁)
- [x] #4 BE-29 좋아요도 FOR SHARE 가시성 확인, 글 행 부재 시 404
- [x] #5 BE-30 page×size 상한(예: 10,000행)
- [x] #6 BE-7 시각은 Clock 빈 하나로 통일
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (Plan-Review-Go)
0. 공통: DB 예외 분류를 common.DbErrors 하나로 모은다(원인 사슬의 SQLState·예외 타입). ApiExceptionHandler(MVC 안)와 ApiErrorController(/error, 필터·Spring Session 등 MVC 밖)가 같이 쓴다. 오류 모양은 TASK-20 계약({status,error,message}, ApiException) 그대로. 로그에는 SQLState 만 남긴다(DB 메시지 원문 금지, TASK-21).
1. AC#1 BE-3 타임아웃·락:
   - 설정(application.properties, env 로 조절): spring.datasource.hikari.connection-timeout=${DB_CONNECTION_TIMEOUT_MS:5000}, validation-timeout=2000, 드라이버 속성 options=-c lock_timeout=${DB_LOCK_TIMEOUT:5s} -c statement_timeout=${DB_STATEMENT_TIMEOUT:10s}, socketTimeout=${DB_SOCKET_TIMEOUT_SECONDS:15}(statement_timeout 보다 길게 — 살아 있는 DB 의 느린 쿼리는 57014 로 깔끔히 끝나고, 응답 없는 DB 는 소켓에서 끊는다), connectTimeout=5. Flyway 는 spring.flyway.init-sqls 로 statement_timeout=0(큰 마이그레이션이 잘리지 않게, lock_timeout 은 유지).
   - B2 예산: 커넥션 대기(5s) + 소켓 타임아웃(15s) + 세션 커밋의 커넥션 대기(5s) = 25s < 30s. 기본값으로 이 합이 30초 미만인지 검사하는 테스트.
   - 매핑: 연결 실패(SQLState 08xxx·57P01~03·53xxx, SQLTransient/NonTransientConnectionException, CannotCreateTransactionException·CannotGetJdbcConnectionException·DataAccessResourceFailureException) → 503 db_unavailable. 락 대기 초과 55P03·교착 40P01 → 409 lock_timeout. statement_timeout(57014) → 503 db_timeout(결정: 클라이언트 입장에서는 "잠시 후 재시도"라 503, 원인 구분을 위해 db_unavailable 과 코드는 나눈다).
   - 테스트: 짧은 타임아웃을 준 별도 컨텍스트(자기 PostgreSQL 컨테이너)에서 (a) 컨테이너 pause → 익명 GET·세션 쿠키 요청·Bearer 쓰기가 30초 안에 503 db_unavailable JSON(B2), (b) 테스트 커넥션이 글 행을 FOR UPDATE 로 잡은 채 PATCH → 409 lock_timeout, (c) 테스트 컨트롤러의 pg_sleep → 503 db_timeout. 분류 단위 테스트(40P01 등). sleep 대기 없음(조건 폴링만).
2. AC#2 BE-4 본문 상한: 서블릿 필터(common.RequestBodyLimitFilter, 보안 체인보다 앞)로 app.request.max-body-size=64KB. Content-Length 가 넘으면 읽지 않고 413 payload_too_large JSON. 길이 없는(chunked) 본문은 읽는 만큼 세다 넘으면 예외 → 핸들러가 413. 첨부는 S3 presigned PUT 이라 API 를 거치지 않는다(멀티파트 엔드포인트 없음). 한도 근거: 가장 큰 정상 글(한글 제목 100자 + 본문 20000자 = UTF-8 약 60KB + 태그)이 들어간다 — 이 경우 201 을 테스트로 고정.
3. AC#3 BE-27·BE-39 낙관적 잠금: V12 마이그레이션 posts.version bigint not null default 0, Post @Version. 작성자 PATCH·DELETE 와 관리자 hide·unhide·restore 의 동시 실행 중 늦은 쪽은 ObjectOptimisticLockingFailureException → 409 conflict. 계약: 클라이언트는 버전을 보내지 않는다(If-Match 없음, 기존 API·프론트 호환) — 서버 트랜잭션끼리 겹친 경쟁만 잡는다. 오래 열어 둔 편집 폼의 stale 덮어쓰기 방지(If-Match/version 필드)는 후속으로 남긴다. like_count·view_count 의 SQL ±1 은 버전을 올리지 않는다(좋아요가 수정을 409 로 만들지 않게).
   - 테스트(B1): 테스트 커넥션이 글 행을 잡아 두 요청을 UPDATE 단계에서 줄 세운 뒤(pg_stat_activity 의 Lock 대기 수를 폴링) 풀어서 결정적으로 경쟁시킨다. 관리자 숨김 → 작성자 수정 순이면 수정 409·숨김 유지, 작성자 삭제 → 관리자 unhide 순이면 unhide 409·삭제 유지.
4. AC#4 BE-29 좋아요: PostLikeService 가시성 확인을 select ... for share 로(댓글 작성과 같은 방식). 확인 뒤 숨김·삭제 UPDATE 는 좋아요 트랜잭션이 끝날 때까지 기다리고, 먼저 커밋된 숨김은 보인다. 카운터 갱신·조회가 0행이면(행 부재) 500 이 아니라 404 post_not_found. 테스트: 숨김 UPDATE 를 열어 둔 채 좋아요 → 커밋 후 404, 글 행 hard delete 를 열어 둔 채 좋아요 → 404.
5. AC#5 BE-30 페이지 상한: common.Paging.check(page, size, maxSize) 로 모으고 page × size(오프셋) ≤ 10,000(long 곱으로 오버플로 방지). 넘으면 400 validation + fields.page(기존 파라미터 오류 계약). 글·댓글·관리자 사용자·감사 로그 목록에 같이 적용.
6. AC#6 BE-7 Clock: Post.write 에 now 를 넘기고(@PrePersist 제거), PostService 수정·삭제 시각도 clock.instant(). TokenService·JwtTokenService·ClientIpResolver 는 Clock 빈 주입(JWT 검증기의 만료 판정도 같은 시계). User·UserIdentity·RefreshToken·LocalCredential·Category 의 @PrePersist/@PreUpdate 는 Clock 빈을 주입받는 엔티티 리스너(common.ClockEntityListener, Hibernate SpringBeanContainer)로. LinkIntent 시작·검증도 Clock. 테스트: MutableClock 을 움직이고 글·사용자·토큰 시각이 그 시계를 따르는지, src/main 에 Instant.now()·Clock.systemUTC() 가 ClockConfiguration 밖에 없는지 검사.
- 문서: docs/api.md 오류 표(503 db_unavailable·db_timeout, 409 lock_timeout·conflict(동시 수정), 413), 페이지 상한, 좋아요 잠금. README 설정 표·example.env·deploy/secrets.example.txt 에 DB_* 타임아웃 env.
- 각 AC 는 red 실행으로 실패를 확인한 뒤 green 커밋(red 증거를 메시지에), 필요하면 refactor 커밋. 표식 TASK-23#n, #B1·#B2.
- 리뷰: 구현 후 독립 서브에이전트 리뷰 → fix·refactor 커밋.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
TASK-19 리뷰에서 넘어온 BE-7 우선순위(2026-10-12): 1) Post.java:99 @PrePersist와 PostService.java:128·136의 Instant.now() — POST/PATCH 응답 시각(나노초)과 이후 GET(마이크로초)이 다른 유일한 사용자 노출 지점. PostService에서 clock.instant()를 넘기는 방식으로. 2) TokenService.java:45와 JwtTokenService.java:67의 자체 Clock.systemUTC()를 Clock 빈 주입으로(만료 테스트 가능). 3) User·UserIdentity·RefreshToken·LocalCredential·Category의 @PrePersist와 LinkIntent Instant.now()는 일관성 목적. 선택: src/main에서 Instant.now()/Clock.systemUTC() 금지 검사(ClockConfiguration 제외). 참고: 통합 테스트는 @Primary MutableClock을 쓰므로 테스트 시계 절삭이 CI 수정의 핵심이다.

독립 리뷰(2026-10-21) 반영 결정: (1) AC#4는 카드 문구의 FOR SHARE 대신 SELECT ... FOR NO KEY UPDATE — FOR SHARE는 동시 좋아요끼리 like_count UPDATE로 잠금을 올리다 교착(40P01). NKU는 FK 검사(KEY SHARE)를 막지 않고 잠금 순서(글 행 → post_likes)를 뒤집는 경로도 없음을 리뷰에서 확인. (2) B2 예산은 문장별 statement_timeout만으로는 부족 → PG18의 transaction_timeout을 함께 건다(유저 결정). (3) Flyway는 풀과 분리한 커넥션으로 statement_timeout·socketTimeout을 끈다. (4) JWT 기본 검증기 복원, multipart 끄기·form 본문 64KB, 동시성 테스트 lock_timeout 여유, 문서 정리. (5) 프론트 페이지 상한은 TASK-33 AC로 이월.

타임아웃 기본값 확정(유저, 2026-10-22, 정책이라 나중에 env로 조정 가능): 커넥션 대기 5s, statement_timeout 5s, transaction_timeout 10s(신규), socketTimeout 10s → 느린 DB 25초·응답 없는 DB 20초. transaction_timeout(25P04)은 503 db_timeout + 커넥션 폐기. 한계: 한 요청이 트랜잭션·autocommit 문장 여러 개를 쓰면 요청 전체로는 30초를 넘을 수 있음(문서화만). 후속 후보: 롤백 예외가 원래 예외를 덮을 때 Spring 의 ERROR 로그에 DB 메시지 원문이 실릴 수 있음(TASK-21 로그 정책 확인 필요).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
DB 장애·잠금·동시성 대비를 넣었다.
- AC#1 BE-3: Hikari 커넥션 대기 5s, pgjdbc socketTimeout 10s, 세션 옵션 lock_timeout 5s·statement_timeout 5s·transaction_timeout 10s(env 조정 가능). DB 장애(08xxx·57P0x·53xxx·풀 대기 초과)는 503 db_unavailable, 문장·트랜잭션 시간 초과는 503 db_timeout, 락 대기 초과·교착은 409 lock_timeout. 필터 단계(세션 조회) 오류도 DbFailureFilter가 JSON으로 낸다. Flyway는 풀 밖의 자기 커넥션(타임아웃 없음)을 쓴다. B2: 느린 DB 25초, 응답 없는 DB 20초 안에 끝난다.
- AC#2 BE-4: 요청 본문 64KB 상한(Content-Length·chunked·form 모두), 넘으면 413 payload_too_large. multipart는 끈다.
- AC#3 BE-27·BE-39: posts.version(@Version), 겹친 수정·숨김·삭제의 늦은 쪽은 409 conflict(B1). 클라이언트는 버전을 보내지 않는다.
- AC#4 BE-29: 좋아요는 글 행을 FOR NO KEY UPDATE로 잠그고 가시성을 확인한다(FOR SHARE는 동시 좋아요끼리 교착). 숨김·삭제·없는 글은 404.
- AC#5 BE-30: page×size ≤ 10,000, 넘으면 400 validation.
- AC#6 BE-7: 시각은 Clock 빈 하나(엔티티 리스너, 토큰·JWT 검증 포함). ClockUsageTest가 시스템 시계 직접 호출을 막는다.
독립 리뷰 반영: Flyway 커넥션 분리, transaction_timeout, JWT 기본 검증기 복원, form·multipart 우회 차단, 잠금 테스트 여유.
검증: 커밋 훅 make test 통과(backend 728, repo 222, deploy 135, terraform 57, frontend 160).
이월: 프론트 페이지 상한은 TASK-33. 편집 화면 버전 전송(If-Match)과 새 오류 코드 문구는 후속(TASK-28 후보). 요청 단위 30초 데드라인은 문서화만 했다.
<!-- SECTION:FINAL_SUMMARY:END -->
