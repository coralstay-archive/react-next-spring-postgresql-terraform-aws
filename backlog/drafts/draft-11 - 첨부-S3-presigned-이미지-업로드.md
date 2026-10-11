---
id: DRAFT-11
title: '첨부: S3 presigned 이미지 업로드'
status: Draft
assignee: []
created_date: '2026-10-11 13:03'
labels:
  - backend
  - infra
dependencies: []
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
- [ ] #1 POST /api/attachments/presign: content-type·크기 검증 후 PUT URL 발급
- [ ] #2 POST /api/attachments/{id}/confirm: HeadObject로 확인 후 글에 연결
- [ ] #3 로컬은 MinIO 또는 LocalStack으로 동작
- [ ] #4 CloudFront /uploads/* 서빙 + 미확정 객체 lifecycle 정리
<!-- AC:END -->
