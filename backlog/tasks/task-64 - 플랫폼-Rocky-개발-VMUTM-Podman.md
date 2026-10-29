---
id: TASK-64
title: '[플랫폼] Rocky 개발 VM(UTM) + Podman'
status: Done
assignee: []
created_date: '2026-10-24 09:06'
updated_date: '2026-10-29 03:22'
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
- [x] #1 make vm-create: Rocky 9 aarch64 GenericCloud 이미지 다운로드(체크섬 검증)·Apple Virtualization용 raw 변환, cloud-init seed ISO 생성, 저장소의 AppleScript(deploy/dev-vm/create-vm.applescript)로 UTM VM rocky-dev(5GB·CPU 4·디스크 40GB) 생성. 저장소는 rsync(ssh)로 VM /repo에 동기화하는 것이 기본이고, 유저가 UTM 화면에서 붙인 VirtioFS 공유가 있으면 자동 감지해 그것을 쓴다(UTM 샌드박스는 AppleScript로 받은 경로에 접근 권한을 남기지 못함 — 유저 결정 2026-10-25). 이미 있으면 거부하고 안내
- [x] #2 cloud-init: 전용 SSH 키, SELinux enforcing 유지, 고정 버전 설치(podman·Java 25·Node·clang/llvm/libbpf/bpftool/kernel-devel·kubectl·kubeconform·yq·git·make), podman 소켓 활성화. VM 안 설치 스크립트는 AWS Rocky EC2와 공유
- [x] #3 맥 ~/.ssh/config에 rocky-dev(별도 include 파일), make vm-start·vm-stop·vm-status·vm-ssh
- [x] #4 사전 검사: VM 메모리·맥 여유 메모리 측정, VM이 꺼져 있으면 켜지 않고 make vm-start를 안내하며 실패. k3s·테스트 진입점이 모두 같은 검사를 거침
- [x] #5 맥에서 make test를 실행하면 Testcontainers·compose가 VM의 Podman을 쓴다(SSH 터널 소켓 또는 VM 안 실행 중 하나로, 설계 근거 기록). 맥에 Docker가 없어도 커밋 훅의 make test가 통과. CI(ubuntu)는 기존 방식 유지
- [x] #6 테스트의 YAML→JSON은 ruby 대신 고정 버전 yq(로컬 VM·CI 동일 버전)
- [x] #7 README 로컬 개발 환경 절(VM 만들기·켜기·끄기·다시 만들기, 메모리 규칙, SELinux 주의, 권한 창 1회 안내)
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

[진행 10] 커밋 eafd127(전체 + 부모 backlog 편집). 남은 결정: (선택) UTM GUI 로 저장소 공유 추가 여부, AC #1 의 'VirtioFS 공유' 문구를 rsync 기본으로 바꿀지

[진행 11] 독립 리뷰 6건 반영(ba6b714, 커밋 전 make test rc=0 528초). (1) worktree: VM 에 맥 저장소 경로 → /repo 심볼릭 링크 + 공통 .git 을 /repo/.git 에 rsync(공유 밖 공통 .git 은 거부) (2) VM 안 /tmp/board-repo-test.lock(flock -n): rsync 전 확인·rsync·원격 실행 모두. 원격 명령은 setsid 그룹 + 부모(sshd) 감시로 ssh 가 끊기면 그룹 TERM/KILL — 실 VM 에서 맥 ssh 를 죽이자 15초 안에 원격 make test 정리·잠금 해제, 동시 make test 는 1.5초 만에 잠금 오류로 실패 확인 (3) scripts/test.sh 주석 rsync 기본 + 선택 VirtioFS. AC #1 의 'VirtioFS 로 저장소 공유'는 UTM 샌드박스가 AppleScript 로 받은 경로의 보안 범위 북마크를 남기지 않아 선택 사항(UTM 화면에서 직접 추가)이 됐다 — AC 문구는 부모가 유저와 갱신 (4) vm-min 4608→4500(실측 4649) (5) cloud-init status --wait 를 VM 안 timeout 1200 + ssh keepalive(15초×4)로, 맥에 남아 있던 옛 cloud-init 대기 ssh(PID 32530, PPID 1) 종료 (6) 공유를 나중에 붙였을 때 bind 덮기 재실행 명령(ssh rocky-dev sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo)을 README·install.sh 에 기록, 빠져 있으면 make test 가 경고. README 에 커밋 훅 make test 의 600초 시간 제한(fail-open) 안내

[진행 12] UTM Apple Virtualization 백엔드는 디렉터리 공유를 '여러 디렉터리 공유'로 내보내 태그 share 루트 아래에 고른 맥 폴더 이름의 디렉터리가 하나 생긴다(실측: /repo/react-next-…) — /repo 가 저장소가 아니었다. install.sh dev_share_setup: 태그를 /mnt/share 에 마운트, 루트에 .git·Makefile 이 있으면 루트·아니면 하위 디렉터리 하나를 /repo 로 bind(fstab x-systemd.requires-mounts-for), 여럿이면 실패. 옛 줄(share /repo virtiofs)은 지우고 내린 뒤 옮긴다. _vm.sh: VirtioFS 판정을 findmnt FSTYPE 로(stat -f 는 fuseblk 로 보고 — 실측), /mnt/share 만 있고 bind 가 없으면 재실행 명령 경고. vm_test 72 통과(가짜 mount 로 중첩·루트·모호·옛 줄 이전·재실행). 실 VM: 재설치 후·재부팅 후 /repo = share[/react-…] virtiofs, node_modules·backend/build·.gradle VM 로컬 bind, 재실행 fstab 그대로. 맥 make test(VirtioFS 감지·rsync 생략) rc=0

[진행 13] 리뷰(e70f957) 실패 경로 반영: 공유 안을 먼저 보고(옛 배치면 /repo 에서, 아니면 /mnt/share 를 fstab 없이 마운트) 저장소를 못 고르면 fstab·마운트를 그대로 둔 채 실패, 하위 디렉터리 하나도 .git·Makefile 이 있어야 함. /repo 는 umount -R 이 성공한 뒤에만 fstab 수정 — EBUSY 면 덮기를 다시 붙이고 실패. /repo bind 줄은 덮기 줄 앞, fstab 이스케이프 \\134·\\040·\\011, 마운트 지점 /repo 는 항상 사용자 소유. _vm.sh: /mnt/share 경고에 '고를 수 없음' 포함, /repo 를 쓸 수 없으면 rsync 전에 재실행 안내 후 실패. vm_test 76 통과(공백·역슬래시, EBUSY, 폴더 이름 변경, 이전 중 모호 → 그대로). 실 VM 재적용: fstab·findmnt -R /repo 변화 없음, /repo 마운트 지점 rocky 소유

[진행 13] 부모 검증(2026-10-29): e8f73cd 위에서 맥 make test rc=0 — VirtioFS 공유로 rsync 없이 실행, repo 236·deploy 143·tools 42·k8s 66·vm 76·terraform 9모듈 통과, backend BUILD SUCCESSFUL(변경 없어 UP-TO-DATE), frontend 577. 실 VM: /mnt/share(virtiofs) → /repo bind(share[/react-next-spring-postgresql-terraform-aws]) + 덮기 3개, 재부팅 후 동일. 독립 리뷰(e70f957) 지적 6건 e8f73cd 반영. Terraform lock 3플랫폼 해시 고정(2c77e41).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
맥에서 Docker 없이 개발·테스트하도록 UTM Rocky Linux 9.8 VM(rocky-dev, 5GB·CPU 4·40GB)과 Podman 환경을 만들었다. make vm-create(이미지 체크섬·raw 변환·cloud-init seed·AppleScript, 권한 창 1회), vm-start/stop/status/ssh, ~/.ssh/config.d/rocky-dev. install.sh(dev/server 프로필, SELinux enforcing, 고정 버전 도구, rootless Podman 소켓 --time=0)는 AWS EC2와 공유한다. 사전 검사는 VM 을 스스로 켜지 않는다. 맥의 make test 는 VM 안에서 돈다: UTM 공유가 있으면 /mnt/share(VirtioFS, Apple 백엔드는 폴더 이름 하위 디렉터리) → /repo bind 로 바로 쓰고, 없으면 rsync. 동시 실행 잠금·ssh 끊김 정리·worktree 지원. yq 고정 버전으로 ruby 제거, Terraform lock 은 3플랫폼 해시. 검증: make test 전체 통과(repo 236, deploy 143, tools 42, k8s 66, vm 76, terraform 9모듈, backend 888, frontend 577), 실 VM 재부팅 후 마운트 유지, 독립 리뷰 2회 반영. 남은 것: 실 VM 에서 옛 배치 마이그레이션·사용 중 언마운트·모호한 공유는 가짜 테스트로만 확인.
<!-- SECTION:FINAL_SUMMARY:END -->
