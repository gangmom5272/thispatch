#!/usr/bin/env bash
# 서버1 (172.26.8.198) — PostgreSQL 17 + pgvector + pg_trgm
#
# 노트북 클러스터의 Spark 가 JDBC 로 여기에 적재하므로
# 외부(SSAFY 랜)에서 접속이 되어야 한다.
set -euo pipefail

DB_NAME=dispatch
DB_USER=dispatch
DB_PASS="${DB_PASS:-}"          # 호출할 때 환경변수로 넘긴다

if [ -z "$DB_PASS" ]; then
  echo "DB_PASS 환경변수가 필요합니다." >&2; exit 1
fi

echo "── [1/6] PGDG 저장소 추가 (기본 저장소는 16 이라) ──────"
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
     curl ca-certificates gnupg >/dev/null
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
     https://www.postgresql.org/media/keys/ACCC4CF8.asc
. /etc/os-release
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
  | sudo tee /etc/apt/sources.list.d/pgdg.list >/dev/null
sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq

echo "── [2/6] PostgreSQL 17 + 확장 설치 ────────────────────"
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
     postgresql-17 postgresql-client-17 postgresql-17-pgvector >/dev/null
psql --version
sudo systemctl enable --now postgresql@17-main
sudo systemctl is-active postgresql@17-main

PGDIR=/etc/postgresql/17/main

echo "── [3/6] 접속 허용 · 메모리 설정 ───────────────────────"
# 노트북 클러스터(SSAFY 랜)에서 붙어야 하므로 외부 리슨.
# 16GB 중 PostgreSQL 에 4GB 를 준다 (shared_buffers 2G).
sudo tee $PGDIR/conf.d/dispatch.conf >/dev/null <<'EOF'
listen_addresses = '*'
port = 5432
max_connections = 120

shared_buffers = 2GB
effective_cache_size = 6GB
work_mem = 16MB
maintenance_work_mem = 512MB

wal_compression = on
checkpoint_completion_target = 0.9
random_page_cost = 1.1          # EBS SSD

# 배치가 대량 INSERT 를 하므로
synchronous_commit = off

log_min_duration_statement = 3000
log_line_prefix = '%m [%p] %u@%d '
EOF
sudo mkdir -p $PGDIR/conf.d
grep -q "conf.d" $PGDIR/postgresql.conf || \
  echo "include_dir = 'conf.d'" | sudo tee -a $PGDIR/postgresql.conf >/dev/null

echo "── [4/6] pg_hba — SSAFY 랜에서 접속 허용 ───────────────"
# 70.12.0.0/16 = SSAFY 교육장 대역. 노트북 IP 가 바뀌어도 커버된다.
sudo sed -i '/dispatch-rule/d' $PGDIR/pg_hba.conf
sudo tee -a $PGDIR/pg_hba.conf >/dev/null <<EOF
# dispatch-rule
host    $DB_NAME    $DB_USER    70.12.0.0/16      scram-sha-256
host    $DB_NAME    $DB_USER    172.26.0.0/20     scram-sha-256
host    $DB_NAME    $DB_USER    127.0.0.1/32      scram-sha-256
EOF

echo "── [5/6] DB · 롤 생성 ─────────────────────────────────"
sudo -u postgres psql -qtAX <<EOF
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='$DB_USER') THEN
    CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASS';
  ELSE
    ALTER ROLE $DB_USER PASSWORD '$DB_PASS';
  END IF;
END \$\$;
EOF
sudo -u postgres psql -qtAX -c \
  "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1 || \
  sudo -u postgres createdb -O $DB_USER $DB_NAME
sudo -u postgres psql -qtAX -d $DB_NAME <<EOF
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
GRANT ALL ON SCHEMA public TO $DB_USER;
EOF

sudo systemctl restart postgresql@17-main
sleep 3

echo "── [6/6] 확인 ─────────────────────────────────────────"
sudo -u postgres psql -qtAX -d $DB_NAME -c \
  "SELECT extname||' '||extversion FROM pg_extension ORDER BY 1;"
echo -n "리슨: "; sudo ss -lntp 2>/dev/null | grep 5432 | head -1 || true
echo
echo "✔ PostgreSQL 17 준비 완료"
echo "  DB=$DB_NAME  USER=$DB_USER  PORT=5432"
echo "  노트북에서:  psql -h j15a202.p.ssafy.io -U $DB_USER -d $DB_NAME"
echo
echo "⚠ SSAFY 보안그룹에서 5432 인바운드(70.12.0.0/16)를 열어야"
echo "  노트북 클러스터가 붙을 수 있습니다."
