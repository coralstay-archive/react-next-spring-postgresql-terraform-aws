# 게시판 (Next.js + Spring Boot + PostgreSQL + Terraform/AWS)

> 초안. 배포·외부 설정 문서는 이후 태스크(TASK-18)에서 채운다.

Next.js 정적 export(S3 + CloudFront)와 Spring Boot API(EC2 + Docker Compose, us-east-1),
PostgreSQL로 만드는 게시판이다. 전체 설계는 [`backlog/docs/doc-1-architecture-plan.md`](backlog/docs/doc-1-architecture-plan.md),
설계 결정은 [`backlog/decisions/`](backlog/decisions/)에 있다.

## 구성

| 디렉토리 | 내용 |
|---|---|
| `backend/` | Spring Boot 4.1 · Java 25 · Gradle Kotlin DSL. 모든 API는 `/api` 아래 (`server.servlet.context-path=/api`) |
| `frontend/` | Next.js 16 · App Router · `output: 'export'` · TypeScript · Tailwind CSS v4 · shadcn/ui |
| `docker-compose.yml` | 로컬 PostgreSQL 18 |
| `scripts/` | 로컬 `make`와 CI가 같이 호출하는 스크립트 (`scripts/test.sh` 등) |
| `backlog/` | backlog.md 태스크·결정·문서 |

`infra/`(Terraform), `deploy/`, `.github/workflows/`는 이후 태스크에서 추가한다.

## 준비물

- Java 25, Node.js 24, Docker (Docker Desktop 등)
- GNU Make

## 로컬 실행

```bash
# (선택) 환경변수 파일 — 복사하지 않아도 기본값으로 동작한다
cp example.env .env

make dev-db         # PostgreSQL 기동 (docker compose)
make backend-run    # 백엔드 http://localhost:8080/api
make frontend-dev   # 프론트 http://localhost:3000
```

백엔드 상태 확인:

```bash
curl http://localhost:8080/api/actuator/health
# {"status":"UP", ...}
```

DB 스키마는 Flyway(`backend/src/main/resources/db/migration`)가 관리한다.
끝나면 `make dev-db-down`으로 DB를 내린다(데이터는 `pgdata` 볼륨에 남는다).

## 테스트

```bash
make test   # = scripts/test.sh : 리포 검증 + 백엔드(./gradlew test) + 프론트(npm test)
```

- 백엔드: JUnit 5 + Testcontainers(PostgreSQL). **Docker가 떠 있어야 한다.**
- 프론트: Vitest + Testing Library.
- 리포: `scripts/test/repo_test.sh` (Makefile 타깃, hooks 설정, gitignore 등 골격 검증)

개별 실행: `make backend-test`, `make frontend-test`, 정적 export 빌드는 `make frontend-build`(결과물 `frontend/out/`).
전체 타깃 목록은 `make help`.

## 환경변수

`example.env` 참고. 실제 값은 git에 올리지 않는 `.env`에만 둔다. 운영 비밀값은 SSM Parameter Store에 두고
배포 스크립트가 EC2에서 주입한다(이후 태스크).

| 변수 | 기본값 | 용도 |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_PORT` | `board` / `board` / `board` / `5432` | 로컬 PostgreSQL 컨테이너 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/board` / `board` / `board` | 백엔드 DB 접속 |
| `JWT_SECRET` | 개발용 기본값(운영 금지) | 자체 서명 JWT HS256 키, 32바이트 이상이 아니면 기동 실패 |
| `COOKIE_SECURE` | `false` | `SESSION`·`XSRF-TOKEN` 쿠키 Secure 플래그(HTTPS 운영은 `true`) |

## 인증·인가 모델 (백엔드)

- **계정**: `users` 1 : N `user_identities(provider, subject)`. 비밀번호는 `local_credentials`에만 있다(decision-4).
- **하나의 보안 체인**(decision-5): `Authorization: Bearer <JWT>`가 있으면 JWT로 인증하고 세션은 읽지도 쓰지도 않는다.
  없으면 `SESSION` 쿠키(Spring Session JDBC, HttpOnly·SameSite=Lax)로 인증한다. 토큰이 틀리면 세션이 있어도 401.
- **CSRF**: 세션 요청의 POST/PUT/PATCH/DELETE는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 한다.
  SPA는 처음에 `GET /api/auth/csrf`로 쿠키를 받는다. Bearer 요청은 CSRF 면제.
- **오류**: 미인증 401, 권한 없음 403, 모두 JSON(`{"status","error","message"}`). 로그인 페이지 리다이렉트는 없다.
- **인가**: 기본은 인증 필요. 역할 `USER`/`ADMIN`은 `@PreAuthorize`, 수정·삭제는 `@ownership.canModify(authentication, ownerId)`
  (본인 또는 ADMIN). `SUSPENDED` 계정은 읽기만 되고 모든 쓰기가 403(DB 최신 상태 기준, 보안 체인에서 공통 처리).
- `/api/actuator/health`는 공개, 세부 정보(DB 등)는 ADMIN에게만.

## 개발 워크플로

이 리포는 [backlog.md](https://github.com/MrLesk/Backlog.md) 태스크 단위로 작업한다.
`.claude-code-agile-hooks.json`에 테스트 명령(`make test`)과 test-first 대상 경로(`testFirst`)가 설정되어 있다.
