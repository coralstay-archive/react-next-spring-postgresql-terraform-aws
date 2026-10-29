---
id: TASK-67
title: '[품질] SonarQube·SonarQube for IDE 로컬·CI'
status: To Do
assignee: []
created_date: '2026-10-29 01:21'
updated_date: '2026-10-29 03:25'
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
