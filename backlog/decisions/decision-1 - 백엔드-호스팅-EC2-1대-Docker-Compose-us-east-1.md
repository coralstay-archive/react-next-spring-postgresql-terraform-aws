---
id: decision-1
title: '백엔드 호스팅: EC2 1대 + Docker Compose (us-east-1)'
date: '2026-10-11 13:01'
status: accepted
---
## Context

가장 간단하고 저렴한 백엔드 호스팅이 필요하다. 후보는 ECS Fargate+ALB, EC2+Compose, App Runner였다.

## Decision

EC2 t4g.small(ARM, 2GB) 1대에서 Docker Compose로 api와 postgres를 실행한다. 리전은 us-east-1. SSH 대신 SSM으로 접근하고 배포한다.

## Consequences

ALB가 없어 비용이 낮다. 수평 확장과 무중단 배포는 직접 처리해야 한다. ARM 이미지를 빌드해야 한다(Mac, GHA arm 러너).
