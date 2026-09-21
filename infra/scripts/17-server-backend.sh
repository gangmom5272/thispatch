#!/usr/bin/env bash
# 서버1 (j15a202.p.ssafy.io) — 백엔드 컨테이너 배포
#
#   # 내 노트북에서 저장소를 서버로 보낸 뒤
#   ssh -i ~/.ssh/J15A202T.pem ubuntu@j15a202.p.ssafy.io
#   bash ~/thispatch/infra/scripts/17-server-backend.sh
#
# 무엇을 하나
#   1. 호스트 PostgreSQL 에 서비스 DB(thispatch)와 확장을 준비한다
#   2. 컨테이너 -> 호스트 DB 경로를 뚫는다 (pg_hba + UFW)
#   3. 이미지를 빌드하고 Redis 준비 후 backend 를 올린다
#
# ⚠ 저장소가 서버에 있어야 한다
#   Dockerfile 의 빌드 컨텍스트가 저장소 루트다. 멀티모듈이라 settings.gradle
#   과 common/ 이 없으면 :backend 를 빌드할 수 없다.
#   그래서 예전처럼 infra/ 폴더만 scp 하는 방식으로는 안 된다.
#   젠킨스 파이프라인이 붙으면 젠킨스가 clone 한 워크스페이스가 그 자리를 대신한다.
#
# ⚠ DB 는 컨테이너가 아니다
#   서버1 에는 PostgreSQL 17 이 네이티브로 돌고 있다. 컨테이너는
#   host.docker.internal(=도커 브리지 게이트웨이)로 호스트를 본다.
#
# 몇 번 돌려도 된다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA="$(dirname "$HERE")"
COMPOSE="$INFRA/compose.server.yaml"
ENV_FILE="$INFRA/.env"
PG_HBA=/etc/postgresql/17/main/pg_hba.conf

[ -f "$COMPOSE" ]  || { echo "compose 파일 없음: $COMPOSE" >&2; exit 1; }
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 이 없습니다. .env.example 을 복사해 채우세요." >&2; exit 1; }

set -a; . "$ENV_FILE"; set +a
: "${POSTGRES_DB:?.env 에 POSTGRES_DB 가 필요합니다}"
: "${POSTGRES_USER:?.env 에 POSTGRES_USER 가 필요합니다}"
: "${POSTGRES_PASSWORD:?.env 에 POSTGRES_PASSWORD 가 필요합니다}"
[ "$POSTGRES_PASSWORD" = "change-me" ] && { echo "POSTGRES_PASSWORD 가 템플릿 기본값입니다." >&2; exit 1; }

echo "── [1/6] DB 와 확장 ───────────────────────────────────"
if sudo -u postgres psql -tAc "select 1 from pg_database where datname='$POSTGRES_DB'" | grep -q 1; then
  echo "    $POSTGRES_DB 이미 있음"
else
  sudo -u postgres psql -q -c "CREATE DATABASE $POSTGRES_DB OWNER $POSTGRES_USER;"
  echo "    $POSTGRES_DB 생성 (owner=$POSTGRES_USER)"
fi

# ⚠ 확장은 여기서 슈퍼유저로 깐다.
#   V1__init.sql 첫 줄이 CREATE EXTENSION IF NOT EXISTS vector 인데,
#   이건 슈퍼유저만 할 수 있다. 앱 역할에 슈퍼유저를 줄 수는 없으므로
#   미리 깔아두면 마이그레이션이 IF NOT EXISTS 로 그냥 지나간다.
for ext in vector pg_trgm; do
  sudo -u postgres psql -d "$POSTGRES_DB" -q -c "CREATE EXTENSION IF NOT EXISTS $ext;"
done
sudo -u postgres psql -d "$POSTGRES_DB" -tAc   "select '    '||extname||' '||extversion from pg_extension order by extname"

echo
echo "── [2/6] pg_hba — 도커 대역 허용 ──────────────────────"
# ⚠ sudo 로 읽어야 한다. 일반 사용자는 읽을 수 없어서 grep 이 항상 실패하고,
#   그러면 다시 돌릴 때마다 같은 줄이 중복으로 쌓인다. (실측)
add_hba() {
  local cidr="$1" note="$2"
  if sudo grep -qE "^host +$POSTGRES_DB +$POSTGRES_USER +${cidr//./\.}" "$PG_HBA"; then
    echo "    $cidr 이미 있음"
  else
    printf '# %s
host    %s    %s    %s     scram-sha-256
'       "$note" "$POSTGRES_DB" "$POSTGRES_USER" "$cidr" | sudo tee -a "$PG_HBA" >/dev/null
    echo "    $cidr 추가"
    HBA_CHANGED=1
  fi
}
HBA_CHANGED=0
add_hba "127.0.0.1/32"  "호스트 자신 (psql 로 확인할 때)"
add_hba "172.17.0.0/16" "도커 기본 브리지"
add_hba "172.18.0.0/16" "compose 전용 네트워크 thispatch_default"
[ "$HBA_CHANGED" -eq 1 ] && { sudo systemctl reload postgresql; echo "    postgresql reload"; }

echo
echo "── [3/6] UFW — 도커 대역에서 오는 5432 ────────────────"
# ⚠ allow 만 쓴다. delete · reset · default 는 쓰지 않는다 (14 스크립트와 같은 이유).
# ⚠ 출발지를 반드시 붙인다. 'ufw allow 5432' 로 쓰면 전 세계에 DB 가 열린다.
sudo ufw allow from 172.17.0.0/16 to any port 5432 proto tcp comment 'docker bridge -> host postgres'
sudo ufw allow from 172.18.0.0/16 to any port 5432 proto tcp comment 'thispatch_default -> host postgres'
sudo ufw status | grep 5432 | sed 's/^/    /'
if sudo ufw status | grep -E '^5432' | grep -qE 'Anywhere'; then
  echo "5432 가 Anywhere 로 열려 있습니다. 즉시 닫으세요." >&2; exit 1
fi

echo
echo "── [4/6] 컨테이너에서 DB 에 붙는지 먼저 확인 ──────────"
# ⚠ 빌드는 몇 분 걸린다. 접속이 안 되는 상태로 빌드부터 하면 시간만 버린다.
#    호스트에서 psql 로 확인하는 것은 의미가 없다 — 출발지 IP 가 달라서
#    pg_hba 판정이 달라진다. 반드시 컨테이너에서 봐야 한다. (실측)
sudo docker run --rm --network thispatch_default   --add-host host.docker.internal:host-gateway   -e PGPASSWORD="$POSTGRES_PASSWORD"   postgres:17-alpine   psql -h host.docker.internal -U "$POSTGRES_USER" -d "$POSTGRES_DB"        -tAc "select '    OK '||current_user||'@'||current_database()||' 출발지='||inet_client_addr()"   || { echo "컨테이너에서 DB 에 못 붙습니다. 위 [2] [3] 을 확인하세요." >&2; exit 1; }

echo
echo "── [5/6] 이미지 빌드 ──────────────────────────────────"
cd "$INFRA"
sudo docker compose -f "$COMPOSE" build backend
sudo docker images thispatch/backend --format '    {{.Repository}}:{{.Tag}}  {{.Size}}'

echo
echo "── [6/6] Redis 준비 후 backend 를 올린다 ─────────────"
# ⚠ --no-deps 를 쓴다. 안 쓰면 jenkins 까지 다시 만든다.
sudo docker compose -f "$COMPOSE" up -d --no-deps --wait --wait-timeout 60 redis
sudo docker compose -f "$COMPOSE" up -d --no-deps backend

echo
echo "    기동을 기다린다 (Flyway 마이그레이션 포함)"
for i in $(seq 1 30); do
  st=$(sudo docker inspect backend --format '{{.State.Health.Status}}' 2>/dev/null || echo none)
  [ "$st" = healthy ] && break
  sleep 5
done
sudo docker ps --format '    {{.Names}}  {{.Status}}'

echo
echo "── 검증 ───────────────────────────────────────────────"
echo "    Flyway 적용 내역"
sudo -u postgres psql -d "$POSTGRES_DB" -tAc   "select '      '||version||'  '||script||'  '||success from flyway_schema_history order by installed_rank"   2>/dev/null || echo "      (없음 — 마이그레이션이 돌지 않았다)"

echo
echo "    컨테이너 직접"
curl -sS -m 10 -o /dev/null -w "      127.0.0.1:18081/ → %{http_code}
" http://127.0.0.1:18081/ || true
echo "    nginx 경유"
curl -sS -m 10 -o /dev/null -w "      /api/ → %{http_code}
" https://j15a202.p.ssafy.io/api/ || true
echo
echo "  401 이면 정상이다 — 요청이 백엔드까지 닿았고 Spring Security 가 막은 것이다."
echo "  502 면 컨테이너가 죽었거나 포트가 안 맞는 것이다. docker logs backend 를 보라."
