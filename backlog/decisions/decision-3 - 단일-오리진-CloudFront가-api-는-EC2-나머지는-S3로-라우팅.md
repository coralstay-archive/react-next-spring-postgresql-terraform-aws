---
id: decision-3
title: '단일 오리진: CloudFront가 /api/*는 EC2, 나머지는 S3로 라우팅'
date: '2026-10-11 13:01'
status: accepted
---
## Context

HTTPS 정적 페이지가 HTTP API를 호출하면 mixed content가 되고 CORS도 필요하다.

## Decision

CloudFront 하나에 /api/*(EC2 origin, 캐시 비활성화, 쿠키·헤더·쿼리 전달)와 기본 동작(S3 OAC)을 둔다. /uploads/*도 같은 배포에서 서빙한다.

## Consequences

같은 오리진이라 CORS가 필요 없고 쿠키 세션이 동작한다. CloudFront→EC2 구간은 HTTP이므로 SG를 CloudFront prefix list로 제한한다.
