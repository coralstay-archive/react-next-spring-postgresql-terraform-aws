# repo 루트의 .env 가 있으면 읽어서 하위 명령(docker compose, gradlew)에 넘긴다. 없으면 기본값을 쓴다.
-include .env
export

.PHONY: help test dev-db dev-db-down backend-run backend-test backend-build frontend-install frontend-dev frontend-test frontend-build

help: ## 타깃 목록
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-18s %s\n", $$1, $$2}'

test: ## 리포·Terraform·백엔드·프론트 테스트 일괄 실행
	./scripts/test.sh

dev-db: ## 로컬 PostgreSQL 기동 (docker compose)
	docker compose up -d --wait postgres

dev-db-down: ## 로컬 PostgreSQL 중지
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
