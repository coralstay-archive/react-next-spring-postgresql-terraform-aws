---
id: TASK-11
title: '첨부: S3 presigned 이미지 업로드'
status: Done
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 19:18'
labels:
  - backend
  - infra
dependencies:
  - TASK-8
references:
  - decision-3
documentation:
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
이미지 첨부를 presigned URL로 구현한다.

## 비즈니스 규칙
- B1 (AC #1) image/png·jpeg·webp·gif, 5MB 이하만
- B2 (AC #2) 본인이 업로드한 첨부만 자기 글에 연결 가능
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 POST /api/attachments/presign: content-type·크기 검증 후 PUT URL 발급
- [x] #2 POST /api/attachments/{id}/confirm: HeadObject로 확인 후 글에 연결
- [x] #3 로컬은 MinIO 또는 LocalStack으로 동작
- [x] #4 CloudFront /uploads/* 서빙 + 미확정 객체 lifecycle 정리
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획
1. **AC#1 presign**: AWS SDK v2 S3(`s3` + `S3Presigner`, url-connection HTTP client). V8 `attachments(id, owner_id, post_id NULL, s3_key UNIQUE, content_type, size, status PENDING|CONFIRMED, created_at, confirmed_at)`.
   - `POST /api/attachments/presign` {contentType, size, filename?}: 허용 MIME(png, jpeg, webp, gif)과 5MB 이하만 받는다(B1). 키는 `uploads/{userId}/{uuid}.{ext}`, PUT URL은 5분 유효, Content-Type과 Content-Length를 서명에 포함한다. 응답은 {attachmentId, uploadUrl, headers, key}.
   - 설정: `app.uploads.bucket`, `region`, `endpoint`(로컬 MinIO), `public-base-path`(`/uploads/`).
2. **AC#2 confirm**: `POST /api/attachments/{id}/confirm` → HeadObject로 존재·크기·타입을 다시 검증하고 CONFIRMED로 바꾼다.
   - 글 연결: 글 작성·수정 요청에 `attachmentIds[]`를 추가한다. 본인 소유이고 CONFIRMED이며 미연결인 것만 연결할 수 있다(B2 → 403/400). 상세 응답에 `attachments[{id,url,contentType}]`를 추가한다.
   - 글을 삭제해도 객체는 lifecycle에 맡긴다.
3. **AC#3 로컬**: docker-compose에 MinIO + 버킷 생성 init 컨테이너를 추가한다. 테스트는 Testcontainers MinIO(또는 LocalStack)로 실제 presigned PUT → confirm 흐름을 검증한다.
4. **AC#4 서빙과 정리**: CloudFront `/uploads/*`와 S3 lifecycle은 TASK-16 Terraform에서 만든다. 이 태스크에서는 `infra/main/modules/uploads`(버킷, 퍼블릭 차단, CORS PUT 허용 origin=PUBLIC_BASE_URL, lifecycle: `uploads/` 중 미확정은 태그 기반 1일 만료)를 terraform test와 함께 작성한다.
   - 미확정 정리 방식: presign 때 객체 태그 `status=pending`을 강제하고, confirm 때 태그를 제거한다. lifecycle 규칙은 해당 태그 + 1일 만료.
   - 앱 측 고아 레코드 정리 스케줄러(PENDING 24시간 초과 삭제)도 둔다.
- 위험: presigned PUT에 태그 헤더(`x-amz-tagging`) 서명이 필요하다. 브라우저 CORS 허용 헤더에 포함해야 한다.

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
S3 presigned 이미지 첨부를 구현했다(V9 attachments). presign은 png·jpeg·webp·gif, 5MB 이하만(B1), 키 uploads/{userId}/{uuid}.{ext}, 5분 PUT에 Content-Type·Length·x-amz-tagging=status=pending을 서명. confirm은 소유자만, HeadObject로 크기·타입 검증(불일치 시 객체 삭제 후 400), 태그 제거 후 CONFIRMED. 글 작성·수정의 attachmentIds(최대 10)는 업로더 본인이고 글 작성자일 때만 신규 연결(B2). 상세에 attachments[]. 버킷 미설정 시 503 uploads_unavailable(기동 시 네트워크 호출 없음). 로컬: chainguard MinIO(digest 고정, Docker Hub minio 이미지 pull 불가), 서버 전역 CORS, make dev-storage. Testcontainers MinIO로 실제 presigned PUT 전체 흐름 테스트. Terraform modules/uploads(퍼블릭 차단, SSE, CORS, pending 태그 1일 만료 lifecycle, HTTPS 전용 + OAC 정책, app_policy_json 출력, mock 테스트 8개). PendingAttachmentCleanup이 매시간 24시간 넘은 PENDING을 정리. 검증: cleanTest 후 make test 통과(repo 63, terraform 10+8, backend 508, 스킵 0). 후속: TASK-16에서 UPLOADS_REGION을 us-east-1로 명시(코드 기본값은 ap-northeast-2, decision-1과 불일치), uploads 모듈·OAC·app_policy_json 연결. TASK-17 CI에서 cgr.dev 접근 필요. 미확인: 연결되지 않은 CONFIRMED 첨부와 삭제된 글의 객체는 정리되지 않음, presign 레이트리밋 없음.
<!-- SECTION:FINAL_SUMMARY:END -->
