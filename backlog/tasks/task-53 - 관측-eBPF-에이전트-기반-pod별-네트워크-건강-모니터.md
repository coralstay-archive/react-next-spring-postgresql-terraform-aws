---
id: TASK-53
title: '[관측] eBPF 에이전트 기반 + pod별 네트워크 건강 모니터'
status: To Do
assignee: []
created_date: '2026-10-24 04:25'
updated_date: '2026-10-24 09:06'
labels:
  - observability
  - ebpf
dependencies:
  - TASK-43
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
C로 만든 단일 에이전트(DaemonSet) — 커널 쪽 BPF와 사용자 공간 모두 C(libbpf·CO-RE, bpftool skeleton, 작은 C HTTP /metrics). 유저 결정 2026-10-24. 모듈을 설정으로 켜고 끈다(메모리 절약). 첫 모듈은 pod별 TCP 건강: 연결 지연, 재전송, 연결 거절, 리셋.
선행: 관측성(Prometheus).

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필로 필요한 것만 켠다(새 프로필: ops).
- 근거: 플랫폼 전환 계획(2026-10-24)과 유저 추가 요청(eBPF 3종, 장애대응 검진센터, 실배포 고려사항).

## 비즈니스 규칙
- B1 (AC #1) 에이전트는 앱 코드·설정을 바꾸지 않고 관찰만 한다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 에이전트 골격: 모듈 레지스트리, 설정으로 모듈 on/off, cgroup id → pod·컨테이너 이름 매핑, Prometheus 지표 노출, 메모리 64Mi 이하
- [ ] #2 네트워크 모듈: pod·대상별 TCP connect 지연 히스토그램, 재전송·RST·connect 실패 카운터
- [ ] #3 카나리 분석과 검진센터가 쓸 수 있는 레코딩 룰(예: 백엔드 → DB 연결 지연 p95)
- [ ] #4 권한 최소화: 필요한 capability만(CAP_BPF·CAP_PERFMON 등), privileged 없이 가능한지 확인하고 불가하면 사유 문서화
- [ ] #5 착수 스파이크: Rocky 9 VM 커널의 BTF/CO-RE 지원 확인(기본 제공 예상), 빌드 도구 clang·llvm·libbpf-devel·bpftool·kernel-devel을 VM에 고정 설치
- [ ] #6 C 단위 테스트(이벤트 디코딩·cgroup→pod 라벨링)와 k3s 스모크(일부러 끊긴 포트로 연결 실패를 만들어 카운터 증가 확인)
<!-- AC:END -->
