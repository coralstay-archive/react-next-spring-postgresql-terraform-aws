---
id: decision-11
title: '실배포 인프라: 관리형 미사용 · 스팟 노드 1대 · LB·CloudFront 없음'
date: '2026-10-29 01:20'
status: accepted
---
## Context
실제 AWS 배포는 로컬에서 할 수 있는 것을 모두 끝낸 뒤(TASK-52)로 미뤘다. 그 전에 실배포 구조를 확정하고 Terraform 얼개(TASK-65)를 잡기로 했다(2026-10-29 유저 결정). doc-4는 관리형 범위를 "혼합 권장, AWS 단계에서 확정"으로 남겨 두었고, 로컬 플랫폼(k3s·Argo·Kafka·Prometheus·eBPF·검진센터)은 t4g.small(2GB)에 올라가지 않는다.

비용 조사(us-east-1, 2026-10): t4g.large 온디맨드 $0.0672/h(약 $49/월), 스팟 약 $0.021~0.026/h(약 $15~19/월). 공인 IPv4 $0.005/h(약 $3.65/개·월). t4g.small 무료 체험은 2026-12-31까지(750시간/월).

## Decision
- 관리형 미사용(인프라만 자체 운영): DB·Kafka는 k3s 안, 오브젝트 스토리지는 MinIO(S3 대신), 레지스트리는 GHCR, 모니터링은 Prometheus·Alertmanager(CloudWatch 대신), TLS는 cert-manager + Let's Encrypt, CloudFront를 쓰지 않는다. RDS·MSK·ECR·NAT·ALB도 쓰지 않는다.
- 유지: 기능 제공자 Cognito(로그인 수단)·End User Messaging SMS(휴대폰 OTP), 접속·비밀 값용 SSM(Session Manager·Parameter Store 표준, 무료), AWS Budgets 알림, 데이터 EBS 스냅샷(토글).
- 노드: t4g.large(8GB) 스팟 1대(persistent, 회수 시 stop), Rocky Linux 9.8 arm64. 데이터는 별도 gp3 볼륨. EIP 1개. 별도 LB EC2는 두지 않고 Traefik을 노드에서 바로 노출한다. HAProxy LB 모듈은 만들어 두되 기본으로 꺼 둔다.
- 미결(TASK-52에서 결정): 도메인(그때까지는 EIP의 sslip.io 이름 + Let's Encrypt 안), WAF 위치(CloudFront가 없으면 AWS WAF를 붙일 곳이 없다).

## Consequences
- 추정 비용: 상시 가동 약 $22~27/월, 밤 12시간 정지 시 약 $15/월. 레거시 계획(t4g.small + CloudFront, 약 $20~21)과 비슷한 비용에 메모리가 4배다. Infracost(TASK-66)와 비용 상한 검사로 고정한다.
- 스팟 회수 시 노드가 멈춘다. 데이터 볼륨은 남고, 회수 대응은 TASK-60 #4에서 한다. 단일 노드라 고가용성은 없다.
- 백업·패치·장애 대응·TLS 갱신을 직접 해야 한다(TASK-60·61·52).
- decision-1(EC2+Compose)·decision-3(CloudFront 단일 오리진)·decision-10(Next static export)은 AWS 단계에서 이 결정과 doc-4로 대체된다(레거시 경로 철거는 TASK-52 AC #5). decision-2의 "DB를 노드 안에서 직접 운영, 별도 EBS·스냅샷" 취지는 k3s 위에서 이어진다.
- 업로드 presigned URL은 S3 대신 Traefik을 거쳐 MinIO를 가리키도록 바뀐다(TASK-52).
