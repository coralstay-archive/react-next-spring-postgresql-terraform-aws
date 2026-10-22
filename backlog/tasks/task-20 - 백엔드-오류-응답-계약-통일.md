---
id: TASK-20
title: '백엔드: 오류 응답 계약 통일'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-12 06:13'
labels:
  - backend
  - errors
dependencies:
  - TASK-19
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: BE-1, BE-2, BE-5, BE-6, BE-41.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 모든 오류 응답 본문은 status·error·message를 가지며 스택트레이스나 내부 메시지를 노출하지 않는다
- B2 (AC #2) 사용자 입력 때문에 500이 나지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 BE-1 공통 처리기: 405·415·406·404·500을 모두 {status,error,message} 모양과 snake_case 코드로(500은 internal_error + 서버 로그)
- [x] #2 BE-2 문자열 입력의 NUL·제어문자를 400 validation으로 거부, 남은 DataIntegrityViolation은 409/400으로 매핑
- [x] #3 BE-5 알 수 없는 JSON 필드는 400 거부(또는 정책 문서화), 본문 타입 오류에 fields 포함
- [x] #4 BE-6 파라미터 오류 코드 통일(validation), enum 대소문자 무시, docs/api.md 갱신
- [x] #5 BE-41 카테고리 저장 시 slug 유니크 위반만 slug_taken, 나머지는 원래 매핑
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (Plan-Review-Go)
1. AC#1 BE-1 공통 처리기: ApiExceptionHandler가 ResponseEntityExceptionHandler를 상속하거나 동등한 처리기를 추가한다. 405 method_not_allowed, 415 unsupported_media_type, 406 not_acceptable, 404 not_found(NoResourceFound, 인증된 사용자), 400 missing_parameter(MissingServletRequestParameter), 그 밖의 예외는 500 internal_error(서버 ERROR 로그, 응답에 내부 메시지 없음). /error 경로(BasicErrorController)도 같은 모양이 되도록 ErrorController 또는 ErrorAttributes를 맞춘다(필터 단계 예외 대비).
2. AC#2 BE-2: 문자열 입력의 NUL(\u0000)과 금지 제어문자(탭·개행 제외)를 400 validation으로 거부한다. 공통 Jackson deserializer, @NoControlChars 제약, 쿼리 파라미터 q·tag 검사 중 하나를 쓴다. 남은 DataIntegrityViolation은 SQLState로 23505 → 409 conflict, 22xxx → 400 validation으로 매핑한다.
3. AC#3 BE-5: spring.jackson.deserialization.fail-on-unknown-properties=true. 알 수 없는 필드는 400 validation이고 fields에 그 이름을 담는다. 타입 오류(InvalidFormatException, MismatchedInputException)도 fields에 경로를 담는다. 기존 클라이언트(프론트)가 보내는 필드를 모두 확인해 회귀가 없게 한다.
4. AC#4 BE-6: MethodArgumentTypeMismatch를 400 validation(fields 포함)으로 통일, enum 파라미터는 대소문자 무시 컨버터. docs/api.md 갱신.
5. AC#5 BE-41: AdminCategoryService.save는 categories_slug 유니크 제약 이름일 때만 slug_taken, 나머지는 2번 매핑을 따른다.
- 테스트: 각 AC마다 red를 먼저 실행해 실패를 확인하고(커밋 훅이 실패 커밋을 막으므로 로그로 기록), green 커밋, 필요하면 refactor 커밋. 표식은 TASK-20#n과 #B1, #B2. 오류 응답 모양 검사는 계약 테스트 하나로 모든 경로(405, 415, 404, 500, 400 계열)를 돈다.
- 프론트 영향: 오류 코드가 bad_request에서 validation으로 바뀌는 부분을 frontend/src/lib/errors.ts에서 확인한다(코드 추가만, 기존 매핑 유지).
- 리뷰: 구현 후 독립 서브에이전트가 리뷰하고, 지적을 refactor 커밋으로 반영한다.
<!-- SECTION:PLAN:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
오류 응답 계약을 통일했다. (1) ApiExceptionHandler가 ResponseEntityExceptionHandler를 상속하고, 명시 코드 표(400 bad_request·validation, 401, 403, 404 not_found, 405 method_not_allowed, 406, 409 conflict, 413 payload_too_large, 415, 429 rate_limited, 500 internal_error, 503, 나머지 http_<status>)를 쓴다. 미처리 예외는 500 internal_error와 ERROR 로그. 원인 사슬의 ApiException과 보안 예외는 원래 처리로 보낸다. ApiErrorController가 /error(필터·컨테이너 오류)도 같은 JSON으로 만든다. (2) MVC 요청 본문의 제어문자(탭·LF·CR 제외)와 쿼리 파라미터를 400 validation + fields로 거부. 남은 무결성 위반은 SQLState로 매핑(23505·23503 → 409, 23502·23514 → 500, 22xxx → 400). 로그에는 SQLState와 제약 이름만 남긴다(개인정보 제거). (3) 알 수 없는 JSON 필드와 타입 오류는 400 validation + fields 경로. 엄격 규칙은 MVC 서버 변환기에만 적용하고 전역 JsonMapper는 너그럽게 유지(RestClient 등 외부 응답 보호, 테스트로 확인). (4) 파라미터 오류 코드를 validation + fields{이름}로 통일(page·size·q·sort·limit·enum·token 등), enum은 정확 일치 후 대소문자 무시, 메서드 파라미터 검증도 같은 모양. (5) 카테고리 저장은 categories_slug_uk 위반만 slug_taken. 진행: Plan → AC마다 Red(실패 확인을 커밋 메시지에 기록) → Green 커밋 → 독립 리뷰(High 없음, Medium 2와 Low 7) → fix·refactor 커밋 5개로 전부 반영. 검증: cleanTest 후 REQUIRE_LINTERS=1 make test 통과(backend 614, 스킵 0, frontend 160). 이월: Hibernate SqlExceptionHelper 로그의 개인정보(TASK-21 노트), 프론트 새 코드 매핑(TASK-28).
<!-- SECTION:FINAL_SUMMARY:END -->
