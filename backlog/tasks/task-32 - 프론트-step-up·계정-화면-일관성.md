---
id: TASK-32
title: '프론트: step-up·계정 화면 일관성'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 14:43'
labels:
  - frontend
  - auth
dependencies:
  - TASK-29
references:
  - decision-7
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-16, FE-18, FE-31.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.

## 비즈니스 규칙
- B1 (AC #1) 로그인 수단 해제는 사용자 확인 없이 일어나지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 FE-16 로그인 수단 해제 확인 다이얼로그(LOCAL이면 비밀번호 삭제 경고), 제한 세션에서 비활성
- [x] #2 FE-18 OTP 5회 실패 시 재발송 안내
- [x] #3 FE-31 제한 세션이면 작성 컨트롤을 미리 비활성 또는 안내, step-up 오류 표시를 단일 컴포넌트로
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. AC#1 FE-16 (B1): 로그인 수단 '해제'는 바로 DELETE 하지 않고 확인 다이얼로그(관리자 화면의 ConfirmDialog alertdialog 재사용)를 연다. LOCAL 이면 '저장된 비밀번호도 함께 삭제되어 되돌릴 수 없고 이메일·비밀번호로 로그인할 수 없다'는 경고를 덧붙인다. 확인해야만 DELETE, 취소·Esc 는 요청 없음(B1 테스트). 오류(409 last_identity 등)는 다이얼로그 안에 보이고, 성공하면 닫고 알림. 제한 세션(me.stepUpRequired)이면 해제 버튼을 비활성으로 두고 '다시 인증한 뒤에 해제할 수 있습니다' 설명을 카드에 붙인다(aria-describedby).
2. AC#2 FE-18: 서버는 5회 실패 뒤에도 같은 401 invalid_otp 만 주므로 폼이 지금 챌린지의 invalid_otp 횟수를 센다(OTP_MAX_ATTEMPTS=5, 백엔드 app.otp.max-attempts 기본값). 5회째면 챌린지를 잠긴 것으로 보고 인증번호 칸을 비우고 입력·확인을 막으며 '5번 틀려 더 이상 쓸 수 없습니다. 인증번호를 다시 받으세요' 안내를 보인다. 새 인증번호를 받으면 횟수·잠금을 초기화. 다시 받기 쿨다운은 서버 429 Retry-After 카운트다운(기존)을 그대로 쓰고, 잠금 안내는 429 오류와 함께 남긴다. 인증번호 외 오류(503·409 등)는 세지 않는다.
3. AC#3 FE-31: step-up 안내를 components/step-up-notice.tsx 의 StepUpNotice 하나로 모은다(제목·표준 문구·/account/#step-up '다시 인증하기' 링크). ApiErrorAlert 는 step_up_required 면 StepUpNotice 를, 레이아웃 StepUpBanner 도 StepUpNotice 를 쓴다. 제한 세션이면 글 편집기는 입력(초안 저장)은 두고 등록·저장 버튼과 이미지 올리기를 미리 막고 StepUpNotice 를 보이며, 댓글 폼도 입력은 두고 등록 버튼을 막고 StepUpNotice 를 보인다. 내 계정의 해제·연결은 1번·기존처럼 막혀 있다.
4. docs/frontend.md 에 해제 확인·OTP 잠금·제한 세션 작성 컨트롤·StepUpNotice 를 적는다. /account/ 의 if (!me) return null(만료 뒤 내용 사라짐)은 AC 에 필요하지 않으면 범위 밖으로 남긴다.
5. make test·tsc·eslint 로 확인.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
독립 리뷰(2026-10-23) 반영 결정(유저): 제한 세션에서 글·댓글 삭제는 확인 창 전에 본인 확인 안내(좋아요·관리자 작업은 눌렀을 때 403 안내 유지). 화면 안 안내(편집기·댓글)는 role=status 간략형으로 — 상단 배너만 alert. OTP 재발송 대기는 서버 429 Retry-After 기준 유지. 고칠 것: frontend.md OTP '한계' 문장 정정(실제 어긋남은 서버가 실패를 커밋했는데 클라이언트가 네트워크 오류·타임아웃을 받은 경우 — 잠금 안내가 늦게 나올 뿐), 잠길 때 포커스를 '인증번호 다시 받기' 버튼으로.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
step-up·계정 화면을 일관되게 정리했다.
- AC#1 FE-16: 로그인 수단 해제는 확인 다이얼로그를 거친다(LOCAL이면 비밀번호 삭제 경고, 409 last_identity는 다이얼로그 안에 표시). 제한 세션에서는 해제 버튼을 미리 막고 이유를 보인다. B1: 취소·Esc면 요청 0건.
- AC#2 FE-18: 현재 챌린지의 invalid_otp를 세어 5번째에 입력을 비우고 막은 뒤 다시 받기 안내, 포커스는 '인증번호 다시 받기'로. 재발송 대기는 서버 429 Retry-After 기준(유저 결정).
- AC#3 FE-31: 제한 세션이면 글 등록·이미지 올리기·댓글 등록을 미리 막고(입력은 초안으로 유지), 글·댓글 삭제는 확인 창 전에 안내(유저 결정). step-up 안내는 StepUpNotice 하나로 — 상단 배너는 alert, 화면 안은 조용한 status 간략형(유저 결정).
검증: make test 통과(frontend 523, repo 233, deploy 135, terraform 57, backend), tsc·eslint.
범위 밖: /account/는 세션 만료 시 내용(해제 다이얼로그 포함)이 사라짐, ConfirmDialog가 components/admin 아래에 있음, 마지막 수단 해제 버튼은 서버 409로 안내.
<!-- SECTION:FINAL_SUMMARY:END -->
