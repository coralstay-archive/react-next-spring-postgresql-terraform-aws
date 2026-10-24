#!/usr/bin/env bash
# 통합 테스트 (TASK-36#6): Testcontainers(PostgreSQL·MinIO)를 쓰는 백엔드 테스트만 돌린다. make test-integration 이 부른다.
# 시작할 때 로컬 k3d 클러스터가 떠 있으면 멈추고 출력한다 — 8GB 에서 클러스터와 Testcontainers 를 같이 띄우지 않는다(make test 도 같다).
# 나중에 통합 태그로 나눈 테스트(k3d 스모크 등)가 생기면 여기에 더한다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT/scripts/k3d.sh" stop-if-running "make test-integration"

echo "==> backend (Testcontainers)"
(cd "$ROOT/backend" && ./gradlew test --console=plain)
