---
id: TASK-67
title: '[품질] SonarQube·SonarQube for IDE 로컬·CI'
status: In Progress
assignee: []
created_date: 2026-10-29 01:21
updated_date: '2026-10-29T11:51:00Z'
labels:
  - quality
  - platform
dependencies:
  - TASK-64
references:
  - decision-11
documentation:
  - backlog/docs/doc-4 - 플랫폼-전환-결정-기록-2026-10-24.md
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
SonarQube Community Build 를 VM Podman 에서 단독 모드로 띄우고(공식 최소 4GB라 k3s·테스트와 동시 불가), IDE 는 SonarQube for IDE(옛 SonarLint) 연결 모드로 붙인다. CI 에서도 서비스 컨테이너로 분석·품질 게이트. 게이트는 새 코드 기준(기존 부채로 막지 않음).
선행: TASK-64.

## 진행 방식
- Plan-Review-Go: 착수 전 구현 계획을 채우고, 구현 후 독립 서브에이전트 코드 리뷰를 반영한 뒤 Done.
- Red-Green-Refactor: AC마다 red(실패 테스트) → green(최소 구현) → refactor.
- 로컬 전용(8GB M1, UTM의 Rocky Linux 9.8 VM rocky-dev, Podman, VM 5GB — Docker 미사용). 실제 AWS 호출·apply 없음.
- 근거: decision-11(실배포 인프라), doc-4, 계획 승인(2026-10-29).

## 비즈니스 규칙
- B1 (AC #2) SonarQube 는 k3s·make test 와 동시에 뜨지 않는다 — 이유와 해결법을 출력하고 실패
- B2 (AC #4) 분석 토큰은 저장소·.env 에 두지 않는다
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 deploy/sonar/compose.yaml(SonarQube Community Build·postgres:18-alpine 다이제스트 고정)을 VM Podman 으로: make sonar-up·sonar-down·sonar-status, 접속 http://<VM IP>:9000
- [ ] #2 단독 모드 강제: k3s 실행 중·make test 잠금(/tmp/board-repo-test.lock)·VM 메모리 부족이면 이유와 해결법을 출력하고 실패(scripts/_vm.sh 사전 검사 재사용, budgets.txt 에 sonar 예산, 힙은 SONAR_*_JAVAOPTS 로 축소). 각 경우를 셸 테스트로 고정
- [ ] #3 install.sh dev 프로필: vm.max_map_count=524288, fs.file-max=131072, nofile 131072 (재실행해도 같은 결과)
- [ ] #4 make sonar-scan: 백엔드 Gradle org.sonarqube + JaCoCo XML, 프론트 vitest lcov + sonar-scanner-cli 컨테이너(다이제스트 고정), sonar.qualitygate.wait=true 로 게이트 실패 시 non-zero. 토큰은 VM 설정 파일에서 읽음. 첫 분석 기준선을 노트에 기록
- [ ] #5 SonarQube for IDE 연결 모드: .sonarlint/connectedMode.json 커밋, README 에 VS Code·IntelliJ 연결 방법
- [ ] #6 CI sonar.yml: GHA 서비스 컨테이너로 SonarQube 를 띄워 분석·품질 게이트, 관리자 비밀번호는 실행마다 API 로 교체, actionlint 통과
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## 구현 계획 (승인된 계획 2026-10-29)
1. AC#1 deploy/sonar/compose.yaml: SonarQube Community Build(다이제스트 고정) + postgres:18-alpine(다이제스트 고정, 볼륨 분리, 포트 비공개). make sonar-up·sonar-down·sonar-status — 맥에서는 vm_proxy_if_mac 로 VM 안 podman compose. 접속 http://<VM IP>:9000(sonar-status 가 주소 출력). 데이터는 VM 로컬 볼륨.
2. AC#2 단독 모드: scripts/_sonar.sh 사전 검사 — k3s(또는 k3d) 실행 중, /tmp/board-repo-test.lock 잡힘, VM 메모리 < budgets.txt 의 sonar 예산이면 이유·해결법 출력 후 실패. make test 쪽도 SonarQube 가 떠 있으면 거부(역방향). 힙: SONAR_WEB_JAVAOPTS·SONAR_CE_JAVAOPTS·SONAR_SEARCH_JAVAOPTS 축소. 각 경우 셸 테스트.
3. AC#3 install.sh dev: /etc/sysctl.d/99-sonarqube.conf(vm.max_map_count=524288, fs.file-max=131072), limits nofile 131072 — 재실행해도 같은 결과. 실 VM 에 적용·확인.
4. AC#4 make sonar-scan: 백엔드 Gradle org.sonarqube 플러그인 + jacoco(XML), 프론트 vitest --coverage(lcov) + sonar-scanner-cli 컨테이너(다이제스트 고정), sonar.qualitygate.wait=true. 토큰은 VM 의 ~/.config/board-sonar/token(유저가 UI 에서 발급해 둠, 저장소·.env 아님) — 없으면 안내 후 실패. 첫 분석 기준선(이슈 수·커버리지)을 노트에 기록.
5. AC#5 .sonarlint/connectedMode.json(sonarQubeUri·projectKey) 커밋, README 에 VS Code·IntelliJ 연결 모드 안내.
6. AC#6 .github/workflows/sonar.yml: services 로 SonarQube·Postgres, 기동 대기, 관리자 비밀번호 API 로 교체(실행마다 무작위), 토큰 발급 → 분석 → 게이트. permissions 최소, actionlint 통과.
- 구현은 서브에이전트, 부모가 make test 재검증 → 독립 리뷰 → Done.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
AC#1 진행: deploy/sonar/compose.yaml(SonarQube 26.10.0.133078-community·postgres:18-alpine 다이제스트 고정, 프로젝트 board-sonar, 9000 만 공개, 이름 있는 볼륨), scripts/sonar.sh·_sonar.sh(up/down[--purge]/status, 맥은 vm_proxy_if_mac), make sonar-up·sonar-down·sonar-status. VM 에 compose 공급자가 없어(podman compose → 공급자 없음) install.sh dev 에 docker-compose v5.6.1 플러그인(sha256 고정, /usr/local/lib/docker/cli-plugins) 추가·VM 에 재적용. 실측: 첫 sonar-up 은 vm.max_map_count 65530 으로 Elasticsearch 부트스트랩 검사 실패 → 컨테이너가 멈추면 기다리지 않고 원인 안내하도록 고침(AC#3 에서 sysctl). sonar_test.sh 16개.

AC#3 진행: install.sh dev 에 sonar_kernel_setup — /etc/sysctl.d/99-sonarqube.conf(vm.max_map_count = 524288, fs.file-max = 131072 는 커널 값이 그보다 작을 때만 — Rocky 9 기본 9223372036854775807 을 낮추지 않는다, SonarQube 요구는 '131072 이상'; 한 번 썼으면 계속 쓴다) + sysctl -p 즉시 적용, /etc/security/limits.d/99-sonarqube.conf(rocky soft·hard nofile 131072). 컨테이너는 compose ulimits nofile 131072. 실 VM: install.sh 두 번 실행 → 두 파일 md5 동일, vm.max_map_count=524288·fs.file-max 그대로, ssh 세션 ulimit -n/-Hn 131072. 적용 전 sonar-up 은 ES 부트스트랩 검사(65530)로 실패, 적용 뒤 성공. sonar_test.sh AC#3 3개(가짜 sysctl·임시 디렉터리).

AC#5 진행: .sonarlint/connectedMode.json(board-backend)·.sonarlint/frontend.json(board-frontend) 커밋 — sonarQubeUri 는 http://localhost:9000(맥에서 ssh -N -L 9000:localhost:9000 rocky-dev 터널; VM IP 는 DHCP 로 바뀔 수 있어 공유 설정에 넣지 않는다). README '코드 품질 (SonarQube, TASK-67)' 절: make 타깃, 단독 모드·메모리 숫자(budgets 와 테스트로 맞춤), 커널 값·기존 VM 재적용 명령, 비밀번호·토큰 위치(출력 없음), 게이트·PREVIOUS_VERSION, 스캐너 컨테이너 이유, VS Code·IntelliJ 연결 절차, CI. 실제 IDE 연결은 맥에서 확인하지 않았다(GUI 앱 미사용).

AC#6 진행: .github/workflows/sonar.yml — pull_request·workflow_dispatch, permissions contents: read 만, services sonar-db(postgres:18-alpine)·sonarqube(26.10 Community, compose 와 같은 다이제스트 — 테스트가 비교), SONAR_ES_BOOTSTRAP_CHECKS_DISABLE(서비스가 단계보다 먼저 떠 러너 sysctl 을 못 바꾼다), checkout fetch-depth 0 → setup-java@v6·setup-node@v7 → scripts/sonar.sh init(SONAR_CONFIG_DIR=$RUNNER_TEMP/sonar, 실행마다 무작위 관리자 비밀번호·토큰, ::add-mask::) → scripts/sonar.sh scan(게이트). secret 없음. actionlint 통과(VM, shellcheck 연동). 한계: 서버가 매번 새로 떠 매번 첫 분석 → 새 코드 게이트는 사실상 통과(README 명시). 실제 GitHub 러너 실행은 하지 않았다(push 없음).

리뷰 반영 1(B1·B2 보강): k3d_preflight(k3d-up·profile·smoke)도 scripts/sonar.sh refuse-if-running 으로 SonarQube 가 떠 있으면 거부(맥에서는 ssh 로 VM 안 podman ps — k3s 재작업에도 그대로 쓰는 진입점). 잠금 확인 flock 이 0·75 가 아니면 거부, podman ps 실패는 '없음'이 아니라 거부(실패 쪽으로 닫힘). 9000 은 VM 127.0.0.1 에만 공개 — 맥은 ssh -N -L 9000:localhost:9000 rocky-dev 터널(connectedMode 주소와 같다), 자격 증명을 맞추지 못하면 compose stop 후 실패. curl -K 설정 값의 \ " 이스케이프. 실 VM: sonar-up 뒤 맥에서 http://192.168.64.13:9000 → 연결 안 됨(000), 터널로 UP 확인, 맥 make k3d-up 은 'SonarQube 가 떠 있다 … make sonar-down' 으로 거부. sonar_test·k8s_test 에 테스트 추가(가짜 ps 실패·flock 실패·맥 ssh 확인·k3d 3 진입점).

리뷰 반영 2: JaCoCo 에이전트는 coverage 속성 값이 false 가 아닐 때만(-Pcoverage=false 는 끔). @vitest/coverage-v8 범위를 vitest 와 같은 ^5.0.3 으로(lock 설치 버전 5.0.3 그대로 — 최신 5.0.5 는 vitest 5.0.5 를 요구해 맞추지 않았다).
<!-- SECTION:NOTES:END -->
