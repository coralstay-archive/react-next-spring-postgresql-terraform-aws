---
id: DRAFT-24
title: npm audit 남은 취약점 정리(shadcn·zod·undici·body-parser·fast-uri·ip-address)
status: Draft
assignee: []
created_date: '2026-10-24 01:26'
labels: []
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
TASK-35 리뷰(2026-10-24)에서 next 16.4.3 업그레이드 뒤에도 npm audit에 high 5건·moderate 2건이 남음: zod <4.6.6, shadcn/@shadcn/registry(zod 경유), undici, body-parser(high), fast-uri, ip-address(moderate). shadcn은 CLI인데 dependencies에 있어 devDependencies로 옮기는 것도 검토. 각 패키지가 런타임 번들에 들어가는지 확인 후 업그레이드·이동, 전체 테스트·빌드·E2E 확인.
<!-- SECTION:DESCRIPTION:END -->
