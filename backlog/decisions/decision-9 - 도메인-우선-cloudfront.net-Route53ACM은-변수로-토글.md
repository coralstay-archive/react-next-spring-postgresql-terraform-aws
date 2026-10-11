---
id: decision-9
title: '도메인: 우선 cloudfront.net, Route53+ACM은 변수로 토글'
date: '2026-10-11 13:01'
status: accepted
---
## Context

아직 도메인이 없다.

## Decision

*.cloudfront.net 기본 인증서로 시작한다. Route53 + ACM(us-east-1)은 domain_name 변수로 켜는 모듈로 미리 구현한다.

## Consequences

도메인을 바꾸면 모든 OAuth 제공자의 콜백 URL을 바꿔야 한다. 일부 제공자가 cloudfront.net 등록을 제약하면 도메인 구입을 앞당긴다.
