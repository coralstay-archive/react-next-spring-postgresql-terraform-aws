---
id: TASK-36
title: '[플랫폼] VM k3s 로컬 플랫폼 부트스트랩'
status: To Do
assignee: []
created_date: 2026-10-24 03:46
updated_date: '2026-10-29T23:52:00Z'
labels:
  - platform
dependencies:
  - TASK-64
references:
  - decision-1
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
k3d(k3s) 단일 노드 로컬 클러스터와 로컬 레지스트리를 만들고, 프로필별로 켜고 끈다. LB는 k3d LB 컨테이너 + Traefik 인그레스.
선행: 없음.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9 VM rocky-dev 안 k3s 직접 설치, Podman, VM 5GB — Docker 미사용). 프로필(core·gitops·protocols·delivery·shadow)로 필요한 것만 켠다.
- 근거: 승인된 계획(플랫폼 전환, 2026-10-24), doc-3(보안 현황 조사).

## 비즈니스 규칙
- B1 (AC #2) 어떤 프로필 조합도 문서화된 메모리 예산을 넘겨 조용히 뜨지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 make k8s-up 이 VM(rocky-dev) 안 k3s(고정 버전 v1.36.4+k3s1, SELinux enforcing·k3s-selinux)에서 core(Postgres·MinIO·backend)를 띄우고 make k8s-smoke 에서 /api/actuator/health 가 UP
- [ ] #2 모든 pod에 requests/limits, 프로필 합계가 예산(core ~1.6GB)을 넘으면 스크립트가 경고
- [ ] #3 Flyway 마이그레이션을 앱 기동과 분리한 Job으로 실행
- [ ] #4 프로필을 끄면 리소스(PVC 포함)가 삭제되어 메모리가 회수된다(make k8s-profile), make k8s-stop 은 k3s 를 멈춰 메모리 회수(데이터 유지), make k8s-down 은 데이터까지 삭제
- [ ] #5 make test 에 kustomize build + kubeconform 오프라인 검증 추가(증가 10초 미만)
- [ ] #6 메모리 절차 강제(1): make test·make test-integration·backend-test·backend-build·e2e 는 시작할 때 k3s 가 떠 있으면 자동으로 멈추고 그 사실을 출력(커밋 훅의 make test 에도 적용)
- [ ] #7 메모리 절차 강제(2): make k8s-up·k8s-profile 은 VM 메모리 부족(vm-min), Testcontainers 실행 중, SonarQube 실행 중, 프로필 합계 예산 초과면 이유와 해결법을 출력하고 실패(예산 초과는 FORCE=1로만 통과). 각 경우를 셸 테스트로 고정
- [ ] #8 스모크 스크립트들도 같은 사전 검사 함수를 공유한다(검사를 우회하는 진입점이 없음)
- [ ] #9 make dev-db·dev-storage·e2e(지금은 맥 Docker 전제)를 VM의 Podman으로 옮긴다 — 맥에서 실행하면 VM 안에서 podman compose 등으로 동작(유저 결정 2026-10-25)
- [ ] #10 맥 브라우저 접근: 포트 포워딩 없이 VM IP로 Traefik에 접속하고, 맥 /etc/hosts에 이름 1개(board.local)를 둔다. VM IP가 바뀌면 make vm-start가 갱신 방법을 안내하고, / 는 프론트·/api 는 백엔드로 같은 오리진(유저 결정 2026-10-29, doc-4)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 부모가 make test 를 다시 돌려 통과(rc=0) 로그 확인
- [ ] #2 실 VM 에서 make k8s-up → k8s-smoke → k8s-profile → k8s-stop → k8s-down 을 실행해 결과(메모리·health)를 노트에 기록
- [ ] #3 맥에서 make dev-db·dev-storage·e2e 를 실제로 실행해 VM Podman 으로 동작함을 확인
- [ ] #4 k3d·Docker 전제 코드·문서가 남지 않음(grep 으로 확인, 의도적으로 남긴 레거시는 사유 기록)
- [ ] #5 독립 서브에이전트 코드 리뷰 지적 반영
- [ ] #6 README 로컬 k8s 절 갱신, final summary 작성
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
### Subjective
- 유저 결정(2026-10-24 B안): 맥 Docker 를 걷어 내고 k3d 대신 UTM Rocky 9.8 VM(rocky-dev) 안에 k3s 를 직접 설치한다. AWS 노드(TASK-65 의 k3s_install_cmd 자리)와 같은 설치 스크립트를 쓴다.
- 유저 결정(2026-10-25): dev-db·dev-storage·e2e 도 VM Podman 으로. (2026-10-29) 맥 브라우저는 VM IP + /etc/hosts 이름 1개, 포트 포워딩 없음.
- 기존 리뷰 반영분(Secret stdin, Job 대기, 메모리 강제, 스키마 SHA 고정)은 재사용한다.

### Objective
- 지금 구현은 k3d 전제: scripts/k3d.sh(251줄)·_k3d.sh(133줄), deploy/k8s/k3d/cluster.yaml(rancher/k3s:v1.36.4-k3s1, LB 127.0.0.1:80, 레지스트리 localhost:5001), Makefile k3d-up·profile·smoke·stop·down. 매니페스트 deploy/k8s/base·profiles/core(postgres·minio·backend·migrate-job), budgets.txt(limit 3072, core 1600).
- dev-db·dev-storage 는 docker compose(docker-compose.yml), e2e.sh 는 docker compose -p board-e2e.
- VM: 5GB(MemTotal 4649MiB), SELinux enforcing, Podman rootless + docker-compose 공급자(TASK-67), 저장소는 /repo(VirtioFS), make 는 vm_proxy_if_mac 로 VM 안에서 돈다. SonarQube 단독 모드 검사(sonar.sh refuse-if-running)가 k3d 진입점에 걸려 있다.
- AC #3(MigrationJob)은 이미 구현·테스트됨.

### Assessment
- k3s 는 VM 에 systemd 서비스로 설치하되 부팅 때 켜지지 않게(INSTALL_K3S_SKIP_ENABLE) 해서 메모리를 쓰는 시점은 make k8s-up 이 정한다. 설치는 deploy/rocky/k3s-install.sh(버전·sha256 고정, k3s-selinux RPM, metrics-server 끔) 하나로 VM·AWS 가 공유한다.
- 이미지 레지스트리 컨테이너 대신 podman build → podman save | k3s ctr images import 로 넣는다(메모리·포트 절약, imagePullPolicy: Never).
- 들어오는 길: k3s ServiceLB 가 Traefik 을 VM :80 에 연다. 맥은 http://board.local(/etc/hosts → VM IP). make vm-start 가 hosts 와 VM IP 가 다르면 갱신 명령을 안내(sudo 는 유저가 직접).
- 메모리 강제는 기존 _k3d.sh 를 _k8s.sh 로 옮겨 k3s 기준(서비스 활성 여부, VM 메모리 vm-min, Testcontainers, SonarQube, 예산)으로 바꾼다. make 타깃 이름은 k8s-* 로 바꾸고 k3d-* 는 지운다(Docker 가 없어 동작할 수 없음).
- dev-db·dev-storage·e2e 는 VM 안 podman compose 로 돌고 포트는 VM IP 에 연다. 맥의 backend-run·frontend-dev 는 VM IP 로 붙는다. e2e 는 Playwright 까지 VM 안에서 돈다(chromium linux arm64).

### Plan
1. AC#7·#8·#6: _k8s.sh 사전 검사·자동 정지(테스트 먼저 — 가짜 systemctl·podman·free).
2. AC#1·#4: deploy/rocky/k3s-install.sh + k8s.sh up·profile·smoke·stop·down, 이미지 import, kubeconfig 는 VM 안에서만. k3d 파일·타깃 삭제.
3. AC#2·#5: budgets.txt·k8s_test.sh 를 k3s 기준으로(예산 재계산, kubeconform 오프라인 유지).
4. AC#10: ServiceLB·Traefik Host board.local, vm-start 의 hosts 안내.
5. AC#9: dev-db·dev-storage·e2e 를 VM podman compose 로(맥 프록시), README.
6. 실 VM 실측(DoD), 부모 make test, 독립 리뷰 반영, Done.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
AC#2의 '경고'는 AC#7(예산 초과 시 실패, FORCE=1로만 통과)이 우선한다. 세션 중단 대비: 단계마다 이 노트에 진행 상황을 적는다.

[구현 계획] scripts/_k3d.sh(사전 검사 공통 함수: Docker VM 메모리 4.5GiB 미만=5GB 설정 미만, Testcontainers 라벨 컨테이너, 프로필 예산 합계 > 상한(FORCE=1만 통과)) + scripts/k3d.sh(up·profile·smoke·stop·down·stop-if-running, 모든 기동 진입점이 같은 사전 검사) + deploy/k8s/budgets.txt(예산 단일 출처) + deploy/k8s/{k3d/cluster.yaml,base,profiles/core} + kubeconform 스키마 저장소 내 고정(deploy/k8s/schemas, 오프라인) + Flyway 마이그레이션 Job(백엔드 이미지의 MigrationJob main, 앱은 k8s 프로필에서 spring.flyway.enabled=false, initContainer가 await) + scripts/test/k8s_test.sh. 진행: 착수.

[진행] AC#3 백엔드 MigrationJob(migrate·await)+application-k8s.properties 구현·테스트 통과(MigrationJobTest 4건). 스크립트(_k3d.sh·k3d.sh·k8s-validate.sh·test-integration.sh)·매니페스트(deploy/k8s)·k8s_test.sh(50건 통과)·README·CI(kubeconform) 작성 완료, 커밋 중.

[진행] 613497c AC#3 커밋. 다음: 스크립트·매니페스트·테스트·README·CI 커밋 → 백엔드 이미지로 MigrationJob 실제 실행 확인.

[진행] 커밋 613497c(AC#3 MigrationJob)·42547a0(k3d 플랫폼·사전 검사·매니페스트·검증)·6fa87f5(Job 없는 프로필 대기 수정). make test 전체 통과(repo 236, deploy 144, tools 42, k8s 52, terraform 58, backend 888, frontend 577). 실제 이미지로 MigrationJob migrate·await, k8s 프로필 기동(health UP, 392MiB/768MiB) 확인. 실제 k3d-up·k3d-smoke(AC#1·#4)는 Docker VM 3.8GiB 라 사전 검사가 거부 — Docker Desktop 5GB 로 올린 뒤 make k3d-up && make k3d-smoke && make k3d-profile PROFILES=core && make k3d-stop 으로 확인 필요. 남은 일: 독립 서브에이전트 코드 리뷰, AC 체크.

[리뷰 반영 착수] 독립 리뷰 지적(M1 Secret 임시 파일, M2 e2e·backend-test 메모리 강제, L1 k3d 없을 때 docker 로 감지, L2 test.sh 백엔드 직전 재검사, L3 MinIO 자격 증명 URL 인코딩, L4 Job Failed 즉시 실패+로그, L5 문서, 스키마 URL SHA 고정) 반영 시작. 순서: M1+L3 → M2+L2 → L1 → L4 → L5+스키마.

[리뷰 반영] 095b2a2 M1(Secret stdin, 임시 파일 없음)+L3(MinIO 자격 증명 env→mc alias set). 다음: M2(make e2e·backend-test·backend-build 도 stop-if-running)+L2(test.sh 백엔드 직전 재검사).

[리뷰 반영] e96ae61 M2+L2. 다음: L1(k3d 없을 때 docker 라벨)+L4(Job Failed 즉시 실패·로그) 커밋, 그다음 L5 문서+스키마 SHA 고정.

[리뷰 반영] cb89855 L1+L4. 남은 것: L5 문서(k3d.sh 머리말 UPLOADS_BUCKET=ConfigMap, README POSTGRES_PASSWORD 변경 시 make k3d-down)+스키마 URL 커밋 SHA 고정 커밋. Docker Desktop 은 하위 에이전트가 open -a 로 재기동했음(유저 지적 — 이후 앱 실행 금지).

[리뷰 반영 완료] 095b2a2(M1+L3) e96ae61(M2+L2) cb89855(L1+L4) 21f1329(L5+스키마 SHA). make test 통과: repo 236, deploy 144, tools 42, k8s 67, terraform 58, backend 888, frontend 577. 미결(유저 결정 대기): YAML→JSON 파서(ruby), 80 포트, 프로필 데이터 삭제. 실제 클러스터 스모크(AC#1·#4)는 여전히 미확인.

2026-10-24 유저 결정(B안)으로 재작업 예정: k3d·Docker 대신 UTM Rocky 9 VM 안 k3s(선행 TASK-64). 리뷰 반영 커밋(095b2a2..069c71c)의 메모리 강제·Secret·Job 대기·문서 개선은 재사용. 포트는 VM 생성 때 정해지고 k8s-status로 표시, 프로필을 끄면 PVC도 삭제(유저 선택). AC#1·#4·#7의 k3d 문구는 재작업 때 k3s 기준으로 고친다.
<!-- SECTION:NOTES:END -->
