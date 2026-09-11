#!/usr/bin/env bash
# 마스터 노트북에 배치 기반을 깐다 — PostgreSQL(배치 메타데이터) + RabbitMQ
#
#   bash 11-master-batch-infra.sh
#
# 왜 마스터에 두는가
#   Spring Batch 원격 파티셔닝은 마스터가 작업을 나눠 보내고 워커가 받아서
#   수집한다. 이때 워커도 실행 상태를 메타데이터 DB 에 쓴다.
#
#   그 DB 를 서버1(EC2)에 두면 워커 5대가 EC2 의 5432 에 닿아야 하는데,
#   UFW 가 막고 있고 여는 순간 AWS 방화벽이 1024~65535 를 이미 허용하고
#   있어서 전 세계에 열린다. SSH 터널 5개를 상시 유지하는 방법도 있지만
#   노트북은 절전·무선 끊김이 잦아 깨지기 쉽다.
#
#   마스터에 두면 워커가 랜 안에서만 통신한다. UFW 도 터널도 필요 없다.
#   마스터가 꺼지면 배치가 안 도는 건 어차피 마찬가지다 — Job 을 돌리는 게
#   마스터이기 때문이다. 그래서 가용성 손해가 없다.
#
# 서비스 DB(서버1)와는 다른 DB 다
#   서버1      game · recent_review · daily_stat ...   서비스 데이터
#   여기       BATCH_JOB_EXECUTION ...                 배치 살림살이
#
#   배치 메타데이터 테이블은 Spring Batch 가 알아서 만든다. 우리 ERD 에
#   들어가지 않는다. 우리는 빈 DB 하나만 만들어 준다.
set -uo pipefail

PG_VER="${PG_VER:-17}"          # 서버1 과 같은 계열로 맞춘다
DB_NAME="${DB_NAME:-dispatch_batch}"
DB_USER="${DB_USER:-dispatch}"
DB_PASS="${DB_PASS:-dispatch-batch-local}"

MQ_USER="${MQ_USER:-dispatch}"
MQ_PASS="${MQ_PASS:-dispatch-mq-local}"
MQ_VHOST="${MQ_VHOST:-dispatch}"

LAN="${LAN:-70.12.0.0/16}"      # 교육장 대역. 이 밖에서는 못 붙는다

banner() { echo; echo "══ $* ══════════════════════════════════"; }

MYIP=$(ip route get 8.8.8.8 2>/dev/null | grep -oE "src [0-9.]+" | cut -d" " -f2 | head -1)
[ -n "$MYIP" ] || MYIP=$(hostname -I | tr -s " " | cut -d" " -f1)

banner "이 노드"
echo "  IP    $MYIP"
echo "  대역  $LAN 에서만 접속을 허용한다"

# ── PostgreSQL ────────────────────────────────────────────────
banner "PostgreSQL $PG_VER 설치"
if command -v psql >/dev/null 2>&1; then
  echo "  이미 있다: $(psql --version)"
else
  # 우분투 기본 저장소는 14 다. 서버1 이 17 이므로 PGDG 를 쓴다.
  sudo install -d /usr/share/postgresql-common/pgdg
  sudo curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
       https://www.postgresql.org/media/keys/ACCC4CF8.asc
  echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
    | sudo tee /etc/apt/sources.list.d/pgdg.list >/dev/null
  sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
       "postgresql-$PG_VER" "postgresql-client-$PG_VER" >/dev/null
  echo "  설치 완료: $(psql --version)"
fi

PGCONF="/etc/postgresql/$PG_VER/main"
if [ ! -d "$PGCONF" ]; then
  echo "  [실패] $PGCONF 가 없다. 설치를 확인하라." >&2
  exit 1
fi

banner "PostgreSQL 설정"
# 워커가 랜에서 붙어야 하므로 로컬만 듣던 것을 전체로 바꾼다.
# 밖에서 오는 것은 Hyper-V 방화벽이 70.12.0.0/16 으로 막는다.
sudo sed -i "s/^#\?listen_addresses.*/listen_addresses = '*'/" "$PGCONF/postgresql.conf"

# 배치 메타데이터는 쓰기가 잦지만 양이 작다. 기본값으로 충분하다.
# 노트북이라 메모리를 크게 잡지 않는다 — YARN 이 10GB 를 쓰고 있다.
sudo tee "$PGCONF/conf.d/dispatch-batch.conf" >/dev/null <<'PGEOF'
shared_buffers       = 256MB
work_mem             = 8MB
max_connections      = 60
synchronous_commit   = off
PGEOF

# 교육장 대역에서 비밀번호로 붙게 한다
HBA="$PGCONF/pg_hba.conf"
# pg_hba.conf 는 root 만 읽을 수 있다. sudo 없이 grep 하면 항상 실패해서
# 다시 돌릴 때마다 같은 줄이 쌓인다. (2026-09-11 실제로 겪음)
if ! sudo grep -q "dispatch-batch" "$HBA"; then
  echo "# dispatch-batch — 워커 노트북이 랜에서 붙는다" | sudo tee -a "$HBA" >/dev/null
  echo "host    $DB_NAME    $DB_USER    $LAN    scram-sha-256" | sudo tee -a "$HBA" >/dev/null
fi

sudo systemctl enable postgresql >/dev/null 2>&1
sudo systemctl restart postgresql
sleep 2
echo "  postgresql: $(systemctl is-active postgresql)"

banner "DB 와 사용자 만들기"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1 \
  || sudo -u postgres psql -q -c "CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASS';"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1 \
  || sudo -u postgres psql -q -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
echo "  DB   $DB_NAME"
echo "  계정 $DB_USER"
echo "  확인 $(PGPASSWORD=$DB_PASS psql -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" -tAc 'select version()' 2>&1 | head -1)"

# ── RabbitMQ ──────────────────────────────────────────────────
banner "RabbitMQ 설치"
if command -v rabbitmqctl >/dev/null 2>&1; then
  echo "  이미 있다"
else
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq rabbitmq-server >/dev/null
  echo "  설치 완료"
fi
sudo systemctl enable rabbitmq-server >/dev/null 2>&1
sudo systemctl restart rabbitmq-server
sleep 5
echo "  rabbitmq-server: $(systemctl is-active rabbitmq-server)"

banner "RabbitMQ 계정과 vhost"
sudo rabbitmqctl list_users 2>/dev/null | grep -q "^$MQ_USER" \
  || sudo rabbitmqctl add_user "$MQ_USER" "$MQ_PASS" >/dev/null
sudo rabbitmqctl list_vhosts 2>/dev/null | grep -qx "$MQ_VHOST" \
  || sudo rabbitmqctl add_vhost "$MQ_VHOST" >/dev/null
sudo rabbitmqctl set_permissions -p "$MQ_VHOST" "$MQ_USER" ".*" ".*" ".*" >/dev/null
sudo rabbitmqctl set_user_tags "$MQ_USER" management >/dev/null

# 브라우저로 큐 상태를 보려면 필요하다. 문제 생겼을 때 눈으로 보는 게 빠르다.
sudo rabbitmq-plugins enable rabbitmq_management >/dev/null 2>&1
echo "  사용자 $MQ_USER · vhost $MQ_VHOST"
echo "  관리 화면 http://$MYIP:15672"

# 기본 guest 계정은 localhost 에서만 되지만, 그래도 지운다.
sudo rabbitmqctl delete_user guest >/dev/null 2>&1 && echo "  기본 guest 계정 삭제"

# ── 마무리 ────────────────────────────────────────────────────
banner "리슨 확인"
ss -tlnp 2>/dev/null | grep -E ":5432|:5672|:15672" | tr -s " " | cut -d" " -f4 | sed 's/^/  /'

banner "다음에 할 것"
cat <<EOF
  1) 관리자 PowerShell 에서 방화벽 포트를 연다
       .\\infra\\scripts\\06-firewall.ps1

  2) collector 의 application.yaml 에 이렇게 적는다
       spring.datasource.url: jdbc:postgresql://$MYIP:5432/$DB_NAME
       spring.rabbitmq.host:  $MYIP
       spring.rabbitmq.virtual-host: $MQ_VHOST

  3) 워커에서 닿는지 확인
       nc -vz $MYIP 5432
       nc -vz $MYIP 5672

  비밀번호는 코드에 넣지 말고 환경변수나 .env 로 넘긴다.
EOF
exit 0
