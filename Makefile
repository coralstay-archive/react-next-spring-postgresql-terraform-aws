# repo 루트의 .env 가 있으면 읽어서 하위 명령(docker compose, gradlew)에 넘긴다. 없으면 기본값을 쓴다.
-include .env
export

.PHONY: help test dev-db dev-storage dev-db-down backend-run backend-test backend-build frontend-install frontend-dev frontend-test frontend-build \
	deploy deploy-backend deploy-frontend put-secrets tf-outputs smoke e2e

help: ## 타깃 목록
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-18s %s\n", $$1, $$2}'

test: ## 리포·Terraform·백엔드·프론트 테스트 일괄 실행
	./scripts/test.sh

dev-db: ## 로컬 PostgreSQL 기동 (docker compose)
	docker compose up -d --wait postgres

dev-storage: ## 로컬 첨부 저장소 MinIO 기동 + 버킷 준비 (http://localhost:9000, 콘솔 :9001)
	docker compose up -d --wait minio
	docker compose run --rm minio-init

dev-db-down: ## 로컬 PostgreSQL·MinIO 중지
	docker compose down

backend-run: ## 백엔드 실행 (http://localhost:8080/api)
	cd backend && ./gradlew bootRun

backend-test: ## 백엔드 테스트
	cd backend && ./gradlew test

backend-build: ## 백엔드 빌드 (bootJar)
	cd backend && ./gradlew build

frontend-install: ## 프론트 의존성 설치
	cd frontend && npm ci

frontend-dev: ## 프론트 개발 서버 (http://localhost:3000)
	cd frontend && npm run dev

frontend-test: ## 프론트 테스트
	cd frontend && npm test

frontend-build: ## 프론트 정적 export (frontend/out)
	cd frontend && npm run build

e2e: ## Playwright E2E: 로컬 스택(compose·백엔드·next dev)을 띄워 chromium 시나리오 실행 후 정리 (느림, make test 에 없음). E2E_ARGS 로 인자 전달
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
