---
id: DRAFT-44
title: '[관측] eBPF 지연 프로파일러 모듈'
status: Draft
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 04:25'
labels:
  - observability
  - ebpf
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
에이전트의 세 번째 모듈. pod별로 어느 시스템 호출·파일 I/O·스케줄링 대기에서 시간이 걸리는지 보여준다.
선행: eBPF 에이전트 기반.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, k3d 단일 노드, Docker VM 5GB 권장). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 pod별 syscall 지연 상위 목록(예: read/write/fsync/connect)
- [ ] #2 pod별 파일 I/O 지연, 런큐 대기(off-CPU) 시간
- [ ] #3 평소에는 꺼 두고 요청 시 N초만 켜는 온디맨드 모드(오버헤드 최소화), 검진센터 자동 진단 스냅샷이 호출 가능
- [ ] #4 결과를 JSON 리포트로 내보냄, 오버헤드 측정 결과 문서화
<!-- AC:END -->
