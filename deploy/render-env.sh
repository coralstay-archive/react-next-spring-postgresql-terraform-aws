#!/usr/bin/env bash
# EC2 에서 실행: SSM Parameter Store <path>/* → compose env 파일 (TASK-17#3). remote-deploy.sh 가 배포마다 부른다.
#   render-env.sh <ssm-path> <out-dir>     (AWS_REGION 필요, 인스턴스 role 은 <ssm-path>/* 읽기·복호화만 가진다)
# 만드는 파일(권한 600, 원자적 교체):
#   <out-dir>/app.env  api 컨테이너 env: 파라미터 이름의 마지막 부분 = 키. DB_URL·DB_USERNAME 이 없으면 compose 의 db 서비스 기본값
#   <out-dir>/db.env   db 컨테이너 env: POSTGRES_USER·POSTGRES_PASSWORD(= DB_PASSWORD)·POSTGRES_DB
# 값 표기: compose env_file 의 큰따옴표 형식. \ " $ 와 줄바꿈을 이스케이프해 값이 그대로 들어간다(보간 없음).
# 필수 키(DB_PASSWORD, JWT_SECRET, OTP_HASH_KEY)가 없으면 실패한다 — 개발용 기본값으로 운영이 뜨지 않게.
set -euo pipefail

ssm_path="${1:?usage: render-env.sh <ssm-path> <out-dir>}"
out_dir="${2:?usage: render-env.sh <ssm-path> <out-dir>}"
: "${AWS_REGION:?AWS_REGION}"
ssm_path="${ssm_path%/}"

umask 077
mkdir -p "$out_dir"
json="$(mktemp "$out_dir/.params.XXXXXX")"
trap 'rm -f "$json" "$out_dir"/.app.env.* "$out_dir"/.db.env.*' EXIT

aws ssm get-parameters-by-path --region "$AWS_REGION" --path "$ssm_path" --recursive --with-decryption --output json >"$json"

python3 - "$json" "$ssm_path" "$out_dir" <<'PY'
import json, os, re, sys, tempfile

src, prefix, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
params = json.load(open(src)).get("Parameters", [])

env = {}
for p in params:
    name = p["Name"]
    if not name.startswith(prefix + "/"):
        continue
    key = name[len(prefix) + 1:]
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
        print(f"render-env: 건너뜀(키 형식이 아님): {name}", file=sys.stderr)
        continue
    env[key] = p["Value"]

missing = [k for k in ("DB_PASSWORD", "JWT_SECRET", "OTP_HASH_KEY") if not env.get(k)]
if missing:
    sys.exit(f"render-env: 필수 파라미터가 없다: {', '.join(prefix + '/' + k for k in missing)} (put-secrets.sh --generate-missing)")

db_name = env.pop("DB_NAME", "board")
env.setdefault("DB_USERNAME", "board")
env.setdefault("DB_URL", f"jdbc:postgresql://db:5432/{db_name}")

def quote(v: str) -> str:
    if "\r" in v or "\0" in v:
        raise SystemExit("render-env: 값에 CR/NUL 이 있다")
    v = v.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n")
    return f'"{v}"'

def write(name: str, values: dict) -> None:
    fd, tmp = tempfile.mkstemp(prefix=f".{name}.", dir=out_dir)
    with os.fdopen(fd, "w") as f:
        f.write("# render-env.sh 가 SSM 에서 만든 파일. 직접 고치지 말고 put-secrets.sh 로 바꾼다\n")
        for k in sorted(values):
            f.write(f"{k}={quote(values[k])}\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, os.path.join(out_dir, name))

write("app.env", env)
write("db.env", {"POSTGRES_USER": env["DB_USERNAME"], "POSTGRES_PASSWORD": env["DB_PASSWORD"], "POSTGRES_DB": db_name})
print(f"render-env: {len(env)} keys → {out_dir}/app.env, db.env", file=sys.stderr)
PY
