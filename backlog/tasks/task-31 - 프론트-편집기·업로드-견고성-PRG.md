---
id: TASK-31
title: '프론트: 편집기·업로드 견고성 + PRG'
status: Done
assignee: []
created_date: '2026-10-12 04:09'
updated_date: '2026-10-23 11:44'
labels:
  - frontend
  - board
dependencies:
  - TASK-29
references:
  - decision-10
documentation:
  - backlog/docs/doc-2 - 예외-상황-전수-목록.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
포함: FE-19, FE-21, FE-24, FE-25, FE-26, FE-27, FE-28, FE-29, FE-30. TASK-18 이슈 중 '작성 직후 조회수 0' 포함.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor 커밋을 나눈다.
- 근거와 위치: backlog/docs/doc-2(예외 상황 전수 목록)의 ID.
- PRG: 폼 제출 성공 시 결과 페이지로 replace 이동, 새로고침·뒤로가기 재제출과 이중 제출을 차단한다.

## 비즈니스 규칙
- B1 (AC #2) 한 번의 저장 의도는 글 하나만 만든다
- B2 (AC #7) 저장하지 않은 입력이 경고 없이 버려지지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 FE-19 작성 후 상세는 캐시 대신 재조회(조회수 반영)
- [x] #2 FE-29·FE-21 성공 후 버튼 비활성 유지 + replace 이동(PRG), 404·403 시 상세 무효화
- [x] #3 FE-24 표시되지 않는 서버 필드 오류 키 매핑(categorySlug, attachmentIds*), 미매핑 키가 있으면 상단 알림
- [x] #4 FE-25 첨부 연결 오류 시 문제 첨부를 표시하고 그것만 빼고 다시 저장
- [x] #5 FE-26 업로드 timeout·취소·삭제, 언마운트 시 abort
- [x] #6 FE-27 confirm만 다시 시도
- [x] #7 FE-28 작성·업로드 중 이탈 경고, 히스토리가 없을 때 취소는 /로
- [x] #8 FE-30 카테고리 목록 오류 표시, 현재 카테고리를 선택지에 유지
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Plan-Review-Go: 아래 계획 → AC마다 red(실패 테스트, 결과를 커밋 메시지에) → green → 필요 시 refactor. 구현 뒤 부모가 독립 리뷰.
1. FE-19: 작성 성공 때 생성 응답으로 상세 캐시를 채우지 않는다(상세 화면이 GET 으로 다시 받아 조회수 반영). 수정 성공은 응답으로 채우되 stale 표시(refetchType none) → 상세가 열리며 다시 받는다.
2. FE-29·FE-21(PRG, B1): 제출을 useRef 로 잠가 연속 클릭·Enter 이중 제출을 막고, 성공 뒤에는 잠금·버튼 비활성을 유지한 채 router.replace 로 상세 이동(편집기가 히스토리에서 빠져 뒤로가기·새로고침 재제출 없음). 실패하면 잠금 해제. 초안은 성공 때 지운다(TASK-29). 수정 저장·삭제가 404·403 이면 상세 캐시 무효화. 삭제 성공 뒤 버튼 비활성 유지 + replace('/').
3. FE-24: 서버 fields 키 매핑(title·content, categorySlug→카테고리, tags·tags[n]→태그, attachmentIds·attachmentIds[n]→첨부 칸). 매핑되지 않은 키가 남으면 폼 위 알림에 그 메시지를 보인다.
4. FE-25: 저장 때 첨부 연결 오류(unknown_attachment·attachment_not_confirmed·attachment_in_use·attachment_expired·attachment_not_found·attachment_forbidden)면 보낸 attachmentIds 중 문제 첨부를 찾아(fields 의 attachmentIds[n] → 메시지 끝 id) 그 항목에 표시하고 "문제 이미지 빼고 다시 저장" 버튼 제공. 못 찾으면 첨부 칸에 안내.
5. FE-26: uploadImage(file, {onProgress, signal}) — XHR PUT(fetch 는 업로드 진행 이벤트가 없음). 진행 이벤트가 30초 없으면 upload_timeout, 전체 상한 5분(presigned URL 만료), signal 이면 xhr.abort + AbortError. 편집기: 업로드 중 항목 '취소', 실패 항목 '빼기', 언마운트 때 진행·대기 업로드 모두 abort.
6. FE-27: PUT 성공 뒤 confirm 이 network·timeout·5xx·429 로 실패하면 attachmentId 를 담은 오류 → 항목에 "확인 다시 시도"(confirm 만 다시, 재업로드 없음 — If-None-Match: * 라 같은 URL 재PUT 은 412). 그 밖의 confirm 실패(410·404·400)는 다시 올리게 안내(새 presign).
7. FE-28(B2): beforeunload — 입력이 원래와 다르거나 업로드·저장 중이면 경고(탭을 닫으면 sessionStorage 초안도 사라짐). 앱 안 링크(document capture click) — 업로드·저장 중이면 confirm 경고(텍스트는 초안이 남아 복원되므로 경고 없음, 단 초안 저장이 실패했으면 경고). 취소 버튼: 같은 규칙 + 히스토리가 없으면 replace('/'). 저장 성공 뒤에는 경고하지 않는다. 결정과 한계(브라우저 뒤로가기는 막을 수 없음)를 docs/frontend.md 에 적는다.
8. FE-30: 카테고리 목록 실패를 칸 아래 알림 + 다시 시도로 보이고, 현재 값(글의 카테고리·초안 slug)이 목록에 없어도 선택지로 둬 표시와 전송값을 맞춘다.
9. docs/frontend.md 갱신, tsc·eslint·make test.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
착수 결정(2026-10-23, 유저): S3 업로드는 '진행이 30초 동안 멈추면' 포기(진행 이벤트 기준 stall timeout), 전체 상한은 presigned URL 만료(5분). TASK-26: presigned PUT은 If-None-Match: * 서명이라 같은 URL 재PUT은 412 — 업로드 재시도는 presign부터 새로, AC#6은 confirm만 다시 시도.

독립 리뷰(2026-10-23) 반영 결정(유저): 글자만 바뀐 상태의 앱 안 이동은 묻지 않음(초안 복원) 유지, 브라우저 뒤로가기·코드 router.push는 막지 않는 한계로 수용(문서화). 백엔드 첨부 연결 오류(unknown_attachment·attachment_expired·attachment_forbidden 등)는 fields {"attachmentIds[i]": 문구}로 문제 첨부를 알려 준다(이 태스크에서, 백엔드 테스트 포함). 고칠 것: 저장 중 이탈 확인 뒤 저장 성공 시 router.replace로 끌려가던 문제(이동은 mutate 호출 단계 onSuccess로), 같은 첨부 오류 중복 표시, 외부에서 들어온 뒤 취소 시 이중 확인은 문서화.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
편집기·업로드를 견고하게 하고 PRG를 넣었다(TASK-18 '작성 직후 조회수 0' 해결).
- AC#1 FE-19: 작성 성공 때 상세 캐시를 채우지 않아 상세가 다시 받은 조회수를 보인다. 수정 성공은 채우되 stale.
- AC#2 FE-29·FE-21: ref 잠금으로 이중 제출 차단(Enter 연타로 글 3개 생기던 버그), 성공하면 버튼 비활성 유지 + replace 이동. 이동은 mutate 호출 단계라 저장 중 떠난 뒤 늦은 성공이 사용자를 끌고 가지 않음. 404·403은 상세 무효화. B1.
- AC#3 FE-24: 서버 필드 오류 키(categorySlug, attachmentIds[n] 등)를 칸에 붙이고 남는 키는 상단 알림.
- AC#4 FE-25: 백엔드가 첨부 연결 오류 4종에 fields {"attachmentIds[i]"}를 담고(forbidden은 id 미노출), 프론트는 문제 첨부에 표시 + '문제 이미지 빼고 다시 저장'.
- AC#5 FE-26: XHR PUT, 진행 정체 30초·전체 5분 제한(유저 결정), 업로드 취소·빼기, 언마운트 시 abort.
- AC#6 FE-27: confirm이 0·429·5xx로 실패하면 confirm만 다시 시도, 410·404·400은 다시 올리기 안내.
- AC#7 FE-28: beforeunload + 앱 안 링크 가로채기(업로드·저장 중·초안 저장 실패 때만 확인, 글자만이면 초안 복원으로 묻지 않음 — 유저 결정). 히스토리 없으면 취소는 /로. B2. 브라우저 뒤로가기·코드 이동은 막지 않는 한계(유저 수용).
- AC#8 FE-30: 카테고리 목록 오류 표시, 현재 카테고리를 선택지에 유지.
검증: make test 통과(frontend 506, backend 876, repo 233, deploy 135, terraform 57), tsc·eslint, make e2e 8/8(리뷰에서).
범위 밖: 연달아 파일 선택 시 업로드 루프 병행(한도는 지켜짐).
<!-- SECTION:FINAL_SUMMARY:END -->
