---
id: TASK-54
title: '[관측] eBPF 런타임 보안 감시 모듈'
status: To Do
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 05:06'
labels:
  - security
  - ebpf
dependencies:
  - TASK-53
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
  - backlog/docs/doc-3 - 보안-현황-조사-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
에이전트의 두 번째 모듈. 컨테이너 안의 예상 밖 동작을 잡아 경보한다(Falco·Tetragon의 작은 버전).
선행: eBPF 에이전트 기반.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 프로세스 실행 감시: 컨테이너 안 셸 실행, 예상 밖 바이너리 실행(pod별 허용 목록)
- [ ] #2 민감 파일 접근 감시: 서비스 계정 토큰, /etc/shadow 등 목록 기반
- [ ] #3 예상 밖 외부 연결 감시: pod별 허용 목적지 밖으로 나가는 연결
- [ ] #4 이벤트는 구조화 로그와 Prometheus 카운터로, 검진센터 인시던트로 연결 가능
- [ ] #5 오탐 제어: 허용 목록을 ConfigMap으로, 데모 시나리오(kubectl exec로 셸 실행)를 스모크로 재현
<!-- AC:END -->
