---
id: TASK-26
title: '백엔드: 첨부 정합성'
status: In Progress
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 01:03'
labels:
  - backend
  - attachments
dependencies:
  - TASK-20
references:
  - decision-3
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-32, BE-33, BE-35, BE-36, BE-37.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #2) CONFIRMED 첨부는 항상 실제 객체가 있다
- B2 (AC #4) 삭제된 글의 이미지는 정책 기간 뒤 공개 URL로 열리지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 BE-32 confirm·head·태그 제거의 S3 오류는 503 storage_unavailable + 로그
- [ ] #2 BE-33 pendingTtl이 지난 confirm 거부, cleanup은 행 삭제 후 객체 삭제, markConfirmed 0행이면 객체 정리
- [ ] #3 BE-35 presign을 트랜잭션 밖에서, S3Client apiCallTimeout
- [ ] #4 BE-36 연결 해제되거나 글이 삭제된 첨부의 객체 정리(지연 삭제 정책)
- [ ] #5 BE-37 스케줄 작업 분산 락(ShedLock 등)
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. AC#1 BE-32 S3 오류 → 503 storage_unavailable
   - attachment.StorageUnavailableException(ApiException 503 storage_unavailable) 추가.
   - S3UploadStorage 의 presignPut·head·clearPendingTag·delete 를 감싸 SdkException(S3Exception 403·5xx, SdkClientException, ApiCall*TimeoutException)을 이 예외로 바꾸고 WARN 로그 1줄: op·key·HTTP 상태·AWS 오류 코드·requestId·예외 클래스 사슬(TASK-25 OAuth2Logs 방식). 예외 메시지·presigned URL·자격 증명은 남기지 않는다. head 의 404 는 지금처럼 empty.
   - 테스트: 테스트 전용 StorageProbe(@Primary 데코레이터, 공유 컨텍스트 유지)로 confirm 의 head·태그 제거만 고장 난 S3(잘못된 자격 증명 MinIO=403, 5xx 를 주는 로컬 HTTP 서버, 닫힌 포트=SdkClientException)로 돌린다 → 503 storage_unavailable, 행은 PENDING, 로그에 비밀값 없음.
2. AC#2 BE-33 만료 confirm 거부·정리 순서 (B1: CONFIRMED 첨부는 항상 실제 객체가 있다)
   - confirm: created_at + pending-ttl 이 지났으면 S3 호출 전에 410 attachment_expired(다시 presign). markConfirmed 에 created_at >= cutoff 조건 추가.
   - markConfirmed 가 0행이면 다시 읽어 CONFIRMED(동시 confirm)면 200, 아니면(정리로 행이 없거나 만료) 태그를 지운 객체를 지우고 410 attachment_expired.
   - PendingAttachmentCleanup: 행을 먼저 조건부 삭제(status=PENDING, created_at<cutoff, 1행씩)하고, 실제로 지운 행의 객체만 지운다(confirm 이 이긴 행은 객체를 건드리지 않음).
   - 경쟁 테스트: 태그 제거 직후 행 삭제(정리 흉내) → 객체 정리 + 410, 동시 confirm 은 200 유지, 정리가 이긴 confirm 경로와 confirm 이 이긴 정리 경로 둘 다 B1 확인.
3. AC#3 BE-35 presign 트랜잭션 밖 + S3 타임아웃
   - AttachmentService.presign: 검증 → 키 생성 → 서명(트랜잭션 없음) → TransactionTemplate 안에서 레이트리밋·PENDING 개수·행 저장(사용자 잠금 유지). 서명 실패는 503 storage_unavailable 이고 행·한도 소모 없음.
   - UploadProperties 에 api-call-timeout(기본 5s)·api-call-attempt-timeout(기본 2s), 양수·시도별<=전체 검증(어기면 기동 실패). UploadConfiguration.s3ClientBuilder 가 overrideConfiguration·UrlConnection 연결/소켓 타임아웃에 반영(TASK-25 SMS 방식). env UPLOADS_API_CALL_TIMEOUT·UPLOADS_API_CALL_ATTEMPT_TIMEOUT.
   - 테스트: presign 중 서명 시점에 트랜잭션이 없음(StorageProbe 가 기록), 기본값·설정·검증, 응답 없는 로컬 서버에 head 가 상한 안에 503.
4. AC#4 BE-36 연결 해제·삭제 글 첨부의 지연 삭제 (B2: 삭제된 글의 이미지는 정책 기간 뒤 공개 URL 로 열리지 않는다)
   - V13: attachments.detached_at(연결 해제 시각) + 정리용 부분 인덱스, posts(deleted_at) 부분 인덱스. detach() 가 detached_at 설정, attachTo() 가 지운다.
   - 새 스케줄 작업 OrphanAttachmentCleanup(app.uploads.cleanup-interval): 기준 cutoff = now - app.uploads.orphan-grace-period(기본 P7D, env UPLOADS_ORPHAN_GRACE_PERIOD). 대상 = (CONFIRMED, post_id NULL, coalesce(detached_at, confirmed_at) < cutoff) 또는 (글의 deleted_at < cutoff). 행을 조건 재확인하며 먼저 지우고(B1) 그다음 객체 삭제. 다시 붙이기(FOR UPDATE 잠금)·복구와 경쟁해도 조건부 DELETE 라 안전.
   - 유예 기간 안에 관리자가 복구하면 행·객체를 건드리지 않아 이미지 유지. 숨김 글은 대상 아님.
   - 공개 URL 은 CloudFront /uploads/* → S3(OAC, CachingOptimized 기본 TTL 24h). 객체 삭제 뒤 엣지 캐시가 최대 24시간 더 줄 수 있음 → 실제 열리지 않게 되는 시점 = 유예 7일 + 정리 주기 1시간 + 캐시 최대 24시간. api.md·README 에 기록.
5. AC#5 BE-37 ShedLock
   - shedlock-spring·shedlock-provider-jdbc-template(7.x), V14__shedlock.sql, SchedulingConfiguration 에 @EnableSchedulerLock + JdbcTemplateLockProvider(usingDbTime).
   - 모든 @Scheduled(PendingAttachmentCleanup, OrphanAttachmentCleanup)에 @SchedulerLock(이름별, lockAtMostFor PT30M, lockAtLeastFor app.scheduling.lock-at-least-for 기본 PT10S). Spring Session JDBC 만료 세션 정리는 라이브러리 스케줄러라 대상 아님(멱등 DELETE) — 문서에 적는다.
   - 결정적 테스트: 첫 실행을 객체 삭제 훅에서 멈춘 채 둘째 실행이 건너뛰는지(새 만료 행이 남음), 풀린 뒤 다시 실행되는지.
6. 문서: docs/api.md(오류 표 410 attachment_expired·503 storage_unavailable, 정리 정책·CloudFront 캐시), README·example.env·deploy/secrets.example.txt(UPLOADS_ORPHAN_GRACE_PERIOD·UPLOADS_API_CALL_*). 마지막에 make test.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
독립 리뷰(2026-10-23) 반영 결정(유저): M2 확정 뒤 같은 presigned URL 재PUT으로 pending 태그가 다시 붙어 CONFIRMED 객체가 lifecycle로 지워지던 B1 위반 → presign에 If-None-Match: * 서명(덮어쓰기 금지), 프론트 업로드가 헤더를 보냄. 앱 IAM에 uploads/ 접두어로 제한한 s3:ListBucket 추가(없는 키 HEAD가 404가 되어 400 attachment_not_uploaded). CloudFront /uploads/* 캐시 24시간은 수용(문서화). 그 밖에 고칠 것: M1 삭제 글 FOR SHARE 서브쿼리를 상관 EXISTS로(1행만 잠금), L1 V13 이전 뗀 첨부 detached_at 백필, L2 태그 없는 고아 WARN 문구, L3 정리 실행당 건수·시간 예산, L4 다시 붙이기·복구 동시성 테스트, L5 7일 넘게 열어 둔 편집기 이미지 소실 문서화.
<!-- SECTION:NOTES:END -->
