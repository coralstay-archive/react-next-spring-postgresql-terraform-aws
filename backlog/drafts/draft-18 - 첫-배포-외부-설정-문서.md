---
id: DRAFT-18
title: 첫 배포 + 외부 설정 + 문서
status: Draft
assignee: []
created_date: '2026-10-11 13:03'
labels:
  - infra
  - docs
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
실제 배포하고 운영 문서를 만든다.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 apply 후 https://<cf>/api/actuator/health 200
- [ ] #2 OAuth 콜백 URL(Google, GitHub, Naver, Kakao, Cognito) 등록 체크리스트
- [ ] #3 SMS 샌드박스 해제/발신 등록 절차
- [ ] #4 README(로컬 실행, 배포, 시크릿 목록, 비용, destroy), diagrams/*.dot → SVG
- [ ] #5 Playwright 스모크 시나리오 통과
<!-- AC:END -->
