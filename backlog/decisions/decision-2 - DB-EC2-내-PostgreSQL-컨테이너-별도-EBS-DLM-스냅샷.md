---
id: decision-2
title: 'DB: EC2 내 PostgreSQL 컨테이너 + 별도 EBS + DLM 스냅샷'
date: '2026-10-11 13:01'
status: accepted
---
## Context

DB를 EC2 안에 둘지 RDS로 둘지 정해야 했다.

## Decision

PostgreSQL을 EC2의 Compose 컨테이너로 돌리고, 데이터는 별도 EBS 볼륨에 둔다. DLM으로 매일 스냅샷을 찍는다. 접속 정보는 env로만 받는다.

## Consequences

추가 비용이 거의 없다. 백업, 패치, 장애 대응은 직접 해야 한다. env만 바꾸면 RDS로 옮길 수 있다.
