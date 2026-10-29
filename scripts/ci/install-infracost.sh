#!/usr/bin/env bash
# infracost CLI 고정 버전 설치 (TASK-66#1). CI(.github/workflows/cost.yml)가 쓴다.
# 개발 VM 은 deploy/rocky/install.sh dev 프로필이 같은 버전·arm64 sha256 으로 설치한다(scripts/test/cost_test.sh 가 두 값을 비교한다).
# 버전을 올릴 때는 릴리스의 infracost-linux-<arch>.tar.gz.sha256 값으로 여기와 install.sh 를 같이 바꾼다.
#   scripts/ci/install-infracost.sh <설치 디렉터리>   같은 버전이 이미 있으면 건너뛴다
set -euo pipefail

INFRACOST_VERSION="0.10.47"
INFRACOST_SHA256_ARM64="1db33dddcac989f39687fa9bf00ed9d8cce4268450578848e5bf6287ad8a7b44"
INFRACOST_SHA256_AMD64="df098d5565b78cc23de57e1dac9f2a02491691322cbd690dca9b197e3e02fe93"

log() { echo "==> [infracost] $*" >&2; }
die() { echo "ERROR: [infracost] $*" >&2; exit 1; }

dest="${1:-}"
[ -n "$dest" ] || die "사용: $0 <설치 디렉터리>"
case "$(uname -m)" in
  aarch64 | arm64) arch=arm64; sha="$INFRACOST_SHA256_ARM64" ;;
  x86_64 | amd64) arch=amd64; sha="$INFRACOST_SHA256_AMD64" ;;
  *) die "고정하지 않은 아키텍처: $(uname -m) (linux arm64·amd64 만)" ;;
esac

if [ -x "$dest/infracost" ] && "$dest/infracost" --version 2>/dev/null | grep -qF "v$INFRACOST_VERSION"; then
  log "v$INFRACOST_VERSION 이 이미 있다 — 건너뛴다"
  exit 0
fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
log "v$INFRACOST_VERSION (linux-$arch) 받기"
curl -fsSL --retry 3 -o "$tmp/infracost.tar.gz" \
  "https://github.com/infracost/infracost/releases/download/v$INFRACOST_VERSION/infracost-linux-$arch.tar.gz"
echo "$sha  $tmp/infracost.tar.gz" | sha256sum -c - >/dev/null || die "sha256 불일치: infracost-linux-$arch.tar.gz"
tar -xzf "$tmp/infracost.tar.gz" -C "$tmp" "infracost-linux-$arch"
mkdir -p "$dest"
install -m 0755 "$tmp/infracost-linux-$arch" "$dest/infracost"
log "설치: $dest/infracost"
