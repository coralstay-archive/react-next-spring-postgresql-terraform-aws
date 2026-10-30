---
id: TASK-27
title: '백엔드: 정리 작업과 관리·조회 견고성'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 05:25'
labels:
  - backend
  - ops
dependencies:
  - TASK-20
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-10, BE-28, BE-38, BE-40. TASK-18 노트의 정리 잡 후보도 포함.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 정리 작업은 보안상 필요한 기록(활성 토큰, 레이트리밋 창)을 지우지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 BE-10 만료·폐기 refresh_tokens, 오래된 otp_challenges·post_views, 미사용 tags 정리 스케줄
- [x] #2 BE-28 조회수 집계 실패는 로그만 남기고 상세 응답은 정상 반환
- [x] #3 BE-38 정지 후 세션 삭제 실패는 로그 + 응답에 경고 필드(정지는 유지)
- [x] #4 BE-40 카테고리 sortOrder 범위 검증과 오버플로 방지
- [x] #5 rate_limit_events 정리: 설정된 app.rate-limit.* window 최댓값보다 오래된 행만 지운다(TASK-22 리뷰 M2)
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. AC#1 BE-10 + AC#5: 새 정리 작업 RetentionCleanup(@Scheduled + @SchedulerLock 'retention-cleanup', run() 락 → cleanUp() 락 없음). 설정 app.retention.*(interval PT1H, refresh-token-retention P7D, otp-retention P1D, batch-size 1000, max-items 표마다 50000, max-duration PT10M ≤ 20분, 기동 시 검증). 표마다 id 순 LIMIT 배치 DELETE(긴 잠금 회피), 실행당 예산.
   - refresh_tokens: 폐기(revoked_at) 또는 만료(expires_at) 뒤 7일이 지난 행만. 활성 토큰(폐기 안 됨·만료 안 됨)은 절대 지우지 않는다(B1). replaced_by FK 는 id 오름차순 배치라 이전 토큰이 같은 배치에 먼저 들어간다. 한계: 7일 지난 로테이션 토큰 재사용은 family 폐기 없이 401(문서화).
   - otp_challenges: expires_at + otp-retention 이 지났고 created_at 이 OtpRateLimiter 가장 긴 창(max(24h, 1h, phone-cooldown)) 밖인 행만 → 지워도 발송 한도가 풀리지 않는다(B1).
   - post_views: viewed_at 이 중복 집계 창(PostViewService.WINDOW 30분) 밖인 행. 다시 보면 어차피 새로 센다 → 결과가 같다.
   - tags: post_tags 참조 없는 태그. 1문장 SELECT ... FOR UPDATE SKIP LOCKED 로 잠그고 2문장 NOT EXISTS 재확인 DELETE. 글 저장 쪽(TagService.resolve)은 upsert 뒤 태그 행을 FOR KEY SHARE 로 잠그고, 정리가 지워 빠진 이름은 다시 upsert(재시도). 경쟁 테스트는 RowLock + pg_stat_activity 로 결정적으로.
   - AC#5 rate_limit_events: occurred_at 이 설정된 app.rate-limit.* window 최댓값(RateLimitProperties.longestWindow, 실행 때 계산)보다 오래된 행만(B1).
   - V16 마이그레이션: 정리용 인덱스(refresh_tokens replaced_by·expires_at·revoked_at, otp_challenges created_at).
2. AC#2 BE-28: PostController.get 이 조회수 집계(PostViewService.record, 자기 트랜잭션·읽기 트랜잭션 밖)의 예외를 잡아 WARN 로그(예외 클래스·글 id)만 남기고 상세를 200 으로 돌려준다. 바깥 트랜잭션이 없어 rollback-only 문제 없음. 테스트: FailingInsert 로 post_views INSERT 실패 주입.
3. AC#3 BE-38: 정지 커밋 뒤 세션 삭제가 실패하면 WARN 로그 + 응답 warnings: ["session_revoke_failed"](정지 응답에는 항상 warnings 배열, 평소 []). 정지는 커밋된 채 유지. 테스트: SPRING_SESSION DELETE 실패 트리거. 문서: 남은 세션은 CurrentUserFilter·SuspendedUserWriteFilter 가 요청마다 status 를 봐서 쓰기 불가(TASK-24).
4. AC#4 BE-40: sortOrder 0..10000(@Min/@Max, 생성·수정) → 400 validation fields.sortOrder. 생략 시 맨 뒤 = min(최대 + 1, 10000)(long 계산, 범위 밖 기존 값이 있어도 오버플로 없음).
5. 문서: docs/api.md, README, example.env, deploy/secrets.example.txt, application.properties 주석. 마지막에 make test.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
TASK-22 리뷰(M2, 2026-10-21): rate_limit_events 정리가 빠져 있어 AC #5로 추가. 정리 기준은 고정 1시간이 아니라 설정된 window 최댓값(B1과 같은 취지).

독립 리뷰(2026-10-23) 반영 결정(유저): M1 refresh_tokens는 로그인 묶음(family) 단위로 지운다 — 살아 있는 토큰이 없고 family가 끝난 시각(max(least(revoked_at, expires_at)))이 보존 기간(7일)보다 오래된 family만 통째로 삭제. 재사용 감지가 토큰 수명 내내 유지되고 replaced_by FK·인스턴스 시계 차이 문제도 사라짐. 대가: 계속 로테이션되는 장기 family는 끝날 때까지 행이 쌓임(문서화). L1 표마다 예외 격리. 프론트 후속(정지 warnings 표시, sortOrder 범위)은 TASK-33 AC로.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
정리 작업과 관리·조회 견고성을 넣었다.
- AC#1 BE-10: RetentionCleanup(ShedLock, 실행당 시간·건수 예산, 배치 DELETE, 표마다 실패 격리). refresh_tokens는 살아 있는 토큰이 없고 끝난 지 7일이 지난 family 단위로 삭제(재사용 감지 유지). otp_challenges는 만료+1일 그리고 OTP 한도 창 밖. post_views는 중복 집계 창(30분) 밖. 미사용 tags는 SKIP LOCKED 잠금 + 재확인 후 삭제, 글 저장은 FOR KEY SHARE로 경쟁 처리. B1: 활성 토큰·한도 창 안 기록은 지우지 않는다. 보존 기간은 env로 조정.
- AC#2 BE-28: 조회수 집계 실패는 WARN만 남기고 상세는 200.
- AC#3 BE-38: 정지 뒤 세션 삭제 실패는 WARN + 응답 warnings ["session_revoke_failed"], 정지는 유지(남은 세션은 TASK-24 필터로 쓰기 403).
- AC#4 BE-40: 카테고리 sortOrder 0~10000 검증, 기본값(최대+1)은 long 계산 후 고정.
- AC#5: rate_limit_events는 설정된 app.rate-limit.* 창의 최댓값보다 오래된 행만 삭제.
독립 리뷰 반영: refresh family 단위 삭제, 표마다 실패 격리, 옛 sortOrder 문서화.
검증: make test 통과(backend 872, frontend 162, repo 233, deploy 135, terraform 57).
후속: 관리자 화면 warnings 표시·sortOrder 범위는 TASK-33 AC. 장기 family 행 누적 상한은 미정.
<!-- SECTION:FINAL_SUMMARY:END -->
