---
id: TASK-64
title: '[플랫폼] Rocky 개발 VM(UTM) + Podman'
status: In Progress
assignee: []
created_date: '2026-10-24 09:06'
updated_date: '2026-10-24 12:02'
labels:
  - platform
dependencies: []
references:
  - decision-1
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
  - backlog/docs/doc-1-architecture-plan.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
맥에서 Docker를 걷어 내고(2026-10-24 삭제 완료) 개발·배포 OS를 Rocky Linux 9로 통일한다. UTM에 이 프로젝트 전용 VM rocky-dev를 만들고, 그 안에서 Podman과 k3s를 직접 운영한다. AWS 단계(TASK-52)의 Rocky EC2와 같은 설치 스크립트를 쓴다.
선행: 없음. 후행: TASK-36(k3d → VM 안 k3s로 재작업).

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 맥에 컨테이너 런타임이 없어서 VM의 Podman이 동작하기 전까지는 커밋 훅의 make test(Testcontainers)가 실패한다. 그래서 VM·Podman 연결이 첫 번째 목표다.
- 근거: doc-4(플랫폼 전환 결정 기록), 유저 결정(B안: 개발·배포 모두 Rocky + Podman, VM은 UTM, AppleScript는 저장소에).

## 비즈니스 규칙
- B1 (AC #1) 맥에서 OS 권한 창을 띄우는 단계는 make vm-create의 UTM 자동화 허용 1회뿐이며, 실행 전에 안내한다
- B2 (AC #4) 사전 검사는 VM이 꺼져 있을 때 스스로 켜지 않고, 켜는 명령을 안내하고 실패한다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 make vm-create: Rocky 9 aarch64 GenericCloud 이미지 다운로드(체크섬 검증)·Apple Virtualization용 raw 변환, cloud-init seed ISO 생성, 저장소의 AppleScript(deploy/dev-vm/create-vm.applescript)로 UTM VM rocky-dev(5GB·CPU 4·디스크 40GB·VirtioFS로 저장소 공유) 생성. 이미 있으면 거부하고 안내
- [ ] #2 cloud-init: 전용 SSH 키, SELinux enforcing 유지, 고정 버전 설치(podman·Java 25·Node·clang/llvm/libbpf/bpftool/kernel-devel·kubectl·kubeconform·yq·git·make), podman 소켓 활성화. VM 안 설치 스크립트는 AWS Rocky EC2와 공유
- [ ] #3 맥 ~/.ssh/config에 rocky-dev(별도 include 파일), make vm-start·vm-stop·vm-status·vm-ssh
- [ ] #4 사전 검사: VM 메모리·맥 여유 메모리 측정, VM이 꺼져 있으면 켜지 않고 make vm-start를 안내하며 실패. k3s·테스트 진입점이 모두 같은 검사를 거침
- [ ] #5 맥에서 make test를 실행하면 Testcontainers·compose가 VM의 Podman을 쓴다(SSH 터널 소켓 또는 VM 안 실행 중 하나로, 설계 근거 기록). 맥에 Docker가 없어도 커밋 훅의 make test가 통과. CI(ubuntu)는 기존 방식 유지
- [ ] #6 테스트의 YAML→JSON은 ruby 대신 고정 버전 yq(로컬 VM·CI 동일 버전)
- [ ] #7 README 로컬 개발 환경 절(VM 만들기·켜기·끄기·다시 만들기, 메모리 규칙, SELinux 주의, 권한 창 1회 안내)
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
[진행 1] 설계: make test 는 (b) VM 안 실행(ssh rocky-dev, /repo VirtioFS) — 맥 Docker 없음·VM에 고정 도구 일원화. VM 사용자 uid=맥 uid(VirtioFS 권한), node_modules·backend/build·.gradle 은 VM 로컬 bind mount(맥 네이티브 모듈 충돌 방지). 이미지 Rocky-9-GenericCloud-Base-9.8-20260525.0 캐시 ~/.cache/rocky-dev, qcow2→raw 는 brew qemu-img. 테스트 scripts/test/vm_test.sh 먼저 작성 예정

[진행 2] vm_test.sh(53개)·_vm.sh·vm.sh·install.sh·AppleScript·Makefile·README·ci.yml(yq) 작성, 가짜로 전부 통과. 다음: 실제 make vm-create

[진행 3] 1차 vm-create: AppleScript 로 VM 생성은 성공(권한 창 없이 통과), 그러나 seed ISO 를 removable 로 붙여 UTM 샌드박스가 'Cannot access resource' 로 시작 실패. seed 를 복사형 드라이브로 바꾸고 utmctl start 뒤 상태 확인 추가. 깨진 빈 VM 은 utmctl delete 로 지우고 다시 만든다

[진행 4] 2차 vm-create: seed 는 해결, 이번엔 공유 디렉터리(update registry)가 'Cannot access resource' — UTM 샌드박스가 AppleScript 로 받은 경로의 보안 범위 북마크를 남기지 않는다. 결정: AppleScript 는 공유 없이 VM 생성, make test 프록시는 rsync(ssh)로 작업 트리를 VM 로컬 /repo 에 동기화 후 실행. 유저가 UTM GUI 에서 공유를 직접 추가하면(파일 선택 창 = 북마크 생김) /repo 가 VirtioFS 로 마운트되고 rsync 를 건너뛴다. 유저 결정 필요 항목으로 보고

[진행 5] rsync 동기화(vm_sync_repo, VirtioFS 마운트면 생략)·AppleScript 공유 제거·install.sh /repo 사용자 소유 반영, vm_test 55 통과. 기존 VM 은 옛 AppleScript(공유 registry)로 만들어져 시작 불가 → utmctl delete 후 make vm-create 재실행

[진행 6] VM 생성·cloud-init 완료(설치 전부 성공, SELinux Enforcing). 실측 수정: uid 501 이라 useradd 가 subuid 를 안 줌 → install.sh 가 범위 추가, podman.socket 은 사용자 매니저 버스 대기 후 enable --now, cloud-init status 는 root 만 읽음 → sudo -n. podman run alpine 성공. 다음: 맥에서 make test

[진행 7] 맥 make test → VM 프록시 동작: repo 236·deploy 143·tools 42 통과. k8s_test 임시 트리에 _vm.sh·vm_test 반영, Makefile backend-* 의 stop-if-running 을 gradle 앞 줄로. 재실행 중

[진행 8] 백엔드 888개 중 16개 실패 원인: podman API 서비스 기본 5초 유휴 종료 → Testcontainers 연결 Broken pipe. install.sh 에 podman.service drop-in(--time=0) 추가·VM 반영. make test 재실행

[진행 9] 맥 make test(VM 프록시) 전부 통과: repo 236·deploy 143·tools 42·k8s 66·vm 57·terraform 9모듈·backend BUILD SUCCESSFUL·frontend 577. README rsync 방식·선택 공유 반영. 커밋 진행
<!-- SECTION:NOTES:END -->
