---
id: TASK-18
title: 첫 배포 + 외부 설정 + 문서
status: To Do
assignee: []
created_date: '2026-10-11 13:03'
updated_date: '2026-10-11 18:19'
labels:
  - infra
  - docs
dependencies:
  - TASK-17
  - TASK-14
  - TASK-15
  - TASK-5
  - TASK-6
  - TASK-7
references:
  - decision-9
  - decision-6
  - decision-7
documentation:
  - backlog/docs/doc-1-architecture-plan.md
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

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (실제 AWS 비용·외부 콘솔 작업이 생기므로 단계마다 유저 확인)
1. **AC#1 첫 배포**: bootstrap apply → main apply(prod.tfvars) → put-secrets → make deploy → `curl https://<cf>/api/actuator/health` 200 확인.
2. **AC#2 OAuth 콘솔**: Google, GitHub, Naver, Kakao 콘솔에 `https://<cf>/api/login/oauth2/code/{p}`를 등록하는 체크리스트를 문서화하고, 유저가 등록한 뒤 각 로그인을 스모크한다. Cognito는 Terraform이 콜백을 자동 등록한다. cloudfront.net 등록이 거부되는 제공자가 있으면 도메인 구입과 dns_cert 활성화로 전환한다.
3. **AC#3 SMS**: End User Messaging 샌드박스 해제, 한국 발신 등록, 지출 한도 절차를 문서화하고 실제 휴대폰 로그인을 스모크한다.
4. **AC#4 문서**: README(로컬 실행, 배포, 시크릿 목록, 비용, `terraform destroy` 정리 순서). `diagrams/*.dot`(아키텍처, 인증 흐름)을 `scripts/build-diagrams.py`로 SVG로 렌더한다. 센티넬 색은 CSS 변수로 치환한다(coralstay 방식).
5. **AC#5 E2E**: Playwright 스모크(`frontend/e2e`). 로컬 compose 대상으로 가입 → 글·태그·이미지 → 좋아요·댓글 → 타 계정 403 → 관리자 정지 → 휴대폰 OTP(console 코드를 테스트용 엔드포인트 없이 로그에서 읽거나, e2e 프로필에서 memory inbox 조회 API를 테스트 전용으로 켬). 배포 환경 대상 스모크는 읽기 위주로 한다.
- 남은 후속 과제를 별도 태스크로 제안한다: refresh_tokens·otp_challenges·post_views 정리 잡, 로그인 레이트리밋, 만료 Bearer로 refresh 호출 시 401 문제, prod health 노출 점검.

### 공통 규칙
- 시작: `backlog task view <ID> --plain` → `task/<ID>` 브랜치 → In Progress. AC마다 테스트(`<ID>#n`, `<ID>#Bk`) → 구현 → 커밋(`make test` 통과).
- 구현은 서브에이전트가 메인 작업 디렉토리에서 한다(훅이 브랜치를 확인하므로 워크트리는 쓰지 않음). 부모가 `cleanTest` 후 `make test`로 다시 검증하고 Done 처리한다.
- `.env*` 토큰은 Bash에 쓰지 않는다(보호 훅). 실제 AWS에는 apply나 호출을 하지 않는다(TASK-18 제외, 유저 확인 후).
<!-- SECTION:PLAN:END -->
