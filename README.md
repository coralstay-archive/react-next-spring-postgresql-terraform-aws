# 게시판 (Next.js + Spring Boot + PostgreSQL + Terraform/AWS)

로그인, 글·댓글·좋아요, 검색, 이미지 첨부, 관리자 화면을 갖춘 게시판이다. 한 AWS 계정(us-east-1)에 Terraform으로 올린다.

- **프론트**: Next.js 16 정적 export → S3 + CloudFront
- **백엔드**: Spring Boot 4 (Java 25) + PostgreSQL 18 → EC2 한 대의 Docker Compose. CloudFront가 `/api/*`를 넘긴다
- **로그인**: 이메일·비밀번호, 휴대폰 OTP(End User Messaging SMS), Google·GitHub·Naver·Kakao·Cognito. 계정 하나에 여러 수단을 연결할 수 있다
- **배포**: `make deploy`(로컬)와 GitHub Actions(main push, OIDC)가 같은 스크립트를 쓴다

전체 설계는 [`backlog/docs/doc-1-architecture-plan.md`](backlog/docs/doc-1-architecture-plan.md), 설계 결정은 [`backlog/decisions/`](backlog/decisions/)에 있다.

<!--dot:architecture-->
<p align="center"><img src="docs/diagrams/architecture.svg" alt="아키텍처 — 브라우저 → CloudFront(정적 S3·/api EC2·/uploads S3) → EC2 Docker Compose(Spring Boot + PostgreSQL), ECR·SSM·CloudWatch·Cognito·End User Messaging·외부 IdP"></p>
<!--/dot:architecture-->

## 빠른 시작

```bash
cp example.env .env                   # (선택) 로컬 env — 이미지 첨부(UPLOADS_*)·ADMIN_EMAIL 을 쓰려면 필요
make frontend-install                 # 프론트 의존성 (처음 한 번. 이후 frontend-*·test·e2e 가 lock 이 바뀌면 알아서 npm ci)
make dev-db dev-storage               # PostgreSQL + MinIO (docker compose)
make backend-run                      # 터미널 1: http://localhost:8080/api
make frontend-dev                     # 터미널 2: http://localhost:3000
```

<http://localhost:3000/signup/>에서 가입한다. 관리자가 필요하면 그 이메일로 가입한 뒤 `.env`의 `ADMIN_EMAIL`을 채우고 백엔드를 다시 시작한다
(가입만으로는 관리자가 되지 않고, ADMIN이 하나도 없을 때 기동하면서 이미 있는 그 LOCAL 계정을 한 번 ADMIN으로 올린다. 승격되면 비운다).
휴대폰 로그인의 인증번호는 백엔드 로그(`[SMS console] to=+82******5678 message=...`, 번호는 마스킹)에 찍힌다. 테스트는 `make test`, 브라우저 E2E는 `make e2e`로 돌린다.

| 문서 | 내용 |
|---|---|
| [docs/first-deploy.md](docs/first-deploy.md) | 첫 배포 런북: 자격 증명 → bootstrap → apply → 시크릿 → 배포 → 스모크 → GitHub Actions → 롤백 시험 → 정리 |
| [docs/oauth-setup.md](docs/oauth-setup.md) | 소셜 로그인 콘솔 설정(콜백 URL, 동의 항목)과 확인 체크리스트 |
| [docs/sms-setup.md](docs/sms-setup.md) | End User Messaging SMS: 샌드박스, 한국 발신, 지출 한도 |
| [docs/api.md](docs/api.md) | 백엔드 API 레퍼런스(인증·게시판·댓글·검색·첨부·관리자) |
| [docs/frontend.md](docs/frontend.md) | 프론트 라우트·상태·`data-testid` |
| [diagrams/](diagrams/) | 다이어그램 원본(DOT) |

## 구성

| 디렉토리 | 내용 |
|---|---|
| `backend/` | Spring Boot 4.1 · Java 25 · Gradle Kotlin DSL. 모든 API는 `/api` 아래 (`server.servlet.context-path=/api`) |
| `frontend/` | Next.js 16 · App Router · `output: 'export'` · TypeScript · Tailwind CSS v4 · shadcn/ui. E2E는 `frontend/e2e/`(Playwright) |
| `docker-compose.yml` | 로컬 PostgreSQL 18 + 첨부 저장소 MinIO(S3 호환, TASK-11) |
| `infra/` | Terraform: `infra/bootstrap`(state 버킷), `infra/main`(레거시 운영 루트 스택 + `modules/*`), `infra/platform`(실배포 설계 — apply 전). [인프라](#인프라-terraform-task-16)·[AWS 실배포 설계](#aws-실배포-설계-task-65-decision-11) 참고 |
| `deploy/` | EC2에서 쓰는 compose·배포 스크립트, 시크릿 입력 예시. [배포](#배포-task-17) 참고 |
| `.github/workflows/` | `ci.yml`(테스트·빌드), `deploy.yml`(main 배포) |
| `scripts/` | 로컬 `make`와 CI가 같이 호출하는 스크립트 (`scripts/test.sh`, 배포, `e2e.sh`, `build-diagrams.py`) |
| `diagrams/` → `docs/diagrams/` | DOT 원본 → 렌더된 SVG(생성물) |
| `backlog/` | backlog.md 태스크·결정·문서 |

## 준비물

- 맥: UTM + `qemu-img` — 컨테이너·테스트는 Rocky 개발 VM 안에서 돈다([로컬 개발 환경](#로컬-개발-환경-rocky-개발-vm-task-64)). VM 밖(리눅스·CI)에서는 Java 25, Node.js 24, Docker 또는 Podman 소켓
- GNU Make
- (배포) aws CLI v2, Terraform ≥ 1.10, jq, docker buildx — [docs/first-deploy.md](docs/first-deploy.md#0-준비물)
- (선택) graphviz(`make diagrams`), shellcheck·actionlint(`make test`가 있으면 쓴다)
- (로컬 k8s) k3d ≥ 5.9, kubectl(kustomize 내장), kubeconform(`make test`의 매니페스트 검증 — 없으면 경고 후 건너뛴다, CI 는 필수). [로컬 k8s](#로컬-k8s-k3d-task-36)

## 로컬 개발 환경 (Rocky 개발 VM, TASK-64)

맥에는 컨테이너 런타임을 두지 않는다(Docker 삭제, [doc-4](backlog/docs/)). 컨테이너·테스트·(TASK-36 이후) k3s 는 UTM 의 Rocky Linux 9 VM
`rocky-dev` 안에서 Podman 으로 돈다. AWS 의 Rocky EC2(TASK-52)와 같은 설치 스크립트([`deploy/rocky/install.sh`](deploy/rocky/install.sh))를 쓴다.

준비물(맥): [UTM](https://mac.getutm.app) 4.x, `qemu-img`(`brew install qemu` — qcow2 → raw 변환에만 쓴다). 나머지 도구(Java 25·Node 24·kubectl·
kubeconform·yq·shellcheck·actionlint·terraform·clang/llvm/libbpf·bpftool)는 VM 안에 고정 버전으로 설치된다.

```bash
make vm-create   # 처음 한 번: 이미지 받기·검증 → UTM VM 만들기 → 켜기 → cloud-init 설치(10분쯤)
make vm-start    # 켜기 + IP 를 읽어 ~/.ssh/config.d/rocky-dev 갱신 (재부팅 뒤 IP 가 바뀌어도 이걸로 맞춘다)
make vm-stop     # 끄기(게스트 정상 종료, 메모리 5GB 회수)
make vm-status   # 상태·IP·메모리(VM·맥)
make vm-ssh      # = ssh rocky-dev (저장소는 VM 안 /repo)
make test        # 맥에서 실행해도 VM 안에서 돈다: rsync(작업 트리 → VM /repo) 뒤 ssh rocky-dev 'cd /repo && bash -lc "make test"'
```

`make vm-create`(= `scripts/vm.sh create`) 순서:
1. VM `rocky-dev`가 이미 있으면 만들지 않고 안내하며 실패한다.
2. 전용 SSH 키 `~/.ssh/rocky-dev_ed25519`가 없으면 만든다(비밀번호 없음, 이 VM 에만 쓴다).
3. Rocky 9.8 aarch64 GenericCloud 이미지를 `~/.cache/rocky-dev/`(저장소 밖 캐시)에 받고, 스크립트에 고정한 sha256 과 비교한다. Rocky 의
   `CHECKSUM` 파일에도 그 값이 있는지 본다. 다르면 지우고 실패한다.
4. Apple 가상화는 raw 디스크만 쓰므로 `qemu-img`로 raw 변환 + 40GB 로 늘린다(희소 파일 — 실제 디스크는 쓴 만큼만).
5. cloud-init seed ISO(`hdiutil makehybrid … -default-volume-name cidata`)를 만든다: 사용자 `rocky`(맥과 같은 uid — VirtioFS 공유 파일에 쓰려면
   같아야 한다, 비밀번호 로그인 없음, NOPASSWD sudo), 공개키, `install.sh --profile dev`(seed 안에 그대로 넣는다).
6. **macOS 자동화 권한 창이 한 번 뜬다** — "터미널이 UTM 을 제어하도록 허용" → [허용]. 스크립트가 실행 직전에 안내한다. 이 프로젝트에서 맥 권한 창이
   뜨는 곳은 여기뿐이고 관리자 비밀번호는 묻지 않는다. 거절했다면 시스템 설정 → 개인정보 보호 및 보안 → 자동화에서 UTM 을 켜고 다시 실행한다.
   그 뒤 [`deploy/dev-vm/create-vm.applescript`](deploy/dev-vm/create-vm.applescript)가 UTM 에 VM 을 만든다: Apple 가상화, 메모리 5GB(5120MiB),
   CPU 4, 디스크 40GB, NAT. 디스크와 seed 는 VM 안으로 복사된다. 저장소 공유 디렉터리는 붙이지 않는다 — UTM 은 샌드박스 앱이라 AppleScript 로
   받은 경로(이동식 ISO·공유 디렉터리)의 접근 권한을 남기지 못해 다음 시작이 "Cannot access resource"로 실패한다(UTM 4.7.5 실측).
7. VM 을 켜고, IP(utmctl → 안 되면 맥의 DHCP 임대 파일)로 ssh 설정을 쓰고, `cloud-init status --wait`로 설치가 끝날 때까지 기다린다.

ssh 설정: `~/.ssh/config`에는 맨 위에 `Include config.d/rocky-dev` **한 줄만** 더한다(처음 한 번, 먼저 `~/.ssh/config.bak-rocky-dev-<시각>`으로 백업).
`Host rocky-dev`(IP·사용자·키·전용 known_hosts `~/.ssh/known_hosts.rocky-dev`)는 `~/.ssh/config.d/rocky-dev`에 있고 `make vm-start`가 다시 쓴다.

VM 안(install.sh `--profile dev`): Podman + 사용자 소켓(`/run/user/<uid>/podman/podman.sock`, linger 로 부팅 때 켜짐, API 서비스는 유휴 종료
없음 `--time=0` — 기본 5초 종료가 Testcontainers 연결을 끊었다). rootless 용 subuid·subgid 범위도 준다. 로그인 셸에서 `DOCKER_HOST`가 그 소켓을
가리키고 Testcontainers 의 Ryuk 은 privileged(rootless Podman 에 필요).

저장소를 VM 에 맞추는 법: 맥의 `make test`(등)는 매번 `rsync -a --delete`로 작업 트리(커밋 안 된 변경·`.git` 포함)를 VM 로컬 `/repo`에 맞춘 뒤
실행한다. `node_modules`·`backend/build`·`.gradle`·`.terraform`처럼 플랫폼이 다른 산출물은 보내지 않고 VM 쪽 것을 그대로 쓰며(네이티브 모듈 darwin ↔ linux),
`.env`·`deploy/*.local.txt` 같은 비밀 파일도 보내지 않는다. 한 방향(맥 → VM)이라 VM 안에서 고친 `/repo` 파일은 다음 동기화 때 덮인다.
- (선택) 실시간 공유: UTM 창에서 `rocky-dev` → 편집 → 공유 → 디렉터리로 저장소를 직접 고르면(파일 선택 창이라 UTM 이 접근 권한을 기억한다)
  VirtioFS(태그 `share`)가 VM `/mnt/share`에 마운트된다. UTM 의 Apple Virtualization 백엔드는 디렉터리 공유를 "여러 디렉터리 공유"로 내보내서
  `/mnt/share` 바로 아래에 고른 맥 폴더 이름의 디렉터리(`/mnt/share/react-next-spring-postgresql-terraform-aws`)가 하나 생긴다 — 설치 스크립트가
  그 디렉터리(태그 루트에 `.git`·`Makefile`이 있으면 루트)를 `/repo`로 bind 하고(fstab, 부팅 때도), 스크립트가 이를 알아보고 rsync 를 건너뛴다.
  저장소(`.git`·`Makefile`)를 고를 수 없거나(하위 디렉터리 여럿) `/repo`를 내리지 못하면(사용 중) fstab·마운트를 바꾸지 않고 실패한다 — 공유는 저장소 폴더 하나만 고른다. 이때
  `frontend/node_modules`·`backend/build`·`backend/.gradle`을 VM 로컬 디렉터리로 bind 해서 덮는다(맥의 darwin `node_modules`·빌드 산출물과
  섞이지 않게). VM 을 만든 뒤에 공유를 붙였다면 `/repo` bind 와 덮기는 아직 없다 — 공유를 붙이고 VM 을 다시 켠 뒤 한 번 설치 스크립트를 다시 돌린다
  (cloud-init 이 VM 안에 남겨 둔 스크립트, 다시 돌려도 된다. 옛 배치 — 태그를 `/repo`에 바로 마운트한 fstab 줄 — 도 이것으로 옮겨진다):
  `ssh rocky-dev sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo`.
  bind 가 빠져 있으면 맥의 `make test`가 이 명령을 경고로 안내한다.
- 동시 실행 잠금: VM 안 실행과 rsync 는 `/tmp/board-repo-test.lock`(flock)을 잡는다. 다른 실행이 돌고 있으면 기다리지 않고 바로 실패한다
  (rsync 가 실행 중인 gradle 아래의 `/repo`를 바꾸지 않게). 맥 쪽 호출이 취소되거나 시간 초과로 죽어 ssh 가 끊기면, VM 안 감시 루프가
  남은 명령(프로세스 그룹)을 멈추고 잠금을 푼다.
- git worktree(`.claude/worktrees/<이름>`): worktree 의 `.git` 파일에는 맥 절대 경로(`gitdir: /Users/…/.git/worktrees/<이름>`)가 적혀 있다.
  스크립트가 VM 에 그 맥 저장소 경로 → `/repo` 심볼릭 링크를 만들고(VM 안 sudo, 맥은 건드리지 않는다) 공통 `.git`도 VM `/repo/.git`에 맞춘 뒤
  worktree 를 `/repo/.claude/worktrees/<이름>`에 동기화하므로 VM 안 git 이 그대로 동작한다. 공통 `.git`이 저장소 밖이면 거부한다.

**SELinux**: VM 은 enforcing 그대로다(설치 스크립트가 enforcing 이 아니면 실패한다 — 끄지 않는다). 공유(`/mnt/share` → `/repo`)는
`context=system_u:object_r:container_file_t:s0`로 마운트해서(선택 공유를 붙인 경우) Podman 볼륨으로 써도 거부되지 않는다. VM 로컬 디렉터리를 컨테이너에 마운트할 때는
`-v 경로:경로:Z`처럼 레이블을 붙인다. 거부가 의심되면 VM 안에서 `sudo ausearch -m avc -ts recent`.

**메모리 규칙**(8GB 맥, 스크립트가 강제한다): VM 은 5GB 를 가져간다. 맥의 `make test`·`test-integration`·`backend-test`·`backend-build`와
`k3d-up`·`k3d-profile`·`k3d-smoke`는 먼저 같은 사전 검사(`scripts/_vm.sh vm_preflight`)를 지난다.
- VM 이 꺼져 있으면 **스스로 켜지 않고** `make vm-start`를 안내하며 실패한다(언제 5GB 를 쓸지는 사람이 정한다).
- VM 메모리(VM 안 `/proc/meminfo`) < `vm-min`(4500 MiB)이면 실패(5GB VM 의 실측 MemTotal 4649 MiB 보다 약간 낮게 둔다), 맥 여유 메모리(`vm_stat`) < `mac-free-min`(512 MiB)이면 경고.
  기준값은 [`deploy/k8s/budgets.txt`](deploy/k8s/budgets.txt).
- 쓰지 않을 때는 `make vm-stop`.

**다시 만들기**: UTM 에서 `rocky-dev`를 삭제한다(VM 안 데이터·Podman 이미지가 모두 지워진다 — 저장소는 맥에 있으니 그대로). 그 뒤 `make vm-create`.
이미지는 캐시에 남아 다시 받지 않는다. 새 VM 은 호스트 키가 바뀌므로 스크립트가 `~/.ssh/known_hosts.rocky-dev`를 지운다.

`make test`를 맥에서 돌리는 방식은 "VM 안에서 실행"이다(맥 → VM 소켓 터널 대신, TASK-64#5). 이유: 맥에 컨테이너 런타임·도구가 없어도 되고, 테스트 도구 버전이
VM·CI·AWS(Rocky)에서 같은 고정값이며, Testcontainers 가 컨테이너 포트에 localhost 로 붙어 터널·호스트 덮어쓰기가 필요 없다. 커밋 훅의 `make test`도
같은 길을 탄다. CI(ubuntu)와 VM 안에서는 그대로 실행한다. 맥에 다른 런타임이 있어 직접 돌리려면 `VM_PROXY=0 make test`.

## 로컬 실행

```bash
# (선택) 환경변수 파일 — 복사하지 않아도 기본값으로 동작한다
cp example.env .env

make dev-db         # PostgreSQL 기동 (docker compose)
make dev-storage    # (선택) 첨부 저장소 MinIO 기동 + 버킷 준비 — 이미지 첨부를 쓸 때
make backend-run    # 백엔드 http://localhost:8080/api
make frontend-dev   # 프론트 http://localhost:3000
```

`.env`는 로컬 실행 타깃(`dev-db`, `dev-storage`, `dev-db-down`, `backend-run`, `frontend-dev`)의 레시피 안에서만 읽는다(`scripts/local-env.sh`, 셸 문법 —
값에 공백·특수문자가 있으면 따옴표로 감싸고, 문법 오류면 타깃이 실패한다). 이미 정의된 변수(환경변수, make 명령줄 변수)는 덮지 않는다 —
`make dev-db POSTGRES_PORT=5433`이나 `POSTGRES_PORT=5433 make dev-db`가 `.env` 값보다 우선한다.
배포(`deploy*`, `put-secrets`, `tf-outputs`)·`smoke`·`test`·`e2e`에는 섞이지 않는다 — `.env`의
`AWS_PROFILE`·`DRY_RUN` 같은 값이 배포 대상이나 명령줄로 준 `DRY_RUN=1`을 바꾸지 않게(TASK-35#4).

프론트 의존성: `make frontend-dev`·`frontend-test`·`frontend-build`, `make test`, `make e2e`는 먼저 `scripts/frontend-deps.sh`를 부른다.
`package.json`·`package-lock.json`의 해시가 `frontend/node_modules/.deps-stamp`와 다르거나 `node_modules`가 없으면 `npm ci`를 돌린다
(브랜치를 바꿔 lock이 달라졌을 때 옛 `node_modules`로 돌지 않게, TASK-35#3). `make frontend-install`은 해시와 상관없이 `npm ci`.
`npm ci`는 `node_modules`를 지우고 다시 만들므로 그 전에 안내를 출력한다(실행 중인 `next dev`·vitest가 깨질 수 있다). 두 make가 동시에
`npm ci`를 돌리지 않게 `frontend/.deps-lock`(PID 기록) 잠금을 잡고, 기다린 쪽은 앞 실행이 설치를 끝냈으면 건너뛴다. 죽은 PID가 남긴 잠금은
치우고, `FRONTEND_DEPS_LOCK_TIMEOUT`(초, 기본 600)을 넘게 기다리면 잡고 있는 PID를 알리며 실패한다.

백엔드 상태 확인:

```bash
curl http://localhost:8080/api/actuator/health
# {"status":"UP", ...}
```

프론트 개발 서버(`next dev`)는 `/api/*`를 백엔드(`BACKEND_URL`, 기본 `http://localhost:8080`)로 프록시한다(`frontend/next.config.ts`의 `rewrites`).
브라우저는 운영(CloudFront가 `/api/*`를 EC2로 보냄)처럼 한 오리진(`http://localhost:3000`)만 보므로 `SESSION`·`XSRF-TOKEN` 쿠키,
CSRF, 소셜 로그인 리다이렉트가 그대로 동작한다. 백엔드의 `PUBLIC_BASE_URL` 기본값 `http://localhost:3000`이 이 구성을 전제로 한다.

```bash
curl -i http://localhost:3000/api/auth/csrf   # 프록시 경유: 200 + Set-Cookie: XSRF-TOKEN=...; Path=/; SameSite=Lax
```

`next build`(= `make frontend-build`)는 정적 export(`frontend/out/`)라 rewrites가 없다. 개발 서버에서만 trailing slash 리다이렉트를 꺼서
`/api/auth/csrf`가 `/api/auth/csrf/`로 바뀌지 않게 한다.

DB 스키마는 Flyway(`backend/src/main/resources/db/migration`)가 관리한다.
끝나면 `make dev-db-down`으로 DB와 MinIO를 내린다(데이터는 `pgdata`·`miniodata` 볼륨에 남는다).

로컬 첨부(MinIO): `make dev-storage`가 MinIO(API `http://localhost:9000`, 콘솔 `http://localhost:9001`, 계정 `minioadmin`/`minioadmin`)를
띄우고 one-shot 컨테이너 `minio-init`(mc)이 버킷 `board-uploads`를 만든 뒤 `uploads/` 아래를 익명 읽기로 연다.
백엔드는 `example.env`의 `UPLOADS_*` 값(복사한 `.env`)으로 MinIO를 쓴다. 이 값이 없으면(`UPLOADS_BUCKET` 비어 있음) 앱은 뜨지만
첨부 API는 503 `uploads_unavailable`이다. 로컬에서 첨부 `url`은 `UPLOADS_PUBLIC_BASE_PATH`(`http://localhost:9000/board-uploads/uploads/`)
기준이라 브라우저로 바로 열린다. CORS는 MinIO 전역 설정 `MINIO_API_CORS_ALLOW_ORIGIN`(= `PUBLIC_BASE_URL`, 기본 `http://localhost:3000`)으로
준다(MinIO는 버킷 CORS API를 구현하지 않는다). Docker Hub의 `minio/minio` 이미지는 더 이상 받을 수 없어 `cgr.dev/chainguard/minio`를 digest로 고정해 쓴다.

소셜 로그인을 로컬에서 쓰려면 제공자 콘솔에 `http://localhost:3000/api/login/oauth2/code/{id}`를 등록하고 id·secret을 `.env`에 넣는다([docs/oauth-setup.md](docs/oauth-setup.md)).

## 로컬 k8s (k3d, TASK-36)

로컬 전용 플랫폼(doc-4): k3d(k3s) 단일 노드 + 로컬 레지스트리 + 프로필. 8GB M1 에서 **Docker Desktop 메모리 5GB 이상**이 전제다.
들어오는 길은 호스트 `127.0.0.1:80` → k3d LB 컨테이너 → Traefik(k3s 기본 인그레스) → `/api` → backend.

```bash
make k3d-up                       # 클러스터 생성/시작 + core(PostgreSQL·MinIO·backend). PROFILES=core,... FORCE=1 SKIP_BUILD=1
make k3d-smoke                    # curl -H 'Host: board.localhost' http://127.0.0.1/api/actuator/health → UP
make k3d-profile PROFILES=core    # 고른 프로필만 남기고 나머지는 삭제(메모리 회수)
make k3d-stop                     # 클러스터·레지스트리 정지(데이터 유지). 다시: make k3d-up
make k3d-down                     # 클러스터 삭제(데이터까지)
```

`make k3d-up`(= `scripts/k3d.sh up`) 순서: 사전 검사 → 백엔드 이미지 빌드(`localhost:5001/board-backend:dev`, 호스트 아키텍처 = arm64) →
클러스터 생성(`deploy/k8s/k3d/cluster.yaml`: 서버 1·에이전트 0, metrics-server 끔, 레지스트리 `k3d-registry.localhost:5001`) 또는 시작 →
레지스트리 push → `deploy/k8s/base`(네임스페이스 `board`) → Secret `board-env`(env 파일 값으로 스크립트가 만든다 — 리포에 없다) →
프로필 적용(`deploy/k8s/profiles/<이름>`, 고르지 않은 프로필은 `kubectl delete -k`) → Job 완료·롤아웃 대기. kubectl 은 항상
`--context k3d-board`로 부르고 현재 컨텍스트는 바꾸지 않는다. 브라우저: `http://board.localhost/api/actuator/health`.

- 마이그레이션: Flyway 는 앱 기동과 분리한 Job `board-migrate`가 한다(백엔드 이미지의 `MigrationJob migrate`, Spring 없이). 백엔드는 k8s 프로필
  (`application-k8s.properties`: `spring.flyway.enabled=false`)로 뜨고, 초기화 컨테이너(`MigrationJob await`)가 마이그레이션이 끝날 때까지 기다린다.
  docker-compose·테스트·AWS 는 지금처럼 기동 시 마이그레이션한다.
- 프로필을 끄면 그 프로필의 리소스가 지워져 메모리가 돌아온다. StatefulSet 의 PVC(데이터)와 네임스페이스는 남는다(`make k3d-down`이 지운다).
- Secret 값(env 파일의 `POSTGRES_*`·`MINIO_ROOT_*`·`JWT_SECRET`·`OTP_HASH_KEY`)은 `make k3d-up`·`k3d-profile`마다 다시 넣는다. 단 PostgreSQL 은
  PVC 가 처음 만들어질 때 한 번만 initdb 하므로, PVC 가 생긴 뒤 `POSTGRES_PASSWORD`(·`POSTGRES_USER`·`POSTGRES_DB`)를 바꾸면 DB 쪽 값은 그대로이고
  백엔드·마이그레이션이 인증에 실패한다. 로컬 데이터를 버려도 되면 `make k3d-down` → `make k3d-up`(클러스터·PVC 를 지우고 새로 initdb)으로 초기화하고,
  데이터를 남기려면 env 파일 값을 원래대로 되돌린다. UPLOADS_BUCKET 같은 비밀이 아닌 설정은 env 파일이 아니라 ConfigMap
  (`deploy/k8s/profiles/core/kustomization.yaml`)에 고정이다.
- 매니페스트 검증: `make test`가 `scripts/k8s-validate.sh`로 base·모든 프로필을 `kubectl kustomize` → `kubeconform -strict` 검증한다(오프라인 —
  스키마는 `deploy/k8s/schemas/`에 고정, k8s 1.36). 새 종류를 쓰면 `scripts/k8s-validate.sh --update-schemas`로 받아 커밋한다(yannh/kubernetes-json-schema 를 움직이는
  `master`가 아니라 스크립트의 `K8S_SCHEMA_REF` 커밋 SHA 에서 받는다 — 같은 입력이면 같은 파일. 올릴 때는 SHA 를 바꾸고 받은 diff 를 리뷰한다). CRD 는 스크립트 머리말 참고.

### 메모리 규칙 (스크립트가 강제한다)

사람의 기억에 맡기지 않는다 — 아래는 `scripts/_k3d.sh`가 검사하고, 어기면 이유와 해결법을 출력하고 실패한다.

| 규칙 | 어디서 | 어기면 |
|---|---|---|
| Docker VM 메모리 ≥ 4608 MiB(Docker Desktop 5GB 설정의 실측 ≈ 4.8GiB, 4GB 설정은 ≈ 3.8GiB) | `k3d-up`·`k3d-profile`·`k3d-smoke` | 실패: Settings → Resources → Memory 5GB |
| Testcontainers 컨테이너(label `org.testcontainers=true`)가 없을 것 | `k3d-up`·`k3d-profile`·`k3d-smoke` | 실패: 테스트가 끝나길 기다리거나 남은 컨테이너 삭제 |
| 켠 프로필 예산 합계 ≤ 상한 3072 MiB | `k3d-up`·`k3d-profile`·`k3d-smoke`(켜진 프로필) | 실패. `FORCE=1`이면 경고를 남기고 진행(이 검사만) |
| k3d 클러스터와 테스트를 같이 띄우지 않는다 | `make test`(시작 + 백엔드 직전)·`make test-integration`·`make backend-test`·`make backend-build`·`make e2e` 시작 | 떠 있는 클러스터를 멈추고 그 사실을 출력한 뒤 테스트 |

세 진입점(up·profile·smoke)은 같은 함수 `k3d_preflight` 하나를 먼저 부른다 — 검사를 건너뛰는 진입점이 없다. 예산 숫자는
[`deploy/k8s/budgets.txt`](deploy/k8s/budgets.txt) 하나에 있고 스크립트와 이 표가 그 값을 쓴다(테스트가 같은지 확인한다). 프로필 예산은
그 프로필 매니페스트의 메모리 한도 합계(Deployment·StatefulSet × replicas + Job)이고, `make test`가 매니페스트에서 다시 계산해 예산 이하인지 본다.
모든 컨테이너(초기화 컨테이너 포함)에 requests·limits 가 있고, backend 는 롤아웃 중 두 벌이 뜨지 않게 `Recreate`다.

| 프로필 | 예산 | 내용 |
|---|---|---|
| core | 1600 MiB | PostgreSQL 256(shared_buffers 64MB) + MinIO 256 + backend 768(-Xmx384m) + 마이그레이션 Job 256 + 버킷 준비 Job 64 |

상한 3072 MiB = 5GB VM − k3s 서버·Traefik·CoreDNS·local-path·LB·레지스트리(약 0.9GiB) − 이미지 빌드·여유(약 0.9GiB).
이후 태스크가 프로필(gitops·protocols·delivery·shadow·ops)을 더할 때 `budgets.txt`에 줄을, `deploy/k8s/profiles/<이름>/`에 매니페스트를 같이 만든다.

## 코드 품질 (SonarQube, TASK-67)

SonarQube Community Build 를 Rocky 개발 VM 안 Podman 에 **단독**으로 띄워 분석하고, IDE 는 SonarQube for IDE(옛 SonarLint) 연결 모드로 붙인다.
서비스는 [`deploy/sonar/compose.yaml`](deploy/sonar/compose.yaml)(SonarQube 26.10 Community·postgres:18-alpine, 태그@sha256 고정, DB 포트 비공개, 이름 있는 볼륨).
9000 은 VM 의 `127.0.0.1` 에만 연다 — 처음 기동 뒤 관리자 비밀번호를 바꾸기 전(admin/admin)에도 VM 밖(같은 네트워크)에서 붙지 못한다.
맥에서는 ssh 터널로 `http://localhost:9000` 에 붙는다(`make sonar-status` 가 안내한다). 비밀번호·토큰을 맞추지 못하면 sonar-up 이 SonarQube 를 멈추고 실패한다.

```bash
make sonar-up       # 사전 검사 → podman compose up → UP 대기 → (처음) 관리자 비밀번호·분석 토큰
ssh -N -L 9000:localhost:9000 rocky-dev   # 맥 브라우저·IDE 용 터널(9000 은 VM 루프백에만 열린다) → http://localhost:9000
make sonar-scan     # 백엔드(gradle JaCoCo XML + org.sonarqube) → 프론트(vitest lcov + 스캐너 컨테이너), 품질 게이트 실패면 non-zero
make sonar-status   # 실행 여부·접속 주소·컨테이너 메모리
make sonar-down     # 내리기(메모리 회수, 분석 이력은 남는다). make sonar-down PURGE=1 은 볼륨·VM 설정까지 지운다
```

- **단독 모드(스크립트가 강제한다)**: `make sonar-up` 은 k3s·k3d 실행 중, make test 잠금(`/tmp/board-repo-test.lock`)·Testcontainers 컨테이너,
  VM 사용 가능 메모리(MemAvailable) < 2600 MiB(`deploy/k8s/budgets.txt` 의 `sonar`)이면 이유·해결을 출력하고 실패한다. 반대로
  `make test`·`test-integration`·`backend-test`·`backend-build`·`e2e`·`k3d-up`·`k3d-profile`·`k3d-smoke` 는 SonarQube 가 떠 있으면(또는 확인하지 못하면) `make sonar-down` 을 안내하고 실패한다 —
  **커밋 훅의 make test 도 막히므로 분석이 끝나면 내린다**. 힙은 web·ce·search 모두 `-Xmx384m`(기본 512m), 기동 직후 실측
  SonarQube + DB 약 2000 MiB(`sonar-rss`). `make sonar-scan` 은 SonarQube 를 띄운 채 gradle test(Testcontainers)를 돌리므로 시작할 때
  남은 Gradle 데몬을 내리고 `--no-daemon` 으로 돈다.
- **커널 값**: 내장 Elasticsearch 가 `vm.max_map_count` ≥ 262144 를 요구한다(기본 65530 이면 부트스트랩 검사로 곧바로 멈춘다 — sonar-up 이
  원인을 알린다). `deploy/rocky/install.sh` dev 프로필이 `/etc/sysctl.d/99-sonarqube.conf`(vm.max_map_count = 524288, fs.file-max 는 커널 값이
  131072 보다 작을 때만 131072)와 `/etc/security/limits.d/99-sonarqube.conf`(nofile 131072)를 쓴다. 기존 VM 에는 한 번
  `ssh rocky-dev 'sudo install -m 0755 /repo/deploy/rocky/install.sh /usr/local/sbin/rocky-install.sh && sudo /usr/local/sbin/rocky-install.sh --profile dev --user rocky --share-tag share --share-path /repo'`
  (podman compose 공급자 docker-compose 플러그인도 이때 설치된다).
- **관리자 비밀번호·토큰(저장소·`.env` 에 두지 않는다)**: 처음 `make sonar-up` 이 API 로 관리자 비밀번호를 무작위 값으로 바꾸고 분석 토큰
  (GLOBAL_ANALYSIS_TOKEN)을 만들어 VM 의 `~/.config/board-sonar/`(0700, 파일 0600)에만 둔다. 출력하지 않고 curl 인자에도 싣지 않는다.
  UI 로그인은 `admin` / 비밀번호 보기: `ssh rocky-dev cat ~/.config/board-sonar/admin-password`. 볼륨이 지워져 서버가 admin/admin 으로 돌아가면
  저장된 비밀번호로 다시 바꾸고, 토큰이 무효면 새로 만든다. UI 에서 비밀번호를 바꿨다면 그 파일을 고치거나 `make sonar-down PURGE=1` 로 처음부터.
- **품질 게이트**: 기본 "Sonar way"(새 코드 기준). 새 코드 기준은 `PREVIOUS_VERSION`(sonar-up 이 설정) — 버전이 그대로라 첫 분석이 기준선이 되고
  그 뒤 바뀐 코드만 게이트에 걸린다(첫 분석은 기존 부채로 막히지 않는다). 프로젝트는 `board-backend`(`backend/build.gradle.kts` 의 `sonar {}`)·
  `board-frontend`(`frontend/sonar-project.properties`). 커버리지: JaCoCo 에이전트는 `-Pcoverage` 일 때만 붙고(평소 make test 에는 없다),
  프론트는 `npm run test:coverage`(@vitest/coverage-v8, lcov).
- **스캐너 컨테이너**: 공식 `sonarsource/sonar-scanner-cli` 이미지는 amd64 뿐이라 VM(arm64)에서 돌지 않는다. [`deploy/sonar/scanner/Containerfile`](deploy/sonar/scanner/Containerfile)
  이 다중 아키텍처 JRE 베이스(다이제스트 고정)에 Maven Central 의 스캐너 jar(sha256 고정)를 얹어 처음 한 번 빌드한다(CI 도 같다).
- **IDE 연결 모드(SonarQube for IDE)**: `.sonarlint/connectedMode.json`(board-backend)·`.sonarlint/frontend.json`(board-frontend)을 커밋했다.
  주소는 `http://localhost:9000` — VM IP 는 DHCP 로 바뀔 수 있어서 맥에서 ssh 터널을 연다: `ssh -N -L 9000:localhost:9000 rocky-dev`.
  토큰은 IDE 가 따로 쓴다(UI → My Account → Security 에서 User Token 을 만든다 — 분석 토큰과 별개, 저장소에 두지 않는다).
  - VS Code: 확장 "SonarQube for IDE" 설치 → 저장소를 열면 `.sonarlint` 설정을 찾아 "Use Configuration" 을 묻는다 → 고르고(백엔드·프론트 중 하나,
    멀티 루트 워크스페이스면 폴더마다) SonarQube Server 연결을 `http://localhost:9000` + 토큰으로 만든다.
  - IntelliJ: 플러그인 "SonarQube for IDE" 설치 → 프로젝트를 열면 공유 설정으로 바인딩을 제안한다 → Settings → Tools → SonarQube for IDE 에서
    연결(`http://localhost:9000`, 토큰)을 만들고 프로젝트(모듈)를 `board-backend`(프론트 모듈은 `board-frontend`)로 묶는다.
- CI(`.github/workflows/sonar.yml`): PR 마다 서비스 컨테이너로 같은 다이제스트의 SonarQube·Postgres 를 띄우고 `scripts/sonar.sh init`(실행마다
  무작위 관리자 비밀번호·토큰, 로그 마스킹) → `scripts/sonar.sh scan`(게이트). secret 이 필요 없다. 서버가 매번 새로 떠서 매번 "첫 분석"이라
  Sonar way(새 코드) 게이트는 걸리지 않는다 — 그래서 `init` 이 프로젝트별 **래칫 게이트**(전체 코드 조건)를 만들어 지정한다. 첫 분석 기준선
  (백엔드 커버리지 91.1%·버그 6·취약점 7, 프론트 94.9%·0·0)은 통과하고 나빠지면 실패한다(`scripts/_sonar.sh` `SONAR_CI_GATE_CONDITIONS`):

  | 게이트 | 커버리지 | 중복 줄 | 버그 | 취약점 |
  |---|---|---|---|---|
  | `board-ci-backend` | 88% 미만이면 실패 | 3% 초과 | 6 초과 | 7 초과 |
  | `board-ci-frontend` | 92% 미만이면 실패 | 3% 초과 | 0 초과 | 0 초과 |

  백엔드 버그·취약점 기준은 지금 값 그대로다 — TASK-38 에서 고치면서 낮춘다. 로컬 서버는 Sonar way(새 코드, 분석 이력이 남는다)를 그대로 쓴다.

## 테스트

```bash
make test   # = scripts/test.sh : 리포 검증 + 배포 스크립트 + k8s 매니페스트·k3d 절차 + Terraform(fmt·validate·test) + 백엔드(./gradlew test) + 프론트(npm test)
make test-integration  # Testcontainers 통합 테스트(백엔드)만
make e2e    # Playwright E2E (느림, make test 에 없음) — 아래
make smoke  # 배포 스모크: SMOKE_BASE_URL=https://<cloudfront> make smoke (없으면 SKIP)
```

- 백엔드: JUnit 5 + Testcontainers(PostgreSQL, MinIO — 컨테이너는 각각 하나를 모든 통합 테스트가 같이 쓴다). **Docker가 떠 있어야 한다.**
- 프론트: Vitest + Testing Library(`frontend/src/**/*.test.ts(x)`).
- 리포: `scripts/test/repo_test.sh` (Makefile 타깃, hooks 설정, gitignore, 문서, 다이어그램이 원본과 같은지 등 골격 검증)
- 배포: `scripts/test/deploy_test.sh` (DRY_RUN·가짜 aws/docker로 배포 스크립트, 워크플로 actionlint, shellcheck, 가짜 HTTP 서버로 `smoke_test.sh` 검증)
- 로컬 도구: `scripts/test/tools_test.sh` (가짜 dot 으로 `build-diagrams.py --check`의 렌더 환경 고정, `scripts/_proc.sh` 프로세스 그룹 정리(TERM → KILL),
  가짜 npm 으로 `frontend-deps.sh`, 임시 Makefile 복사본으로 `.env`가 배포 타깃에 섞이지 않는지, Next.js 취약 버전 아님)
- k8s: `scripts/test/k8s_test.sh` (kustomize 렌더 + kubeconform 오프라인 검증, requests/limits·예산, 마이그레이션 Job, 가짜 docker·k3d·kubectl 로
  `scripts/k3d.sh`의 사전 검사·프로필 전환·스모크·자동 정지). `make test`·`make test-integration`·`make backend-test`·`make backend-build`·`make e2e`는 시작할 때 떠 있는 k3d 클러스터를 멈춘다([메모리 규칙](#메모리-규칙-스크립트가-강제한다)).
- Terraform: `terraform fmt -check -recursive infra` + 루트 스택(`infra/bootstrap`, `infra/main`)과 `tests/*.tftest.hcl`이 있는 모듈마다 `terraform init -backend=false`·`validate`·`test`.
  테스트는 `mock_provider "aws"`라 AWS 자격 증명이 필요 없다(provider는 처음 한 번 내려받아 `~/.terraform.d/plugin-cache`에 둔다).
  `terraform`이 없으면 경고만 내고 건너뛴다. 재사용 모듈의 `.terraform.lock.hcl`은 커밋하지 않고, 루트 스택(`infra/bootstrap`, `infra/main`)의 lock 파일은 커밋한다.
- CloudFront Function: `scripts/test/cf_function_test.mjs`(Node 로 함수의 viewer-request·viewer-response 핸들러를 직접 실행하고, 함수의 페이지 목록이
  `frontend/src/app`의 `page.tsx` 목록과 같은지 검사한다. `repo_test.sh`가 호출).

개별 실행: `make backend-test`, `make frontend-test`, 정적 export 빌드는 `make frontend-build`(결과물 `frontend/out/`).
전체 타깃 목록은 `make help`.

### E2E (Playwright, TASK-18)

`make e2e`(= `scripts/e2e.sh`)가 실제 로컬 스택을 띄우고 시나리오를 돌린 뒤 정리한다. 처음 한 번 `cd frontend && npx playwright install chromium`이 필요하다.

1. PostgreSQL + MinIO: compose 별도 프로젝트 `board-e2e`(포트 55432·59000, `frontend/e2e/docker-compose.e2e.yml`)로 띄워 개발용 컨테이너와 겹치지 않고, 매번 빈 DB로 시작한다.
2. 백엔드: `bootJar` → `java -jar`(:18080, `SMS_PROVIDER=console`, `ADMIN_EMAIL=admin-e2e@example.com`). 로그는 `frontend/e2e/artifacts/backend.log`이고,
   휴대폰 OTP 시나리오가 이 로그에서 인증번호를 읽는다(테스트 전용 API 없음). 관리자는 운영 절차와 같이 `ADMIN_EMAIL`로 API 가입 → 백엔드 재시작으로
   올려 두고, 시나리오는 그 계정으로 로그인한다.
3. 프론트: `next dev`(:3100, `/api` → :18080). **같은 `frontend/`의 `next dev`는 하나만 뜬다** — `make frontend-dev`를 먼저 끈다.
4. `npx playwright test`(chromium) → 결과·스크린샷·trace는 `frontend/e2e/artifacts/`(gitignore). 끝나면 프로세스를 끄고 `compose down -v`.

프로세스 정리(TASK-35#2): 백엔드와 `next dev`는 각자 프로세스 그룹으로 띄우고(`scripts/_proc.sh`), 끝날 때(성공·실패·Ctrl-C) 그 그룹에만
TERM을 보낸 뒤 `E2E_STOP_TIMEOUT`(기본 10초) 안에 안 끝나면 KILL한다. `next dev`가 띄운 하위 서버도 같은 그룹이라 함께 끝난다.
포트로 프로세스를 찾아 죽이지 않는다(같은 포트를 잡은 다른 프로그램을 건드리지 않게).

시나리오(`frontend/e2e/smoke.spec.ts`, 직렬): A 가입·로그아웃·로그인 → 글쓰기(카테고리·태그·이미지 업로드) → 목록·태그·검색 → 좋아요·댓글 →
B 가입, A의 글에 수정·삭제 버튼 없음 + API PATCH 403 → 관리자 정지 → B의 다음 쓰기 실패·로그인 403 → 휴대폰 OTP 로그인 → 삭제 확인 창(취소·확인).
인자는 그대로 넘긴다: `make e2e E2E_ARGS="--headed"`, `./scripts/e2e.sh -g OTP`. `E2E_KEEP=1`이면 끝나도 스택을 남긴다(디버깅).
남긴 프로세스 그룹과 시작 시각은 `frontend/e2e/artifacts/kept-pids`에 적히고, 다음 `make e2e`가 시작할 때 시작 시각이 같은 그룹만 끄고
`compose down -v` 한 뒤 진행한다(PID가 다른 프로그램에 재사용됐으면 건너뛴다).

### 다이어그램

DOT 원본은 `diagrams/*.dot`이고, `make diagrams`(= `scripts/build-diagrams.py`)가 `docs/diagrams/*.svg`로 렌더한 뒤 문서의 `<!--dot:이름-->` 블록을 갱신한다.

- 색은 `.dot`에 센티넬 hex로 쓰고, 빌드가 CSS 변수로 바꾼다. SVG 안의 `<style>`이 라이트 값과 `@media (prefers-color-scheme: dark)` 다크 값을 정의하므로, SVG 파일 하나가 보는 쪽 테마를 따른다.
- GitHub는 Markdown 안의 인라인 `<svg>`를 지우고 페이지 CSS를 SVG에 넘기지 않는다. 그래서 변수 정의를 SVG 안에 넣고 `<img>`로 참조한다. `<img>`의 SVG는 OS·브라우저의 색 테마를 따른다.
  GitHub 테마만 따로 바꾼 경우에도 읽히도록 배경을 칠해 둔다.
- `make test`가 `build-diagrams.py --check`로 커밋된 SVG가 원본과 같은지 확인한다(로컬에 `dot`이 없으면 건너뛴다. CI는 graphviz를
  apt로 설치해 항상 검사한다).
  graphviz 버전·OS(글꼴 측정)가 다르면 같은 `.dot`이라도 좌표가 바이트 단위로 달라지므로, 커밋된 SVG를 만든 환경을 `diagrams/render-env.txt`
  (`graphviz=<버전>`, `os=<uname -s>`)에 적어 두고 그 환경에서만 바이트 비교한다. 다르면(CI의 Ubuntu graphviz 2.43 등) `SKIP`을 알리고
  좌표와 무관한 구조 — 글자(노드 이름·라벨), 요소 태그(노드·엣지·클러스터 그룹 순서대로), `var(--d-*)` 색 토큰, 모양·화살표 요소 종류별 개수 —
  와 문서 블록·파일 목록을 비교한다. 그래서 색 센티넬이나 모양만 바꾸고 다시 렌더하지 않은 것도 잡는다(그룹 안 요소 순서는 graphviz 버전마다
  달라 정렬해서 본다).
  다르면 파일 목록과 diff 일부를 보인다(TASK-35#1). graphviz를 올려 다시 렌더했으면 `python3 scripts/build-diagrams.py --env > diagrams/render-env.txt`.
- 시퀀스 다이어그램(OAuth 로그인 흐름)은 DOT으로 표현할 수 없어 [docs/api.md](docs/api.md)에 mermaid로 썼다.

## 인증·인가 요약

<!--dot:auth-model-->
<p align="center"><img src="docs/diagrams/auth-model.svg" alt="인증 모델 — users 1:N user_identities(provider, subject), local_credentials·refresh_tokens, 요청은 Bearer JWT 와 SESSION 쿠키(+CSRF) 중 하나로 인증된 뒤 공통 인가 필터를 지난다"></p>
<!--/dot:auth-model-->

- 계정 `users` 1 : N `user_identities(provider, subject)`. 비밀번호는 `local_credentials`에만 있다. 이메일이 같아도 자동으로 합치지 않고, 로그인한 뒤 "연결"한다.
- 요청 하나에 보안 체인 하나: `Authorization: Bearer <JWT>`가 있으면 JWT, 없으면 `SESSION` 쿠키(+ 쓰기는 `X-XSRF-TOKEN`). 정지 계정·제한 세션의 쓰기는 공통 필터가 403으로 막는다.
- 상세 규칙(엔드포인트, 오류 코드, 휴대폰 OTP 한도, 재활용 번호 보호)은 [docs/api.md](docs/api.md#인증인가-모델-백엔드)에 있다.

## 인프라 (Terraform, TASK-16)

> 첫 apply 는 [docs/first-deploy.md](docs/first-deploy.md) 순서로 한다(유저 확인 후). 로컬 검증은 `make test`(fmt·validate·mock_provider 테스트)다.

### 구성

```
infra/bootstrap/          state S3 버킷(버전·SSE·퍼블릭 차단·HTTPS 강제·prevent_destroy). 로컬 state 로 1회 apply
infra/main/               운영 루트 스택 (backend.tf: S3 partial config + use_lockfile, providers.tf: aws + aws.us_east_1)
  envs/prod.backend.hcl   backend 설정(bootstrap 출력 backend_config)
  envs/prod.tfvars        prod 변수
  modules/network         VPC 10.20.0.0/16, public subnet 1개(AZ 변수), IGW, 기본 경로
  modules/backend_host    EC2 t4g.small(AL2023 arm64, SSM 파라미터 AMI, IMDSv2, hop 2, 루트 암호화), EIP,
                          데이터 EBS gp3 20GB(/dev/sdf → /srv/pgdata, 비어 있을 때만 포맷, fstab UUID+nofail, final_snapshot),
                          DLM 일일 스냅샷 7개, SG(8080 ← CloudFront origin-facing prefix list 만, 22 없음),
                          인스턴스 role(SSM Core, ECR pull, Logs, SSM /board/prod/* 읽기 + KMS(SSM 경유), sms-voice:SendTextMessage, uploads 정책),
                          로그 그룹 /board/prod(14일), user_data(docker + compose plugin, awslogs 기본 로그 드라이버, /opt/board)
  modules/frontend_cdn    ECR(scan on push, 최근 20개), web S3 + OAC, CloudFront(아래)
  modules/uploads         첨부 버킷(TASK-11). CloudFront /uploads/* OAC 정책은 배포 ARN 으로 여기서 붙는다
  modules/cognito         User Pool + Hosted UI(TASK-6). 콜백은 PUBLIC_BASE_URL 에서
  modules/ci_oidc         GitHub OIDC provider + main 브랜치 전용 배포 role (github_repository 를 주면 켜짐)
  modules/dns_cert        Route53 zone(조회/생성) + ACM(us-east-1, DNS 검증) + alias A/AAAA (domain_name 을 주면 켜짐)
```

CloudFront 동작:

| 경로 | 오리진 | 캐시 | 그 밖 |
|---|---|---|---|
| 기본(`/*`) | web S3(OAC) | Managed-CachingOptimized | CF Function(같은 함수를 두 이벤트에): viewer-request `/x/`·`/x` → `/x/index.html`, 모르는 페이지 경로 → `/404.html`; viewer-response 그 응답을 상태 404 로 |
| `/api/*` | EC2 EIP public DNS, HTTP 8080 | Managed-CachingDisabled | 모든 메서드, HTTPS 전용, 함수 없음(트레일링 슬래시 리라이트 없음). origin request policy = 모든 viewer 헤더·쿠키·쿼리 + `CloudFront-Viewer-Address` |
| `/uploads/*` | uploads S3(OAC) | Managed-CachingOptimized | GET/HEAD |

- `/api/*` 헤더: `Authorization`은 cache key 나 "모든 viewer 헤더" origin request policy 로만 전달된다. CachingDisabled 는 키를 둘 수 없으므로
  `allViewerAndWhitelistCloudFront` + `CloudFront-Viewer-Address`(앱의 `CLIENT_IP_HEADER`) 커스텀 정책을 쓴다. 이 정책은 viewer `Host`도 넘기지만
  오리진이 가상 호스트 없는 Tomcat 이라 문제없다(OAuth redirect-uri 는 `PUBLIC_BASE_URL`로 고정).
- 404 페이지(TASK-34#2, FE-35): `custom_error_response`는 두지 않는다. 배포 전체에 걸려 `/api/*`의 404·403 JSON 을 HTML 로 덮기 때문이다(B1).
  대신 기본 동작의 CF Function 이 페이지 목록(`PAGES`, `functions/index_rewrite.js`)에 없는 확장자 없는 경로·디렉터리를 `/404.html`로 보내고
  viewer-response 에서 상태를 404 로 바꾼다. 함수는 S3 에 객체가 있는지 알 수 없어 목록을 두며, **페이지(`page.tsx`)를 더하면
  `infra/main/modules/frontend_cdn/functions/index_rewrite.js`의 `PAGES`도 고친다**(`make test`의 `cf_function_test.mjs`가 어긋나면 실패).
  배포 순서는 **`terraform apply`(함수의 `PAGES`) → `make deploy-frontend`(페이지 파일)**다. `deploy.yml`은 terraform 을 돌리지 않으므로 main 에
  머지해도 함수는 바뀌지 않는다 — apply 를 먼저 하지 않으면 새 페이지가 404 로 보인다. 함수는 `/api/*`·`/uploads/*`에 붙지 않고, 함수 안에서도
  `/api`·`/api/*`·`/uploads/*`는 그대로 돌려준다.
- 비용: viewer-response 연결로 기본 동작 요청마다 함수가 두 번(viewer-request·viewer-response) 불린다. CloudFront Functions 는 100만 호출당
  $0.10 이므로 기본 동작 100만 요청당 $0.10 이 더 든다.
- 한계(받아들임): 확장자가 있는 없는 경로(`/robots.txt`, `/foo.html`, 옛 배포의 `_next` 청크 등)는 함수가 `/404.html`로 보내지 않아 S3 의 404(XML)
  그대로다.
- 가격 등급 PriceClass_100, 인증서는 `*.cloudfront.net` 기본(decision-9). `domain_name`을 주면 aliases + ACM.

### 처음 적용 순서

bootstrap(state 버킷, 계정당 1회) → `envs/prod.backend.hcl` → `envs/prod.tfvars`의 `CHANGE-ME` 채우기 → `terraform -chdir=infra/main init -backend-config=envs/prod.backend.hcl`
→ `plan -var-file=envs/prod.tfvars -out=prod.tfplan` → `apply prod.tfplan`. 단계별 명령과 확인 항목은 [docs/first-deploy.md](docs/first-deploy.md) 1–4단계에 있다.

`envs/prod.tfvars` 항목: `region`(us-east-1), `name`, `availability_zone`, `instance_type`, `data_volume_size`, `web_bucket_name`·`uploads_bucket_name`(전역 고유),
`ecr_repository_name`, `ssm_parameter_path`(`/board/prod`), `log_group_name`, `cognito_domain_prefix`(전역 고유), `cognito_deletion_protection`,
`cognito_extra_callback_urls`/`cognito_extra_logout_urls`(로컬 Cognito 로그인용, 선택), `domain_name`·`subject_alternative_names`·`create_hosted_zone`(비우면 cloudfront.net),
`github_repository`(`owner/repo`, 비우면 CI role 없음)·`github_branch`, `existing_github_oidc_provider_arn`(계정에 이미 있으면).

### apply 뒤 앱 설정

`terraform output app_env`가 비밀이 아닌 값을 준다. TASK-17 `put-secrets.sh`가 시크릿과 함께 SSM `/board/prod/*`에 넣는다.

| env | 값 |
|---|---|
| `PUBLIC_BASE_URL` | `https://<cloudfront_domain 또는 domain_name>` (출력 `public_base_url`) |
| `COOKIE_SECURE` | `true` |
| `CLIENT_IP_HEADER` | `CloudFront-Viewer-Address` |
| `UPLOADS_BUCKET` / `UPLOADS_REGION` | 출력 `uploads_bucket` / `us-east-1` (앱 기본값 `ap-northeast-2`라 반드시 명시) |
| `UPLOADS_PUBLIC_BASE_PATH` | `/uploads/` (CloudFront 같은 오리진). `UPLOADS_ENDPOINT`·`UPLOADS_PATH_STYLE`·`UPLOADS_ACCESS_KEY`·`UPLOADS_SECRET_KEY`는 비움(인스턴스 프로파일) |
| `COGNITO_ISSUER_URI` / `COGNITO_CLIENT_ID` / `COGNITO_DOMAIN` | 출력 `cognito` |
| `COGNITO_CLIENT_SECRET` | `terraform output -raw cognito_client_secret` → SSM SecureString (state 에는 남는다) |
| `SMS_PROVIDER` / `SMS_AWS_REGION` | `aws` / `us-east-1` |

Terraform 은 SSM 파라미터(시크릿)를 만들지 않는다 — 값이 state 에 남지 않게 한다. 인스턴스 role 은 `/board/prod/*`만 읽는다.
GitHub Actions 는 `ci_role_arn`을 assume 하고, 배포 대상 값(ECR URL·web 버킷·배포 ID·인스턴스 ID)은 리포 variables 로 넣는다(CI 는 state 를 읽지 않는다).
EC2 의 PostgreSQL 데이터는 `/srv/pgdata`(데이터 EBS)이고 deploy compose 가 컨테이너의 `/var/lib/postgresql`에 바인드한다. 배포 디렉터리는 `/opt/board`.
컨테이너 로그는 docker 기본 로그 드라이버 `awslogs`(그룹 `/board/prod`)로 CloudWatch 에 간다. postgres 는 `log_error_verbosity=terse`·`log_min_error_statement=panic`으로
오류 DETAIL(키 값 등 개인정보)과 실패한 문장을 서버 로그에 남기지 않는다(TASK-21).

비용은 [비용](#비용-us-east-1-월-대략), 삭제 순서는 [정리](#정리-terraform-destroy)를 본다.

## AWS 실배포 설계 (TASK-65, decision-11)

> 레거시 `infra/main`(EC2 + Compose + CloudFront)을 대체할 새 루트 스택 `infra/platform`의 얼개다. **아직 apply 하지 않는다** — 검증은
> `make test`(fmt·validate·mock_provider 테스트)뿐이고, 실제 배포·레거시 철거는 TASK-52 에서 한다.

<!--dot:platform-aws-->
<p align="center"><img src="docs/diagrams/platform-aws.svg" alt="AWS 실배포 설계(decision-11) — 브라우저 → EIP → k3s 노드(t4g.large 스팟, Rocky 9.8, Traefik 80·443)의 앱·PostgreSQL·Kafka·MinIO·모니터링, 데이터 gp3 볼륨(/data, DLM 스냅샷), SSM·Cognito·SMS·Budgets, 선택 HAProxy lb_node"></p>
<!--/dot:platform-aws-->

관리형 서비스를 쓰지 않는다(decision-11): DB·Kafka·MinIO(S3 대신)·Prometheus/Alertmanager(CloudWatch 대신)가 모두 노드의 k3s 안에서 돌고,
이미지는 GHCR(ECR 대신), TLS 는 cert-manager + Let's Encrypt(CloudFront·ACM 대신)다. NAT·ALB/NLB·CloudFront·RDS·MSK·ECR·CloudWatch 알람은
만들지 않는다(`make test`가 리소스 종류 허용 목록으로 막는다). 남기는 AWS 기능은 Cognito·End User Messaging SMS·SSM(Session Manager·Parameter Store)·
Budgets·EBS 스냅샷뿐이다.

```
infra/platform/           새 루트 스택 (backend: bootstrap 버킷, key=platform/terraform.tfstate, use_lockfile)
  envs/prod.backend.hcl   backend 설정        envs/prod.tfvars   prod 변수(repo_commit·cognito_domain_prefix 는 CHANGE-ME)
  (network)               ../main/modules/network 재사용 — VPC + public subnet 1개, NAT 없음
  modules/k3s_node        Rocky 9.8 arm64(792107900819) t4g.large 스팟(persistent, 회수 시 stop), IMDSv2, 루트·데이터 EBS 암호화, EIP 1개,
                          SG 80·443 만 — 22 없음(IPv6 규칙도 두지만 network 에 IPv6 가 없다 — 지금은 IPv4 로만 닿는다),
                          IAM = SSM 에이전트 최소 권한(Session Manager·Run Command) + /board/* 파라미터 읽기·복호화만,
                          데이터 볼륨 gp3 30GB(/dev/sdf → /data, prevent_destroy, data_snapshot_id 는 처음 만들 때만), user_data
  modules/lb_node         HAProxy t4g.small(80·443 TCP → 노드). enable_lb_node(기본 false)
  snapshots.tf            데이터 볼륨 DLM 일일 스냅샷 7개. enable_snapshots(기본 true)
  budgets.tf              AWS Budgets 월 한도(budget_monthly_usd, 기본 $30) 실제 80%·예측 100% 이메일 알림. budget_email 이 비면 없음
  (cognito)               ../main/modules/cognito 재사용. 공개 주소 = domain_name 또는 <EIP>.sslip.io
```

노드 부팅(user_data, cloud-init):

1. `board-data-volume.sh`: `k3s.service`에 `RequiresMountsFor=/data` + 시작 전 `mountpoint` 드롭인을 **먼저** 쓴다 — 데이터 볼륨이
   마운트되지 않으면 k3s(그 안의 DB)가 루트 디스크에 빈 데이터로 뜨지 않는다. 장치는 볼륨 ID 의 by-id 링크로만 찾고, `blkid -p` 종료 코드 2(빈 장치)일
   때만 포맷한다(그 밖의 코드면 손대지 않고 실패). fstab 은 UUID + `nofail` — 볼륨이 없을 때 부팅이 비상 모드에 빠져 SSM 으로도 못 들어가는 일을 막고,
   서비스 차단은 드롭인이 맡는다. 볼륨을 키웠으면 `xfs_growfs`로 늘린다. k3s `data-dir`(containerd 이미지 포함)·local-path PV 는 `/data` 아래이고,
   새 XFS 는 SELinux `default_t`라 `/data/k3s`는 `/var/lib/rancher/k3s`와 같은 규칙(equivalence), `/data/local-path`는 `container_file_t`로 맞춘다.
   k3s 노드 이름은 `node_name`(기본 `board-node`)으로 고정한다 — 노드를 새로 만들어도 local-path PV 의 nodeAffinity 가 그대로 맞는다(바꾸면 PV 가 고아가 된다).
2. 저장소 **고정 커밋**(`repo_commit`, 40자리)의 `deploy/rocky/install.sh --profile server`(개발 VM 과 같은 스크립트). dnf·fetch·install.sh 는 3회 재시도.
3. k3s 설치 자리 `k3s_install_cmd`(기본 비움 — TASK-36 스크립트를 넘긴다). 끝나면 `/var/lib/board-node/bootstrap.done`.
4. **배포 전에 반드시** SSM 세션에서 `sudo /usr/local/sbin/board-node-ready`(출력 `node_ready_check`)를 돌린다. `cloud-init status --wait`가
   `done`이고 끝 표식이 있을 때만 성공한다(error·degraded 는 실패).

`user_data_replace_on_change = true`: user_data 를 바꾸면(커밋·k3s 명령) 노드를 새로 만든다. stop/start 로는 스크립트가 다시 돌지 않아 노드가 설정과
어긋나기 때문이다. 노드는 가축이다 — 데이터 볼륨·EIP 는 남고 새 노드에 다시 붙는다. AMI 갱신은 `ignore_changes = [ami]`라 `-replace`로 명시한다.
데이터 볼륨은 `prevent_destroy`라 plan 이 지우려 하면 실패한다. 스냅샷 복원·state 잠금 고착·백업은 [docs/terraform-state.md](docs/terraform-state.md).
IMDS hop limit 은 2 다 — External Secrets(파드)가 노드 자격 증명으로 `/board/*`를 읽기 때문이다. 그래서 파드도 IMDS 에 닿으므로 노드 role 을
최소 권한으로 두고, 앱 네임스페이스는 NetworkPolicy 로 막는다(아래 TASK-52).

비용(us-east-1, 월, 대략 — decision-11, 2026-10 조사):

| 항목 | 산식 | 월 |
|---|---|--:|
| k3s 노드 t4g.large 스팟 | 약 $0.021–0.026/h × 730 (온디맨드 $0.0672/h ≈ $49) | ≈ $15–19 |
| EBS gp3 루트 30GB + 데이터 30GB | 60GB × $0.08 | ≈ $4.8 |
| EBS 스냅샷(DLM 7개, 증분) | 변경분 수 GB × $0.05 | ≈ $0.3–1 |
| 공인 IPv4(EIP 1개) | $0.005/h × 730 | ≈ $3.7 |
| SSM(Session Manager·Parameter Store 표준)·Budgets | 무료 | $0 |
| Cognito | 소규모 MAU 무료 구간 **(요금제 확인 필요)** | ≈ $0 |
| **합계(상시 가동)** | decision-11 추정 $22–27 에 데이터 볼륨 30GB(+$0.8) | **≈ $23–28** |
| 밤 12시간 정지 | 노드 시간 절반, EBS·IPv4 는 그대로 | ≈ $16 |
| (선택) lb_node t4g.small 온디맨드 + EIP | $0.0168/h × 730 + $3.7 | +≈ $16 |
| (선택) SMS | 사용량 — [sms-setup](docs/sms-setup.md#5-비용-메모) | 사용량 |

레거시(t4g.small + CloudFront, ≈ $20–21)와 비슷한 비용에 메모리가 4배(8GB)다. 스팟이 회수되면 노드가 멈추고(데이터·EIP 유지) 단일 노드라 고가용성은 없다.

apply 하지 않는 이유: 로컬(Rocky 개발 VM·k3d)에서 할 수 있는 플랫폼 작업을 모두 끝낸 뒤 실배포한다(2026-10-29 결정). 지금 apply 하면 쓰지 않는 노드에
비용이 나가고, k3s 설치 스크립트(TASK-36)·앱 매니페스트가 아직 없어 노드가 빈 상태로 떠 있게 된다. 그래서 구조와 안전 장치만 오프라인 테스트로 고정한다.

TASK-52 로 넘기는 항목:

- 첫 `terraform plan/apply`(`envs/prod.tfvars`의 `repo_commit`·`cognito_domain_prefix`·`budget_email` 채우기), `board-node-ready` 확인
- 도메인(그때까지 `<EIP>.sslip.io`)과 WAF 위치(CloudFront 가 없어 AWS WAF 를 붙일 곳이 없다)
- 스냅샷 복원 리허설(`data_snapshot_id`), 스팟 회수 대응(TASK-60)
- 노드 role 에 앱 권한 추가(SMS `sms-voice:SendTextMessage` 등 — 지금은 SSM 에이전트 + `/board/*` 읽기·복호화만). KMS 조건의
  암호화 컨텍스트 키(`kms:EncryptionContext:PARAMETER_ARN`)로 SecureString 을 실제로 읽을 수 있는지 확인
- 앱 네임스페이스는 NetworkPolicy 로 `169.254.169.254`(IMDS) 차단 — hop limit 2 라 파드가 노드 자격 증명에 닿는다(External Secrets 만 허용)
- destroy·교체 시 스팟 요청이 취소되는지 확인(남으면 `aws ec2 cancel-spot-instance-requests`). persistent 요청은 인스턴스를 지워도 다시 띄울 수 있는데,
  provider 가 `aws_instance`(instance_market_options) 삭제 때 요청을 취소하는지는 오프라인으로 확인하지 못했다
- lb_node 를 켜면: 노드 SG 는 그대로 80·443 이 열려 있어 LB 를 우회할 수 있고, HAProxy 가 `send-proxy`(PROXY protocol)를 쓰지 않아 앱이 클라이언트 IP 를
  잃는다 — 켜기 전에 노드 SG 를 LB SG 로 좁히고 Traefik 의 PROXY protocol 을 맞춘다
- IPv6: network 에 IPv6 가 없다(VPC·subnet IPv6 CIDR, `::/0` 경로). IPv6 로 받으려면 network 모듈에 더한다
- 레거시 `infra/main` 철거(AC #5) — 그때 `network`·`cognito` 모듈을 `infra/platform/modules`로 옮긴다

## 배포 (TASK-17)

<!--dot:deploy-pipeline-->
<p align="center"><img src="docs/diagrams/deploy-pipeline.svg" alt="배포 파이프라인 — 로컬 make deploy 와 GitHub Actions deploy.yml 이 같은 스크립트로 백엔드 이미지를 ECR 에 올리고 SSM send-command 로 EC2 에 배포(헬스 실패 시 롤백), 프론트는 S3 sync 후 CloudFront invalidation"></p>
<!--/dot:deploy-pipeline-->

> 스크립트·워크플로는 `make test`에서 DRY_RUN·가짜 aws/docker로 검증한다. 실제 첫 배포는 [docs/first-deploy.md](docs/first-deploy.md)를 따른다.

로컬(`make deploy`)과 GitHub Actions(`deploy.yml`)가 같은 스크립트를 쓴다. 다른 점은 대상 값의 출처뿐이다.
로컬은 `scripts/tf-outputs.sh`가 `terraform output`에서 읽고, CI 는 리포 variables 에서 읽는다.
모든 스크립트는 `DRY_RUN=1`이면 aws·docker·npm 명령을 `[dry-run] ...`로 출력만 한다. 예: `make deploy DRY_RUN=1`

```
backend/Dockerfile            JDK 25 빌드 스테이지(bootJar → jarmode=tools 레이어) → eclipse-temurin:25-jre, uid 10001, -XX:MaxRAMPercentage=60
scripts/build-backend.sh      ECR 로그인 + buildx linux/arm64 --push  (태그: git short sha 12자리 + latest)
scripts/deploy-backend.sh     SSM send-command(AWS-RunShellScript) → EC2 에서 remote-deploy.sh, get-command-invocation 폴링
scripts/deploy-frontend.sh    npm ci·build → S3 sync 2회(캐시 헤더) → CloudFront invalidation /*
scripts/put-secrets.sh        입력 파일 + terraform app_env·cognito secret → SSM /board/prod/*
scripts/tf-outputs.sh         terraform output → AWS_REGION, ECR_REPOSITORY_URL, WEB_BUCKET, DISTRIBUTION_ID, INSTANCE_ID, ...
deploy/docker-compose.prod.yml  EC2 compose: api(:8080) + postgres:18-alpine(/srv/pgdata), awslogs, healthcheck, restart
deploy/remote-deploy.sh       (EC2) render-env → ECR 로그인 → pull·up → 헬스 대기 → 실패 시 롤백
deploy/render-env.sh          (EC2) SSM /board/prod/* → /opt/board/app.env, db.env (600)
deploy/secrets.example.txt    put-secrets 입력 예시 (실제 입력 deploy/secrets.local.txt 는 gitignore)
.github/workflows/ci.yml      PR·main 이 아닌 브랜치: make test + bootJar + 프론트 build
.github/workflows/deploy.yml  main push·수동: ci.yml → OIDC → build-backend → deploy-backend → deploy-frontend
```

### 처음 배포 순서

```bash
make tf-outputs                                             # 0) apply 가 끝났는지 확인
cp deploy/secrets.example.txt deploy/secrets.local.txt      # 1) OAuth id·secret, SMS_* 를 채운다(ADMIN_EMAIL 은 관리자 가입·확인 뒤에만, first-deploy 7)
make put-secrets PUT_SECRETS_FLAGS=--generate-missing DRY_RUN=1   # 2) 무엇을 넣을지 확인(시크릿은 '****')
make put-secrets PUT_SECRETS_FLAGS=--generate-missing       #    DB_PASSWORD·JWT_SECRET·OTP_HASH_KEY 는 SSM 에 없을 때만 만든다
make deploy DRY_RUN=1 && make deploy                        # 3) 커밋된 HEAD 기준(커밋 안 된 변경은 build-backend 가 거부)
SMOKE_BASE_URL="$(terraform -chdir=infra/main output -raw public_base_url)" make smoke   # 4) health·캐시 헤더·CSRF·/api/posts
```

전체 순서(SSM 등록 확인, GitHub Actions 연결, 롤백 시험 포함)는 [docs/first-deploy.md](docs/first-deploy.md)에 있다.

로컬 배포에 필요한 것: aws CLI v2(관리자 자격 증명), docker buildx(Apple Silicon 은 arm64 네이티브, x86 은 QEMU), jq, terraform, Node 24.

### 백엔드 배포 흐름

1. `build-backend.sh`: `<ECR>:<sha>`·`:latest`를 linux/arm64 로 빌드해 push 한다(`--provenance=false`: attestation 이 ECR 수명 주기 20개를 차지하지 않게).
2. `deploy-backend.sh`: ECR 에 `<sha>`가 있는지 확인한다. compose 파일, `render-env.sh`, `remote-deploy.sh`를 base64 로 명령에 실어 send-command 를 보낸다.
   S3 를 거치지 않아 CI role 에 추가 권한이 필요 없다. 그 뒤 `get-command-invocation`을 끝날 때까지 폴링하고(기본 900초), 원격 stdout·stderr 를 출력한다. 실패면 종료 코드가 0 이 아니다.
3. EC2(`/opt/board`)의 `remote-deploy.sh`:
   SSM → `app.env`·`db.env` 렌더 → 인스턴스 role 로 ECR 로그인 → `IMAGE_TAG=<sha> docker compose pull && up -d` →
   `http://127.0.0.1:8080/api/actuator/health` 대기(240초).
   성공하면 `/opt/board/current_tag`에 태그를 쓰고 1주일 넘은 이미지를 정리한다. 실패하면 api 로그 100줄을 보이고 `current_tag`(직전 성공 태그)로 되돌린 뒤 실패로 끝난다.
4. postgres 데이터: postgres 18 이미지의 `PGDATA`는 `/var/lib/postgresql/18/docker`다. 데이터 EBS `/srv/pgdata`를 부모 `/var/lib/postgresql`에 바인드한다.
   메모리 한도는 api 1200m(힙 60% ≈ 720m), db 512m 다(t4g.small 2GB).

### 시크릿 흐름 (로컬 → SSM → EC2 env 파일)

- `put-secrets.sh <파일>`의 값 출처는 다음 순서로 덮어쓴다. ① `terraform output app_env`(비밀 아님) ② `cognito_client_secret` ③ 입력 파일.
  빈 값은 넣지 않는다(앱 기본값). 이미 넣은 값을 지우려면 `aws ssm delete-parameter --name /board/prod/<KEY>`를 쓴다.
- 이름에 SECRET·PASSWORD·KEY·TOKEN 이 들어간 키는 `SecureString`(aws/ssm KMS), 나머지는 `String`이다. 모두 `--overwrite`한다.
  값은 명령줄이 아니라 `--cli-input-json` 임시 파일(600)로 넘기고, 출력(DRY_RUN 포함)에는 `****`만 남긴다.
- EC2 의 `render-env.sh`는 배포마다 `get-parameters-by-path --recursive --with-decryption`으로 `/opt/board/app.env`(api)와 `db.env`(POSTGRES_* 만)를 600 으로 다시 쓴다.
  값은 compose env_file 큰따옴표 형식으로 쓴다(`\ " $` 줄바꿈 이스케이프, 보간 없음). `DB_URL`·`DB_USERNAME`이 없으면 `jdbc:postgresql://db:5432/board`·`board`를 쓴다.
  필수 키(DB_PASSWORD, JWT_SECRET, OTP_HASH_KEY)가 없으면 배포가 실패한다. 개발용 기본값으로 운영이 뜨지 않게 하려는 것이다.
- 시크릿을 바꾼 뒤에는 `make deploy-backend`로 반영한다(같은 코드면 이미지 태그도 같다).

### GitHub Actions 설정

1. `envs/prod.tfvars`의 `github_repository = "owner/repo"`로 apply → `terraform output ci_role_arn`.
2. 리포 Settings → Secrets and variables → Actions → **Variables**(시크릿 아님, 전부 terraform output):

| variable | 값 |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `ci_role_arn` |
| `AWS_REGION` | `region` (us-east-1) |
| `ECR_REPOSITORY_URL` | `ecr_repository_url` |
| `WEB_BUCKET` | `web_bucket` |
| `DISTRIBUTION_ID` | `distribution_id` |
| `INSTANCE_ID` | `instance_id` |

- OIDC: role trust 는 `repo:<owner/repo>:ref:refs/heads/main`과 정확히 일치해야 한다. GitHub **environment**를 job 에 붙이면 OIDC `sub`가
  `repo:<owner/repo>:environment:<name>`으로 바뀌어 assume 이 실패한다. 그래서 environment 를 쓰지 않는다(승인 게이트가 필요하면 ci_oidc trust 도 함께 바꾼다).
  `deploy` job 은 `github.ref == refs/heads/main`일 때만 돈다.
- `deploy.yml`은 `ci.yml`(workflow_call)이 통과해야 배포하고, `concurrency: deploy-prod`(진행 중 취소 안 함)로 한 번에 하나만 돈다.
  러너는 `ubuntu-24.04-arm`이다(arm64 이미지를 에뮬레이션 없이 빌드). 비공개 리포라면 플랜에서 arm64 호스티드 러너를 쓸 수 있는지 확인한다.
- CI role 권한: ECR push, web 버킷 sync, invalidation, 이 인스턴스에 `AWS-RunShellScript` send-command, 명령 결과 조회. SSM 파라미터·state 는 읽지 않는다.
  그래서 `put-secrets`는 로컬에서만 한다.
- 액션은 메이저 버전 태그(`@v7` 등)로 고정했다. 공급망을 더 조이려면 커밋 SHA 고정 + Dependabot(github-actions)으로 바꾼다.
- `ci.yml`은 actionlint 를 설치하고 `REQUIRE_LINTERS=1`로 `make test`를 돌린다. 로컬은 shellcheck·actionlint 가 없으면 그 검사만 건너뛴다.

### 롤백

- 자동: 헬스체크가 실패하면 `remote-deploy.sh`가 `/opt/board/current_tag`(직전 성공 태그)로 되돌리고 배포를 실패 처리한다.
- 수동(특정 태그로): 빌드 없이 배포만 한다. 태그는 ECR 에 남아 있어야 한다(최근 20개).
  ```bash
  IMAGE_TAG=<이전 sha 12자리> ./scripts/tf-outputs.sh -- ./scripts/deploy-backend.sh
  ```
  `make deploy-backend`는 현재 코드로 다시 빌드하므로 롤백에 쓰지 않는다.
- 주의: Flyway 마이그레이션은 되돌리지 않는다. 새 버전이 스키마를 바꿨다면 이전 이미지가 그 스키마에서 뜨는지 먼저 확인한다(마이그레이션은 하위 호환으로 쓴다).
- 프론트: 이전 커밋을 체크아웃해 `make deploy-frontend`. 이것은 S3 파일만 되돌리고 CloudFront Function 의 `PAGES`는 되돌리지 않는다. 되돌린
  커밋에 없는 페이지가 `PAGES`에 남으면 그 경로는 `/404.html` 대신 S3 오류 응답(XML)이 되고, 되돌린 커밋에만 있는 페이지는 404 로 보인다 — 페이지 목록이
  달라졌다면 그 커밋으로 `terraform apply`도 한다.

### 로그

- 컨테이너 로그: CloudWatch Logs 그룹 `/board/prod`(14일). 스트림 이름은 `api/<컨테이너 이름>`, `db/<컨테이너 이름>`이다(compose `tag`).
  ```bash
  aws logs tail /board/prod --follow --log-stream-name-prefix api/
  ```
- 배포 명령 출력: `deploy-backend.sh`가 원격 stdout·stderr 를 그대로 출력한다(SSM 은 24,000자까지 보관). 지난 명령은 `aws ssm list-command-invocations --details`로 본다.
- 호스트 접속(SSH 없음): `aws ssm start-session --target <instance_id>` → `sudo -i; cd /opt/board; cat current_tag; docker compose -f docker-compose.prod.yml ps`.

### 문제 해결

| 증상 | 확인 |
|---|---|
| `send-command`가 `InvalidInstanceId` | 첫 부팅 직후 SSM agent 등록 전이다. `aws ssm describe-instance-information`에 나올 때까지 기다린다 |
| `render-env: 필수 파라미터가 없다` | `make put-secrets PUT_SECRETS_FLAGS=--generate-missing` |
| DB 접속 실패(password authentication failed) | `DB_PASSWORD`는 `/srv/pgdata`를 처음 초기화할 때만 쓰인다. SSM 값을 원래대로 돌리거나 `docker compose exec db psql -U board -c "ALTER USER board PASSWORD '...'"` 후 재배포 |
| 헬스 대기 시간 초과 | 출력된 api 로그 → CloudWatch `api/` 스트림. 시작이 느리면 `HEALTH_TIMEOUT`(remote-deploy.sh, 기본 240) |
| `ECR 에 이미지가 없다` | `build-backend.sh`와 태그가 다르다(커밋 안 된 변경, 다른 HEAD). `IMAGE_TAG`를 맞춘다 |
| CI 의 `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `github_repository`·브랜치·environment 미사용, `AWS_DEPLOY_ROLE_ARN` 확인 |
| `build-backend`가 커밋 안 된 변경을 거부 | 커밋하거나 `ALLOW_DIRTY=1`(태그가 내용과 달라진다) |
| `운영 모드(...)에서 안전하지 않은 설정이라 기동하지 않는다` | 운영 배포(render-env)는 `PRODUCTION_MODE=true`, 그 밖에는 `PUBLIC_BASE_URL`이 https면 운영 모드다. 메시지 아래 목록의 키(`JWT_SECRET`·`OTP_HASH_KEY` 공개 값, `COOKIE_SECURE`, `DB_PASSWORD`, `CLIENT_IP_HEADER`, `SMS_PROVIDER`)를 `put-secrets`로 고치고 재배포. 판정은 `PRODUCTION_MODE=auto\|true\|false`로 강제할 수 있다 |
| `CREATE EXTENSION pg_trgm 이 실패해 기동하지 못했다` | V8 마이그레이션에 DB CREATE 권한이 필요하다. [first-deploy: pg_trgm 권한](docs/first-deploy.md#postgresql-확장pg_trgm-권한) |

## 시크릿·환경변수 목록

앱은 모든 설정을 환경변수로 받는다. 로컬은 repo 루트의 `.env`(`example.env`를 복사, git에 올리지 않음)이고, 운영은 SSM Parameter Store `/board/prod/<KEY>`에 둔다.
운영 값은 `make put-secrets`가 넣고, EC2의 `render-env.sh`가 배포할 때마다 env 파일로 만든다([시크릿 흐름](#시크릿-흐름-로컬--ssm--ec2-env-파일)).

- **시크릿**: 이름에 SECRET·PASSWORD·KEY·TOKEN이 들어간 키는 SSM `SecureString`이다. 출력에는 `****`만 남긴다.
- **운영 필수**: 없으면 배포가 실패하거나(●) 그 기능이 꺼진다(○). 나머지는 앱 기본값을 쓴다.
- **운영 출처**: `terraform`은 `terraform output app_env`/`cognito_client_secret`이고 put-secrets가 자동으로 넣는다. `생성`은 `--generate-missing`이 무작위로 만든다. `입력`은 `deploy/secrets.local.txt`에 적는다.

| 키 | 시크릿 | 운영 필수 | 운영 출처 | 기본값 · 설명 |
|---|:-:|:-:|---|---|
| `DB_PASSWORD` | ● | ● | 생성 | 로컬 `board`. 운영은 postgres 데이터 디렉터리를 처음 만들 때만 쓰인다(그 뒤에 바꾸면 접속이 깨진다) |
| `DB_URL` / `DB_USERNAME` | | | (render-env 기본) | 로컬 `jdbc:postgresql://localhost:5432/board` / `board`. 운영은 `jdbc:postgresql://db:5432/board` / `board` |
| `DB_LOG_SERVER_ERROR_DETAIL` | | | (없음) | `false`. PostgreSQL 오류 Detail(키 값 등 개인정보)을 예외·로그에 싣지 않는다. 로컬 디버깅 때만 `true` |
| `DB_CONNECTION_TIMEOUT_MS` / `DB_SOCKET_TIMEOUT_SECONDS` | | | 입력(선택) | `5000` / `10` (TASK-23#1). 풀에서 커넥션을 기다리는 시간(hikari `connection-timeout`), 응답 없는 DB 를 끊는 소켓 타임아웃(pgjdbc `socketTimeout`). 커넥션 대기 + `DB_TRANSACTION_TIMEOUT` + 소켓 타임아웃, 커넥션 대기 ×2 + 소켓 타임아웃이 모두 30초 안이어야 DB 장애·과부하 중에도 응답이 30초 안에 503 JSON 으로 끝난다(B2) |
| `DB_LOCK_TIMEOUT` / `DB_STATEMENT_TIMEOUT` / `DB_TRANSACTION_TIMEOUT` | | | 입력(선택) | `5s` / `5s` / `10s` (TASK-23#1, PostgreSQL 시간 단위). 세션마다 거는 `lock_timeout`(넘으면 409 `lock_timeout`)·`statement_timeout`(문장 하나, 넘으면 503 `db_timeout`)·`transaction_timeout`(PostgreSQL 17+, 트랜잭션 하나 — 문장 여러 개의 합. 넘으면 서버가 세션을 끝내고(FATAL 25P04) 503 `db_timeout`, 그 커넥션은 풀에서 버린다). statement_timeout 은 소켓 타임아웃·transaction_timeout 보다 짧게 둔다. 상한은 트랜잭션마다라 한 요청이 트랜잭션 여러 개(세션 조회·본 처리·세션 저장)나 autocommit 문장 여러 개를 쓰면 이론상 합이 늘 수 있다(요청 단위 데드라인은 없다). Flyway 마이그레이션은 앱 풀 밖의 자기 커넥션(같은 `DB_URL`·계정)을 열어 statement_timeout·transaction_timeout·소켓 타임아웃 없이 돈다(lock_timeout 은 유지) |
| `JWT_SECRET` | ● | ● | 생성 | 자체 서명 JWT HS256 키, 32바이트 이상. 로컬은 개발용 기본값(운영 금지). 빈 값이면 기동 실패 |
| `OTP_HASH_KEY` | ● | ● | 생성 | OTP 코드 해시(HMAC-SHA256) 키, 32바이트 이상. 로컬은 개발용 기본값(운영 금지) |
| `JWT_REFRESH_TTL` | | | 입력(선택) | `P14D`. refresh 토큰 수명(ISO-8601). access 토큰은 15분 고정 |
| `COOKIE_SECURE` | | ● | terraform(`true`) | `false`. `SESSION`·`XSRF-TOKEN`·`VID` 쿠키 Secure 플래그 |
| `PUBLIC_BASE_URL` | | ● | terraform | `http://localhost:3000`. 브라우저가 보는 공개 주소(끝 `/` 없이). OAuth redirect-uri와 로그인 후 리다이렉트를 이 값으로 만든다 |
| `PRODUCTION_MODE` | | | render-env(`true`) | 앱 기본 `auto`, 운영 배포(render-env)는 SSM 에 없으면 `true`. `auto`면 `PUBLIC_BASE_URL`이 https일 때 운영 모드: 기동 시 안전 설정 검사(공개 비밀값·`COOKIE_SECURE`·`DB_PASSWORD`·`CLIENT_IP_HEADER`·`SMS_PROVIDER`), 실패하면 기동 안 함. `true`\|`false`로 강제 |
| `CLIENT_IP_HEADER` | | ● | terraform(`CloudFront-Viewer-Address`) | 비어 있음 = remote address. OTP 발송 한도의 클라이언트 IP |
| `ADMIN_EMAIL` | | | 입력(일시) | 비어 있음. 기동할 때 ADMIN이 하나도 없고 이 이메일(대소문자 무시)의 **LOCAL** 계정이 이미 있으면 한 번 ADMIN으로 올린다. 가입만으로는 ADMIN이 되지 않는다 — 가입 → 소유 확인 → 그때 설정·재배포 → 승격 뒤 반드시 삭제([first-deploy 7](docs/first-deploy.md#7-스모크-ac1)) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | 비우면 Google 로그인 꺼짐. [oauth-setup](docs/oauth-setup.md#google) |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | 비우면 꺼짐. 운영·로컬 OAuth App을 따로 만든다 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | Client ID / Client Secret. 비우면 꺼짐 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 시크릿만 | ○ | 입력 | REST API 키 / 보안 → Client Secret. 비우면 꺼짐 |
| `COGNITO_CLIENT_ID` | | ○ | terraform | Cognito 로그인. 있으면 `COGNITO_ISSUER_URI`·`COGNITO_DOMAIN`도 필요(없으면 기동 실패) |
| `COGNITO_CLIENT_SECRET` | ● | ○ | terraform(`cognito_client_secret`) | App Client secret(state에는 남는다) |
| `COGNITO_ISSUER_URI` / `COGNITO_DOMAIN` | | ○ | terraform | `https://cognito-idp.<region>.amazonaws.com/<pool id>` / `https://<prefix>.auth.<region>.amazoncognito.com` |
| `OAUTH_HTTP_CONNECT_TIMEOUT` / `OAUTH_HTTP_READ_TIMEOUT` | | | 입력(선택) | `3s` / `5s` (`app.oauth2.http.*`, TASK-25#3). 모든 제공자의 토큰 교환·userinfo·JWKS 호출의 연결·읽기 타임아웃. 넘으면 프론트 콜백 `?error=invalid_token_response`·`invalid_user_info_response`·`invalid_id_token`. 콜백 한 번의 최악은 대략 (연결 + 읽기) × 3. 0·음수면 기동 실패 |
| `SMS_PROVIDER` | | ● | terraform(`aws`) | `console`(로그로만, 번호 마스킹, 로컬 전용) · `memory`(테스트) · `aws`. 그 밖의 값이면 기동 실패. 운영 모드(https)는 `aws`만 허용 [sms-setup](docs/sms-setup.md) |
| `SMS_AWS_REGION` | | | terraform(`us-east-1`) | `us-east-1` |
| `SMS_ORIGINATION_IDENTITY` | | | 입력 | 발신 번호·sender ID·pool. 비우면 계정 기본 |
| `SMS_CONFIGURATION_SET` | | | 입력(선택) | 전송 이벤트 기록 |
| `SMS_API_CALL_TIMEOUT` / `SMS_API_CALL_ATTEMPT_TIMEOUT` | | | 입력(선택) | `10s` / `4s` (`app.sms.aws.api-call-timeout`·`api-call-attempt-timeout`, TASK-25#4). End User Messaging SendTextMessage 호출 전체(재시도 포함)·HTTP 시도 하나의 상한. 넘으면 503 `sms_unavailable`. 0·음수이거나 시도별 > 전체면 기동 실패. 타임아웃 난 시도를 SDK 가 재시도하므로 드물게 같은 SMS 가 두 번 갈 수 있다 |
| `SMS_DEFAULT_REGION` | | | 입력(선택) | `KR`. `+` 없이 들어온 번호의 지역 |
| `SMS_ALLOWED_REGIONS` | | | 입력(선택) | `KR`(`app.sms.allowed-regions`). SMS를 보낼 수 있는 번호의 지역(쉼표로 여럿, ISO 3166-1). 이 지역의 휴대폰(MOBILE·FIXED_LINE_OR_MOBILE)만, 그 밖은 400 `phone_not_allowed`. 모르는 코드면 기동 실패 (TASK-22#4) |
| `OTP_PHONE_DAILY_LIMIT` / `OTP_IP_HOURLY_LIMIT` | | | 입력(선택) | `10` / `20`. 같은 번호 24시간 N회, 같은 IP 1시간 M회(같은 번호 60초 1회는 고정). 1 이상이어야 하고 0·음수면 기동 실패(TASK-25#6, `app.otp.max-attempts`·`ttl`·`phone-cooldown` 도 기동 시 검증) |
| `RATE_LIMIT_LOGIN_ACCOUNT_MAX` / `RATE_LIMIT_LOGIN_IP_MAX` / `RATE_LIMIT_SIGNUP_IP_MAX` | | | 입력(선택) | `5` / `30` / `10` (`app.rate-limit.login-account`·`login-ip`·`signup-ip`). 로그인 실패 (계정, IP)별 15분 창(슬라이딩, `login-account` 이지만 키는 계정+IP — 다른 IP에서는 잠기지 않는다)·IP별 1시간, 가입 시도 IP별 1시간. 넘으면 429 `rate_limited` + `Retry-After` (TASK-22#1). step-up 실패 사용자별 15분 창 안 5회(슬라이딩 창, `app.rate-limit.step-up`)는 env 없이 고정 |
| `RATE_LIMIT_POST_MINUTE_MAX` / `RATE_LIMIT_POST_HOUR_MAX` / `RATE_LIMIT_COMMENT_MAX` | | | 입력(선택) | `3` / `30` / `10` (`app.rate-limit.post-minute`·`post-hour`·`comment`). 사용자별 글 1분·1시간, 댓글 1분. ADMIN 포함 (TASK-22#2) |
| `RATE_LIMIT_PRESIGN_MAX` / `RATE_LIMIT_MAX_PENDING_ATTACHMENTS` | | | 입력(선택) | `60` / `20` (`app.rate-limit.presign`·`max-pending-attachments`). 사용자별 presign 1시간(429), 미확정 첨부 개수(409 `too_many_pending_attachments`) (TASK-22#3). 창 길이는 `app.rate-limit.<이름>.window`(ISO-8601)로만 바꾼다 |
| `UPLOADS_BUCKET` | | ○ | terraform | 비어 있음 = 첨부 API 503 `uploads_unavailable`(앱은 뜬다) |
| `UPLOADS_REGION` | | ● | terraform(`us-east-1`) | 앱 기본값이 `ap-northeast-2`라 운영은 반드시 명시. 로컬 MinIO는 `us-east-1` |
| `UPLOADS_PUBLIC_BASE_PATH` | | | terraform(`/uploads/`) | 첨부 공개 URL 앞부분(url = 이 값 + 키에서 `uploads/` 뒤, 운영은 CloudFront 같은 오리진). 로컬은 `http://localhost:9000/board-uploads/uploads/` |
| `UPLOADS_ENDPOINT` / `UPLOADS_PATH_STYLE` | | | (운영은 비움) | 로컬 MinIO `http://localhost:9000` / `true`. 운영 S3는 비움 / `false` |
| `UPLOADS_ACCESS_KEY` / `UPLOADS_SECRET_KEY` | 시크릿만 | | (운영은 비움) | 로컬 MinIO 정적 자격 증명. 운영은 비워서 EC2 인스턴스 프로파일을 쓴다 |
| `UPLOADS_ORPHAN_GRACE_PERIOD` | | | 입력(선택) | `P7D` (`app.uploads.orphan-grace-period`, TASK-26#4). 글에서 뗀·붙이지 않은 확정 첨부와 삭제된 글의 첨부를 지우기 전 유예 기간. 이 안에 관리자가 글을 복구하면 이미지가 그대로다. 지난 뒤 1시간 안에 행·S3 객체를 지우고, 공개 URL 은 CloudFront 캐시(최대 24시간) 뒤 열리지 않는다. 양수가 아니면 기동 실패 |
| `UPLOADS_API_CALL_TIMEOUT` / `UPLOADS_API_CALL_ATTEMPT_TIMEOUT` | | | 입력(선택) | `5s` / `2s` (`app.uploads.api-call-timeout`·`api-call-attempt-timeout`, TASK-26#3). S3 호출(HeadObject·태그 제거·삭제) 하나의 전체(재시도 포함)·HTTP 시도별 상한. 넘거나 S3 오류면 503 `storage_unavailable`. 0·음수이거나 시도별 > 전체면 기동 실패 |
| `SCHEDULING_LOCK_AT_LEAST_FOR` | | | 입력(선택) | `PT10S` (`app.scheduling.lock-at-least-for`, TASK-26#5). 스케줄 작업(첨부 정리) ShedLock 락의 최소 유지 시간 — 짧게 끝난 작업을 다른 인스턴스가 바로 또 돌리지 않게 한다 |
| `RETENTION_INTERVAL` / `REFRESH_TOKEN_RETENTION` / `OTP_RETENTION` | | | 입력(선택) | `PT1H` / `P7D` / `P1D` (`app.retention.*`, TASK-27#1). 보존 기간 정리 주기, refresh 토큰 family 를 지우기 전 보존 기간(family 단위 — 살아 있는 토큰이 하나도 없고 마지막 토큰이 폐기·만료된 지 이 기간이 지난 family 만 통째로 지운다. 살아 있는 family 는 로테이션된 토큰까지 남아 재사용 감지가 유지된다), 만료된 OTP 챌린지 보존 기간(실제로는 발송 한도 창 24시간·`app.otp.phone-cooldown` 중 늦은 쪽까지 남긴다). 레이트리밋 기록·조회 기록·미사용 태그는 각 창(설정)에서 계산한다 — docs/api.md "보존 기간 정리". 양수가 아니면 기동 실패 |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_PORT` | | | (로컬 전용) | `board` / `board` / `board` / `5432`. 로컬 PostgreSQL 컨테이너. 운영 db 컨테이너 값은 render-env가 `DB_*`에서 만든다 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `MINIO_PORT` / `MINIO_CONSOLE_PORT` | | | (로컬 전용) | `minioadmin` / `minioadmin` / `9000` / `9001` |
| `BACKEND_URL` | | | (로컬 전용) | `http://localhost:8080`. `next dev`가 `/api`를 넘길 주소 |

배포 도구가 쓰는 값(앱 env 아님):

| 이름 | 어디 | 내용 |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `ECR_REPOSITORY_URL`, `WEB_BUCKET`, `DISTRIBUTION_ID`, `INSTANCE_ID` | GitHub 리포 **Variables**(시크릿 아님) | terraform output. 로컬은 `scripts/tf-outputs.sh`가 같은 이름으로 채운다 |
| `DRY_RUN`, `IMAGE_TAG`, `SKIP_BUILD`, `ALLOW_DIRTY`, `HEALTH_TIMEOUT` | 배포 스크립트 | [배포](#배포-task-17) |
| `SMOKE_BASE_URL` | `make smoke` | 스모크 대상(운영 `public_base_url`) |
| `E2E_ARGS`, `E2E_KEEP`, `E2E_STOP_TIMEOUT`, `E2E_*_PORT` | `make e2e` | [E2E](#e2e-playwright-task-18) |

## 비용 (us-east-1, 월, 대략)

가정: 온디맨드 가격(2026 기준 공개 요금, 세금 제외), 트래픽은 작은 실습 규모(CloudFront 월 수 GB, 요청 수십만 건), 프리 티어 크레딧 제외, 한 달 = 730시간.

| 항목 | 산식 | 월 |
|---|---|--:|
| EC2 t4g.small | $0.0168/h × 730 | ≈ $12.3 |
| EBS gp3 루트 20GB + 데이터 20GB | 40GB × $0.08 | ≈ $3.2 |
| EBS 스냅샷(DLM 7개, 증분) + 최종 스냅샷 | 변경분 수 GB × $0.05 | ≈ $0.3–1 |
| 퍼블릭 IPv4(EIP) | $0.005/h × 730 | ≈ $3.7 |
| CloudFront | 상시 무료 한도(월 1TB·1천만 요청) 안 | ≈ $0 |
| S3(web·uploads·state) | 수 GB + 요청 | < $0.5 |
| ECR | 이미지 20개 × 약 0.15GB × $0.10/GB | ≈ $0.3 |
| CloudWatch Logs | 수집 $0.50/GB, 14일 보관 | < $1 |
| SSM Parameter Store(Standard), KMS `aws/ssm` | 무료(표준 파라미터·관리형 키) | $0 |
| Cognito | 소규모 MAU는 무료 구간 **(요금제 확인 필요)** | ≈ $0 |
| **합계(기본)** | | **≈ $20–21** |
| (선택) Route 53 hosted zone + 도메인 | $0.50/zone + 도메인 연 $10–15 | ≈ $0.5 + 도메인 |
| (선택) SMS | 건당(한국행 단가) + 발신 번호 월 요금 — [sms-setup](docs/sms-setup.md#5-비용-메모) | 사용량 |

- 가장 큰 항목은 EC2와 IPv4다. 쓰지 않을 때 비용을 줄이려면 인스턴스를 멈춘다(EBS와 EIP 요금은 계속 나간다). 완전히 없애려면 아래 정리 순서를 따른다.
- 단가는 바뀐다. 적용 전에 [AWS Pricing Calculator](https://calculator.aws/)로 다시 계산하고, **AWS Budgets** 월 예산 알림을 건다.

### 비용 추정 (Infracost, TASK-66)

위 표는 레거시 `infra/main` 기준이다. 실배포 플랫폼(`infra/platform`, decision-11 추정 약 $22~27/월)은 Infracost 로 본다.
가격은 Infracost 호스팅 가격 API 로 조회한다 — 보내는 것은 리소스 메타데이터(종류·크기·리전)뿐이고 상태·자격 증명은 보내지 않는다.

- 설정: `infracost.yml`(프로젝트 `platform` = `infra/platform`, 비교용 `main` = `infra/main`, 둘 다 `envs/prod.tfvars`) +
  usage 파일은 프로젝트마다 따로 — `infracost-usage.yml`(platform: 가동 시간·데이터 전송량), `infracost-usage-main.yml`(main: 가동 시간만).
  사용량 전용 리소스(`aws_data_transfer`)는 usage 파일을 쓰는 프로젝트마다 더해져서 한 파일을 같이 쓰면 두 번 센다.
- 버전: Infracost **v0.10.47** 로 고정했다. 새 CLI(v2, infracost/cli)가 나와 있다 — CI 에 기대기 전(TASK-52)에 v0.10 가격 API 지원이
  계속되는지 다시 확인한다.
- **스팟 보정**: Infracost v0.10.x 는 `aws_instance` 에 `spot_price` 가 있을 때만 스팟 단가를 쓴다. 노드는 `instance_market_options`
  로 스팟이라 그대로면 온디맨드(t4g.large 약 $49/월)로 계산된다. 그래서 usage 파일의 노드 `monthly_hrs` 를 "온디맨드 환산 시간"
  (730 × 스팟 상한 $0.026 / 온디맨드 $0.0672 ≈ 282)으로 둔다 — 표의 시간은 실제 가동 시간이 아니다. 단가가 바뀌면 usage 파일의
  주석 값과 `monthly_hrs` 를 같이 바꾼다(`make test` 가 산식을 확인한다). 키를 등록한 뒤 실측 결과는 TASK-66 노트에 남긴다.
- **계산하지 않는 것**: 데이터 볼륨 DLM 스냅샷(`aws_dlm_lifecycle_policy` 미지원 — 증분 수 GB × $0.05, $0.5 미만), Cognito MAU·SMS
  건당 요금(사용량), Budgets(2개까지 무료). 상한($30)은 이 몫을 남겨 둔 값이다. 실제 금액은 TASK-52 apply 뒤 Cost Explorer 로 맞춰 본다.

```bash
make cost                   # breakdown 표 + 상한 검사(scripts/cost-guard.sh, platform 프로젝트). 상한: COST_LIMIT=25 > COST_LIMIT_USD > 30
make cost-diff              # main 대비 차이. make cost-diff COST_BASE=<브랜치·커밋>
```

- 맥에서 실행하면 Rocky 개발 VM 안에서 돈다(`make test` 와 같은 `vm_proxy_if_mac`). infracost(v0.10.47 고정)는 `deploy/rocky/install.sh` dev 프로필이 설치한다.
- **API 키(무료) 등록 — VM 안에서 한 번**: `ssh rocky-dev` 로 들어가 `infracost configure set api_key <키>`
  (키는 [Infracost](https://www.infracost.io/) 가입 후 받는다. VM 의 `~/.config/infracost/credentials.yml` 에 저장된다).
  저장소·`.env` 에는 넣지 않는다. 맥 셸의 `INFRACOST_API_KEY` 는 VM 으로 넘기지 않는다(ssh 인자·환경에 키를 싣지 않는다 — VM 에 등록한
  키를 쓴다). 키가 없으면(`INFRACOST_API_KEY` 도, 저장된 키도 없으면) 경고만 내고 건너뛴다 — `make test` 는 키 없이
  샘플 JSON 으로 상한 검사를 오프라인 테스트한다(`scripts/test/cost_test.sh`).
- 상한 검사: `scripts/cost-guard.sh [--project <이름>] <breakdown·diff JSON> [상한]` — `totalMonthlyCost` 가 상한(인자 > `COST_LIMIT_USD` > 30)을
  넘으면 실패(1), 형식 오류도 실패(2).
- `make cost-diff`: 기준 ref 의 `infra` 를 `git archive` 로 꺼내 breakdown 한다. 기준에 없는 프로젝트(예: `infra/platform` 도입 전의
  main)는 빼고 기준 비용 0 으로 비교하고, 남는 프로젝트가 없으면 빈 breakdown(비용 0)과 비교한다.
- CI(`.github/workflows/cost.yml`): `infra/**`·비용 설정·스크립트를 바꾼 PR 에서 `scripts/cost.sh diff origin/<기준>` → PR 코멘트(표·차이,
  다시 돌면 같은 코멘트를 고친다) → 상한 검사. 리포 secret `INFRACOST_API_KEY` 가 필요하고, 없으면(포크 PR 포함) 경고만 내고 건너뛴다.

## 정리 (terraform destroy)

순서와 명령은 [docs/first-deploy.md 12단계](docs/first-deploy.md#12-정리-destroy-순서)에 있다. 요약:

1. `cognito_deletion_protection = false`(필요하면 `uploads_force_destroy = true`)로 apply.
2. web 버킷을 비우고, ECR 이미지를 지운다.
3. `terraform -chdir=infra/main destroy -var-file=envs/prod.tfvars`. 데이터 EBS는 최종 스냅샷을 남긴다.
4. Terraform 밖의 것을 지운다: SSM `/board/prod/*`, 스냅샷, SMS 발신 번호·등록, GitHub Variables, 제공자 콘솔의 콜백.
5. state 버킷(bootstrap)은 `prevent_destroy`라 남는다. 지우려면 `infra/bootstrap/README.md`를 따른다.

## 개발 워크플로

이 리포는 [backlog.md](https://github.com/MrLesk/Backlog.md) 태스크 단위로 작업한다.
`.claude-code-agile-hooks.json`에 테스트 명령(`make test`)과 test-first 대상 경로(`testFirst`)가 설정되어 있다.

> 커밋 훅의 `make test`는 Claude Code 훅 시간 제한(600초)을 받는다. 시간 제한을 넘으면 훅이 취소되고 커밋은 **막히지 않고 통과한다**(fail-open).
> 맥에서 VM 안 전체 `make test`는 600초를 넘을 수 있으므로, 훅 설정을 바꾸기 전까지는 커밋 전에 저장소 루트에서 `make test`를 직접 돌려
> 종료 코드 0 을 확인한 뒤 커밋한다.
