---
id: decision-10
title: '프론트: Next static export + Tailwind/shadcn + 쿼리 파라미터 라우팅'
date: '2026-10-11 13:02'
status: accepted
---
## Context

Next를 S3에서 정적으로 서빙한다.

## Decision

output:'export', trailingSlash, CloudFront Function으로 index를 리라이트한다. 동적 라우트 대신 /posts/view?id= 같은 쿼리 파라미터 라우팅을 쓴다. Tailwind + shadcn/ui + TanStack Query를 쓴다.

## Consequences

SSR과 middleware를 쓸 수 없다. 인증 가드는 클라이언트(/api/auth/me)에서 하고 권한 판단은 서버가 한다.
