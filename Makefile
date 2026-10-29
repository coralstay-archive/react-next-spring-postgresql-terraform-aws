# repo 루트의 .env(example.env 를 복사)는 로컬 실행 타깃(dev-db, dev-storage, dev-db-down, backend-run, frontend-dev, k3d-up, k3d-profile)의
# 레시피 안에서만 읽는다(TASK-35#4, OPS-41). 전역으로 include·export 하지 않는다 — 배포(deploy*, put-secrets, tf-outputs)·smoke·
# test·e2e 에 로컬 값(AWS_PROFILE, DRY_RUN 같은)이 섞이거나, 명령줄·환경변수로 준 DRY_RUN=1 을 덮지 않게. 없으면 기본값을 쓴다.
# 로컬 실행 타깃에서도 이미 정의된 변수(환경변수, make 명령줄 변수)는 덮지 않는다 — make dev-db POSTGRES_PORT=5433 이 파일 값보다 우선한다
# (scripts/local-env.sh: 정의되지 않은 키만 export 문으로 출력). 셸 문법으로 읽으므로 값에 공백·특수문자가 있으면 따옴표로 감싸고,
# 문법 오류면 타깃이 실패한다. docker compose 는 같은 디렉터리의 .env 를 스스로도 읽지만 셸 환경변수가 우선한다.
LOCAL_ENV = local_env="$$(./scripts/local-env.sh .env)" && eval "$$local_env" &&

.PHONY: help test test-integration vm-create vm-start vm-stop vm-status vm-ssh k3d-up k3d-profile k3d-smoke k3d-stop k3d-down dev-db dev-storage dev-db-down backend-run backend-test backend-build frontend-install frontend-dev frontend-test frontend-build \
	deploy deploy-backend deploy-frontend put-secrets tf-outputs smoke e2e diagrams cost cost-diff sonar-up sonar-down sonar-status sonar-scan

help: ## 타깃 목록
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-18s %s\n", $$1, $$2}'

test: ## 리포·k8s 매니페스트·Terraform·백엔드·프론트 테스트 일괄 실행 (떠 있는 k3d 클러스터는 먼저 멈춘다)
	./scripts/test.sh

test-integration: ## Testcontainers 통합 테스트(백엔드) (떠 있는 k3d 클러스터는 먼저 멈춘다)
	./scripts/test-integration.sh

# --- 로컬 k3d 플랫폼 (TASK-36, README 로컬 k8s). 모든 타깃이 scripts/k3d.sh 를 거치고, 띄우거나 쓰는 타깃(up·profile·smoke)은
# 같은 사전 검사(Docker VM 메모리 5GB·Testcontainers 없음·프로필 예산 deploy/k8s/budgets.txt)를 먼저 지난다. FORCE=1 은 예산 검사만 넘긴다.
PROFILES ?=

k3d-up: ## 로컬 k3d 클러스터 + 프로필(기본 core: Postgres·MinIO·backend) 기동. PROFILES=core,... FORCE=1 SKIP_BUILD=1
	$(LOCAL_ENV) PROFILES="$(PROFILES)" ./scripts/k3d.sh up

k3d-profile: ## 켤 프로필만 남기고 나머지는 삭제(메모리 회수): make k3d-profile PROFILES=core
	$(LOCAL_ENV) PROFILES="$(PROFILES)" ./scripts/k3d.sh profile

k3d-smoke: ## k3d LB + Traefik 으로 /api/actuator/health 가 UP 인지 (Host: board.localhost)
	./scripts/k3d.sh smoke

k3d-stop: ## k3d 클러스터 정지(데이터 유지, 메모리 회수)
	./scripts/k3d.sh stop

k3d-down: ## k3d 클러스터 삭제(데이터까지)
	./scripts/k3d.sh down

dev-db: ## 로컬 PostgreSQL 기동 (docker compose)
	$(LOCAL_ENV) docker compose up -d --wait postgres

dev-storage: ## 로컬 첨부 저장소 MinIO 기동 + 버킷 준비 (http://localhost:9000, 콘솔 :9001)
	$(LOCAL_ENV) docker compose up -d --wait minio
	$(LOCAL_ENV) docker compose run --rm minio-init

dev-db-down: ## 로컬 PostgreSQL·MinIO 중지
	$(LOCAL_ENV) docker compose down

backend-run: ## 백엔드 실행 (http://localhost:8080/api)
	$(LOCAL_ENV) cd backend && ./gradlew bootRun

# gradle test·build 는 Testcontainers 를 띄운다 — 떠 있는 k3d 클러스터를 먼저 멈춘다(TASK-36#6, 8GB)
# TASK-64#5 맥에는 컨테이너 런타임이 없다 — 맥이면 사전 검사 후 Rocky 개발 VM 안에서 같은 타깃을 돌린다(scripts/vm.sh run)
backend-test: ## 백엔드 테스트 (맥이면 VM 안에서, 떠 있는 k3d 클러스터는 먼저 멈춘다)
	@if ./scripts/vm.sh on-mac; then ./scripts/vm.sh run make backend-test; else \
	  ./scripts/k3d.sh stop-if-running "make backend-test" && \
	  ./scripts/sonar.sh refuse-if-running "make backend-test" && \
	  cd backend && ./gradlew test; fi

backend-build: ## 백엔드 빌드 (bootJar, 테스트 포함 — 맥이면 VM 안에서, 떠 있는 k3d 클러스터는 먼저 멈춘다)
	@if ./scripts/vm.sh on-mac; then ./scripts/vm.sh run make backend-build; else \
	  ./scripts/k3d.sh stop-if-running "make backend-build" && \
	  ./scripts/sonar.sh refuse-if-running "make backend-build" && \
	  cd backend && ./gradlew build; fi

# --- Rocky 개발 VM (TASK-64, README 로컬 개발 환경). 맥의 make test·test-integration·backend-test·backend-build 는 이 VM 안에서 돈다.
vm-create: ## Rocky 9 개발 VM(UTM rocky-dev, 5GB·CPU 4·40GB, 저장소 → /repo) 만들기. UTM 자동화 허용 창이 한 번 뜬다
	./scripts/vm.sh create

vm-start: ## 개발 VM 켜기 + ssh 설정(~/.ssh/config.d/rocky-dev) 갱신
	./scripts/vm.sh start

vm-stop: ## 개발 VM 끄기(게스트 정상 종료, 메모리 회수)
	./scripts/vm.sh stop

vm-status: ## 개발 VM 상태·IP·메모리(VM·맥)
	./scripts/vm.sh status

vm-ssh: ## 개발 VM 에 ssh (꺼져 있으면 make vm-start 안내)
	./scripts/vm.sh ssh

frontend-install: ## 프론트 의존성 설치 (npm ci. frontend-* 타깃은 lock 이 바뀌었을 때만 자동으로 한다)
	./scripts/frontend-deps.sh --force

frontend-dev: ## 프론트 개발 서버 (http://localhost:3000)
	./scripts/frontend-deps.sh
	$(LOCAL_ENV) cd frontend && npm run dev

frontend-test: ## 프론트 테스트
	./scripts/frontend-deps.sh
	cd frontend && npm test

frontend-build: ## 프론트 정적 export (frontend/out)
	./scripts/frontend-deps.sh
	cd frontend && npm run build

diagrams: ## diagrams/*.dot → docs/diagrams/*.svg 렌더 + 문서의 <!--dot:이름--> 블록 갱신 (graphviz 필요)
	python3 scripts/build-diagrams.py

# --- 비용 추정 (TASK-66, README 비용 추정). Infracost(infracost.yml). 맥이면 VM 안에서 돈다. API 키가 없으면 경고 후 건너뛴다
# 상한: COST_LIMIT > COST_LIMIT_USD(환경변수) > 30(cost-guard 기본). 맥에서는 환경변수가 VM 으로 넘어가지 않아 make 가 인자로 넘긴다
COST_LIMIT ?=
COST_BASE ?= main

cost: ## Terraform 월 비용 추정(infra/platform·비교용 infra/main) + 상한 검사(COST_LIMIT > COST_LIMIT_USD > 30 USD)
	./scripts/cost.sh breakdown $(or $(COST_LIMIT),$(COST_LIMIT_USD))

cost-diff: ## 기준 브랜치(COST_BASE, 기본 main) 대비 월 비용 차이
	./scripts/cost.sh diff $(COST_BASE)

# --- SonarQube (TASK-67, README 코드 품질). Rocky 개발 VM 안 Podman 에 단독으로 띄운다(k3s·make test 와 동시에 띄우지 않는다).
# 맥이면 VM 안에서 돈다. 접속 http://<VM IP>:9000 (make sonar-status 가 주소를 보여 준다)
PURGE ?=

sonar-up: ## SonarQube 기동(VM Podman, 단독 모드) → http://<VM IP>:9000
	./scripts/sonar.sh up

sonar-down: ## SonarQube 내리기(메모리 회수, 분석 이력은 남는다). PURGE=1 이면 볼륨·VM 설정까지 지운다
	./scripts/sonar.sh down $(if $(filter 1,$(PURGE)),--purge)

sonar-status: ## SonarQube 실행 여부·접속 주소·메모리
	./scripts/sonar.sh status

sonar-scan: ## 분석: 백엔드(gradle JaCoCo)·프론트(vitest lcov + 스캐너 컨테이너) → 품질 게이트 실패면 non-zero (sonar-up 먼저)
	./scripts/sonar.sh scan

e2e: ## Playwright E2E: 로컬 스택(compose·백엔드·next dev)을 띄워 chromium 시나리오 실행 후 정리 (느림, make test 에 없음, 떠 있는 k3d 는 먼저 멈춘다). E2E_ARGS 로 인자 전달
	./scripts/e2e.sh $(E2E_ARGS)

# --- 배포 (TASK-17). CI(deploy.yml)와 같은 스크립트를 쓴다. 대상 값은 terraform output(infra/main)에서 읽는다(scripts/tf-outputs.sh).
# DRY_RUN=1 이면 aws·docker 명령을 출력만 한다. 예: make deploy DRY_RUN=1
TF_OUTPUTS := ./scripts/tf-outputs.sh --
SECRETS_FILE ?= deploy/secrets.local.txt
PUT_SECRETS_FLAGS ?=

deploy: deploy-backend deploy-frontend ## 운영 배포: 백엔드 이미지 → EC2 → 프론트 (CI 와 같은 스크립트)

deploy-backend: ## 백엔드 이미지 빌드·ECR push(arm64) + EC2 배포(SSM, 헬스·롤백)
	$(TF_OUTPUTS) ./scripts/build-backend.sh
	$(TF_OUTPUTS) ./scripts/deploy-backend.sh

deploy-frontend: ## 프론트 빌드 + S3 sync + CloudFront invalidation
	$(TF_OUTPUTS) ./scripts/deploy-frontend.sh

put-secrets: ## SSM /board/prod 에 앱 env 넣기 (SECRETS_FILE=deploy/secrets.local.txt, 처음엔 PUT_SECRETS_FLAGS=--generate-missing)
	./scripts/put-secrets.sh $(PUT_SECRETS_FLAGS) $(SECRETS_FILE)

tf-outputs: ## 배포 대상 값(terraform output) 보기
	./scripts/tf-outputs.sh

smoke: ## 배포 스모크(읽기 전용): SMOKE_BASE_URL=https://<cloudfront> make smoke (없으면 SKIP)
	./scripts/test/smoke_test.sh
