#!/usr/bin/env bash
# 서비스 DB 백업 — 서버1 에서 실행, 서버2A 로 보낸다
#
#   bash 18-db-backup.sh now        지금 한 번 백업
#   bash 18-db-backup.sh install    systemd 타이머 등록 (매일 새벽)
#   bash 18-db-backup.sh list       로컬·원격에 쌓인 백업 확인
#   bash 18-db-backup.sh verify     최신 백업이 진짜 읽히는지 확인
#   bash 18-db-backup.sh restore    복구 절차 출력 (실행하지는 않는다)
#   bash 18-db-backup.sh setup-key  백업 전용 열쇠 만들기
#   bash 18-db-backup.sh remove     타이머 해제
#
# 왜 필요한가
#   HDFS 는 백업도 만들고 복구 훈련까지 했는데(10번) 서비스 DB 는 맨몸이었다.
#   여기에는 HDFS 로 다시 만들 수 없는 것이 들어간다 — 회원, 찜한 게임,
#   패치 분석 결과 같은 것들이다. 수집 원본은 HDFS 에 있으니 다시 만들 수
#   있지만, 사람이 만든 데이터는 날아가면 끝이다.
#
# 무엇을 담는가
#   <db>.dump    pg_dump -Fc. 스키마와 데이터 전부
#   globals.sql  역할(role)과 비밀번호 해시. 이게 없으면 새 서버에서
#                복구할 때 thispatch 역할이 없어 소유자를 못 붙인다
#   meta.txt     버전 · 확장 · 테이블별 행 수. 복구 후 대조용
#
# ⚠ 열쇠를 따로 쓴다
#   서버1 에는 J15A202T.pem 이 없다(일부러 안 뒀다). 대신 백업 전용
#   ed25519 키를 서버1 에서 만들어 서버2A 에 등록했다.
#   서버2A 의 authorized_keys 에 이런 제한을 걸어 두었다.
#     restrict   포트포워딩 · 에이전트포워딩 · pty 금지
#     from=...   서버1 의 사설·공인 주소에서 온 것만
#   마스터 키를 서버1 에 복사하지 않은 이유 — 서버1 이 뚫리면 서버2A 까지
#   같이 넘어가기 때문이다. 이 키는 서버2A 에서 한 줄 지우면 바로 무효가 된다.
#
# 한계 (알고 쓰자)
#   pg_dump 는 시작한 순간의 일관된 스냅샷을 뜬다. 그 뒤의 변경은 없다.
#   하루 한 번이면 최악의 경우 하루치가 빠진다. 그보다 촘촘해야 하면
#   WAL 아카이빙을 해야 하는데, 지금 규모(수십 MB)에는 과하다.
set -uo pipefail

DBS=${DBS:-thispatch}
BACKUP_HOST=${BACKUP_HOST:-j15a202a.p.ssafy.io}
BACKUP_USER=${BACKUP_USER:-ubuntu}
BACKUP_KEY=${BACKUP_KEY:-$HOME/.ssh/thispatch-backup}
BACKUP_DIR=${BACKUP_DIR:-/home/ubuntu/thispatch-backup/postgres}

LOCAL_DIR=${LOCAL_DIR:-$HOME/db-backup}
KEEP=${KEEP:-14}
KEEP_REMOTE=${KEEP_REMOTE:-30}

SSH_OPTS="-o ConnectTimeout=10 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"

CMD=${1:-now}
banner() { echo; echo "== $* =========================================="; }

remote_ready() {
  [ -f "$BACKUP_KEY" ] || return 1
  ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" true 2>/dev/null
}

case "$CMD" in

now)
  banner "서비스 DB 백업"
  systemctl is-active --quiet postgresql || { echo "  PostgreSQL 이 떠 있지 않습니다."; exit 1; }

  TS=$(date -u +%Y%m%d-%H%M)
  WORK=$(mktemp -d /tmp/dbbak.XXXXXX)
  trap 'rm -rf "$WORK"' EXIT
  STAGE="$WORK/db-$TS"
  mkdir -p "$STAGE"

  for db in $DBS; do
    if ! sudo -u postgres psql -tAc "select 1 from pg_database where datname='$db'" | grep -q 1; then
      echo "  [건너뜀] $db 가 없습니다"
      continue
    fi
    # -Fc = custom 포맷. 압축돼 있고, pg_restore 로 테이블 하나만 골라 되살릴 수 있다.
    if ! sudo -u postgres pg_dump -Fc -Z 6 "$db" > "$STAGE/$db.dump" 2>"$STAGE/$db.err"; then
      echo "  [실패] $db 덤프"; sed 's/^/         /' "$STAGE/$db.err"; exit 1
    fi
    rm -f "$STAGE/$db.err"
    # ⚠ 크기만 보면 안 된다. 잘린 파일도 크기는 그럴듯하다.
    #   목차를 실제로 열어서 객체가 들어 있는지 확인한다.
    # ⚠ pg_restore -l 은 sudo -u postgres 로 돌리지 않는다.
    #   덤프 파일은 ubuntu 소유의 700 폴더 안에 있어 postgres 가 못 읽는다.
    #   목차를 읽는 데는 DB 접속이 필요 없다 — 파일만 본다. (실측)
    N=$(pg_restore -l "$STAGE/$db.dump" 2>/dev/null | grep -c '^[0-9]')
    echo "  $db  $(du -h "$STAGE/$db.dump" | cut -f1)  객체 $N 개"
    [ "$N" -gt 0 ] || { echo "  [실패] $db 덤프를 읽을 수 없습니다"; exit 1; }
  done

  # 역할. 새 서버에서 복구할 때 thispatch 역할이 없으면 소유자를 못 붙인다.
  # ⚠ 비밀번호 해시가 들어 있다. 묶음 파일 권한을 600 으로 둔다.
  sudo -u postgres pg_dumpall --globals-only > "$STAGE/globals.sql" 2>/dev/null
  echo "  globals.sql  역할 $(grep -c 'CREATE ROLE' "$STAGE/globals.sql") 개"

  {
    echo "시각(UTC)   $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "호스트      $(hostname)"
    echo "PostgreSQL  $(sudo -u postgres psql -tAc 'select version()')"
    for db in $DBS; do
      echo
      echo "[$db]"
      sudo -u postgres psql -d "$db" -tAc "select '  확장  '||extname||' '||extversion from pg_extension order by extname" 2>/dev/null
      sudo -u postgres psql -d "$db" -tAc "select '  행    '||relname||'  '||n_live_tup from pg_stat_user_tables order by relname" 2>/dev/null
    done
  } > "$STAGE/meta.txt"
  echo "  meta.txt     $(wc -l < "$STAGE/meta.txt") 줄"

  mkdir -p "$LOCAL_DIR"; chmod 700 "$LOCAL_DIR"
  TAR="$LOCAL_DIR/db-$TS.tar.gz"
  tar -czf "$TAR" -C "$WORK" "db-$TS"
  chmod 600 "$TAR"
  SUM=$(sha256sum "$TAR" | cut -d' ' -f1)
  echo "  묶음         $(basename "$TAR")  $(du -h "$TAR" | cut -f1)"
  echo "  sha256       ${SUM:0:16}..."

  if remote_ready; then
    ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "mkdir -p $BACKUP_DIR && chmod 700 $BACKUP_DIR" 2>/dev/null
    if scp -q -i "$BACKUP_KEY" $SSH_OPTS "$TAR" "$BACKUP_USER@$BACKUP_HOST:$BACKUP_DIR/" 2>/dev/null; then
      RSUM=$(ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "sha256sum $BACKUP_DIR/$(basename "$TAR") | cut -d' ' -f1" 2>/dev/null)
      if [ "$RSUM" = "$SUM" ]; then
        echo "  원격 전송    $BACKUP_HOST  체크섬 일치"
      else
        echo "  [실패] 원격 체크섬 불일치. 전송이 깨졌습니다."; exit 1
      fi
      ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "ls -1t $BACKUP_DIR/db-*.tar.gz 2>/dev/null | tail -n +$((KEEP_REMOTE+1)) | xargs -r rm -f" 2>/dev/null
    else
      echo "  [실패] scp 가 실패했습니다. 로컬 사본만 남습니다."; exit 1
    fi
  else
    echo "  [실패] $BACKUP_HOST 에 닿지 않습니다."
    echo "         서버1 이 죽으면 백업도 같이 죽습니다. 열쇠를 확인하세요:"
    echo "         bash 18-db-backup.sh setup-key"
    exit 1
  fi

  ls -1t "$LOCAL_DIR"/db-*.tar.gz 2>/dev/null | tail -n +$((KEEP+1)) | xargs -r rm -f
  echo
  echo "  완료."
  ;;

list)
  banner "로컬  $LOCAL_DIR"
  ls -lht "$LOCAL_DIR"/db-*.tar.gz 2>/dev/null | head -"$KEEP" || echo "  (없음)"
  banner "원격  $BACKUP_HOST:$BACKUP_DIR"
  if remote_ready; then
    ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "ls -lht $BACKUP_DIR/db-*.tar.gz 2>/dev/null | head -10" || echo "  (없음)"
  else
    echo "  (닿지 않음)"
  fi
  banner "타이머"
  systemctl status thispatch-dbbackup.timer --no-pager 2>/dev/null | sed -n '1,6p' || echo "  (등록 안 됨)"
  ;;

verify)
  banner "최신 백업이 진짜 읽히는지"
  T=$(ls -1t "$LOCAL_DIR"/db-*.tar.gz 2>/dev/null | head -1)
  [ -n "$T" ] || { echo "  로컬 백업이 없습니다."; exit 1; }
  echo "  대상  $(basename "$T")  $(du -h "$T" | cut -f1)"
  W=$(mktemp -d /tmp/dbver.XXXXXX); trap 'rm -rf "$W"' EXIT
  tar -xzf "$T" -C "$W" || { echo "  [실패] 압축이 깨졌습니다."; exit 1; }
  D=$(ls -d "$W"/db-* | head -1)
  for f in "$D"/*.dump; do
    N=$(pg_restore -l "$f" 2>/dev/null | grep -c '^[0-9]')
    if [ "$N" -gt 0 ]; then R=OK; else R=깨짐; fi
    echo "  $(basename "$f")  객체 $N 개  $R"
  done
  echo
  echo "  --- meta.txt ---"
  sed 's/^/  /' "$D/meta.txt"
  ;;

setup-key)
  banner "백업 전용 열쇠 만들기"
  KF=$HOME/.ssh/thispatch-backup
  if [ -f "$KF" ]; then
    echo "  이미 있습니다: $KF"
  else
    ssh-keygen -t ed25519 -f "$KF" -N "" -C "thispatch-db-backup@$(hostname)" >/dev/null
    echo "  만들었습니다: $KF"
  fi
  chmod 600 "$KF"
  PRIV=$(hostname -I | awk '{print $1}')
  PUBIP=$(curl -s -m 5 https://checkip.amazonaws.com)
  PUBKEY=$(cat "$KF".pub)
  echo
  echo "  서버2A 의 ~/.ssh/authorized_keys 에 아래 한 줄을 넣으세요."
  echo "  (마스터 키가 있는 노트북에서 서버2A 에 접속해서 넣습니다)"
  echo
  # 큰따옴표를 %c 로 찍는다. 셸 인용부호 지옥을 피하려는 것이다.
  awk -v p="$PRIV" -v i="$PUBIP" -v k="$PUBKEY" 'BEGIN{q=sprintf("%c",34); print "    restrict,from=" q p "," i q " " k}'
  echo
  echo "  restrict = 포트포워딩·pty 금지 · from = 이 서버에서 온 것만 허용"
  ;;
drill)
  banner "복구 훈련 — 백업이 진짜 되살아나는지"
  # ⚠ 서비스 DB 는 건드리지 않는다. 훈련용 DB 를 따로 만들었다 지운다.
  #   verify 는 "파일이 읽히나" 까지만 본다. 이건 "진짜 되살아나나" 를 본다.
  #   그리고 로컬 사본이 아니라 '서버2A 에 있는 것' 을 가져와서 한다.
  #   로컬만 되면 서버1 이 죽었을 때 아무 소용이 없기 때문이다.
  DRILL_DB=${DRILL_DB:-thispatch_drill}
  SRC_DB=${SRC_DB:-thispatch}

  remote_ready || { echo "  $BACKUP_HOST 에 닿지 않습니다."; exit 1; }
  T=$(mktemp -d /tmp/drill.XXXXXX); chmod 755 "$T"
  trap 'rm -rf "$T"' EXIT

  LATEST=$(ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "ls -1t $BACKUP_DIR/db-*.tar.gz 2>/dev/null | head -1")
  [ -n "$LATEST" ] || { echo "  원격에 백업이 없습니다."; exit 1; }
  echo "  원본  $LATEST"
  scp -q -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST:$LATEST" "$T/" || { echo "  가져오기 실패"; exit 1; }
  tar -xzf "$T"/db-*.tar.gz -C "$T"
  D=$(ls -d "$T"/db-*/ | head -1)
  chmod -R a+rX "$T"

  sudo -u postgres psql -q -c "DROP DATABASE IF EXISTS $DRILL_DB;" 2>/dev/null
  sudo -u postgres psql -q -c "CREATE DATABASE $DRILL_DB OWNER thispatch;"
  sudo -u postgres psql -q -d "$DRILL_DB" -c "CREATE EXTENSION IF NOT EXISTS vector;"
  sudo -u postgres psql -q -d "$DRILL_DB" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"

  # ⚠ 여기서 "must be owner of extension" 에러가 두 줄 뜬다. 무해하다.
  #   확장은 위에서 postgres 가 미리 깔았는데, 복구는 thispatch 역할로 돈다.
  #   덤프 안의 COMMENT ON EXTENSION 을 thispatch 가 실행할 권한이 없어서다.
  #   주석 한 줄을 못 단 것뿐이고 테이블·데이터와는 무관하다.
  sudo -u postgres pg_restore -d "$DRILL_DB" --no-owner --role=thispatch "$D/$SRC_DB.dump" 2>&1 | grep -v 'COMMENT ON EXTENSION' | head -8

  echo
  echo "  원본과 복구본 대조"
  printf '   %-28s %8s %8s' 테이블 원본 복구본; echo
  BAD=0
  for t in $(sudo -u postgres psql -tAc "select table_name from information_schema.tables where table_schema='public' order by table_name" -d "$SRC_DB"); do
    a=$(sudo -u postgres psql -tAc "select count(*) from public.$t" -d "$SRC_DB" 2>/dev/null)
    b=$(sudo -u postgres psql -tAc "select count(*) from public.$t" -d "$DRILL_DB" 2>/dev/null)
    if [ "$a" = "$b" ]; then m=""; else m="  <-- 다름"; BAD=$((BAD+1)); fi
    printf '   %-28s %8s %8s' "$t" "$a" "${b:-없음}"; echo "$m"
  done

  echo
  # 구조 비교. pg_dump 는 매번 무작위 \restrict 토큰을 넣으므로 그 줄은 뺀다.
  sudo -u postgres pg_dump --schema-only --no-owner "$SRC_DB"   | grep -v restrict > "$T/a.sql" 2>/dev/null
  sudo -u postgres pg_dump --schema-only --no-owner "$DRILL_DB" | grep -v restrict | sed "s/$DRILL_DB/$SRC_DB/g" > "$T/b.sql" 2>/dev/null
  if diff -q "$T/a.sql" "$T/b.sql" >/dev/null; then
    echo "  스키마 완전히 같음"
  else
    echo "  스키마 차이:"; diff "$T/a.sql" "$T/b.sql" | head -20 | sed 's/^/    /'; BAD=$((BAD+1))
  fi

  sudo -u postgres psql -q -c "DROP DATABASE $DRILL_DB;"
  echo "  훈련용 DB 정리 완료"
  echo
  if [ "$BAD" -eq 0 ]; then
    echo "  통과 — 이 백업으로 되살릴 수 있다."
  else
    echo "  [주의] $BAD 군데가 다릅니다. 백업을 믿으면 안 됩니다."
    exit 1
  fi
  ;;

install)
  [ "$(id -u)" = 0 ] && { echo "sudo 없이 그냥 실행하세요." >&2; exit 1; }
  banner "systemd 타이머 등록"
  SELF=$(readlink -f "$0")
  sudo tee /etc/systemd/system/thispatch-dbbackup.service >/dev/null <<UNIT
[Unit]
Description=디스패치 서비스 DB 백업 (서버2A 로 전송)
After=postgresql.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$USER
Environment=DBS=$DBS
Environment=BACKUP_HOST=$BACKUP_HOST
Environment=BACKUP_USER=$BACKUP_USER
Environment=BACKUP_KEY=$BACKUP_KEY
Environment=BACKUP_DIR=$BACKUP_DIR
Environment=LOCAL_DIR=$LOCAL_DIR
Environment=KEEP=$KEEP
Environment=KEEP_REMOTE=$KEEP_REMOTE
ExecStart=/usr/bin/env bash $SELF now
UNIT

  sudo tee /etc/systemd/system/thispatch-dbbackup.timer >/dev/null <<'UNIT'
[Unit]
Description=디스패치 DB 백업 — 매일 새벽 4시 (한국시간)

[Timer]
# ⚠ 이 서버의 시간대는 UTC 다.
#   그냥 04:00 이라고 쓰면 한국시간 낮 1시에 돈다.
#   systemd 는 뒤에 시간대를 적으면 그걸 따르므로 명시한다.
OnCalendar=*-*-* 04:00:00 Asia/Seoul
# 서버가 꺼져 있어 걸렀으면 켜지자마자 한 번 따라잡는다
Persistent=true
RandomizedDelaySec=300

[Install]
WantedBy=timers.target
UNIT

  mkdir -p "$LOCAL_DIR"; chmod 700 "$LOCAL_DIR"
  sudo systemctl daemon-reload
  sudo systemctl enable --now thispatch-dbbackup.timer
  echo "  등록 완료. 다음 실행:"
  systemctl list-timers thispatch-dbbackup.timer --no-pager | sed -n '1,3p'
  echo
  echo "  지금 한 번 돌려보려면:  sudo systemctl start thispatch-dbbackup.service"
  echo "  로그:                   journalctl -u thispatch-dbbackup -n 40"
  ;;

remove)
  banner "타이머 해제"
  sudo systemctl disable --now thispatch-dbbackup.timer 2>/dev/null
  sudo rm -f /etc/systemd/system/thispatch-dbbackup.service /etc/systemd/system/thispatch-dbbackup.timer
  sudo systemctl daemon-reload
  echo "  해제했습니다. 백업 파일은 지우지 않았습니다."
  ;;

restore)
  banner "복구 절차 — 읽고 손으로 하세요"
  cat <<'GUIDE'
  자동화하지 않는다. 잘못 돌리면 멀쩡한 DB 를 덮어쓴다.

  0) 어느 시점으로 되돌릴지 먼저 정한다
       bash 18-db-backup.sh list
       bash 18-db-backup.sh verify

  1) 백업 가져와서 풀기
       scp -i ~/.ssh/thispatch-backup ubuntu@j15a202a.p.ssafy.io:~/thispatch-backup/postgres/db-<시각>.tar.gz .
       tar -xzf db-<시각>.tar.gz && cd db-<시각>
       cat meta.txt        # 이 백업이 어떤 상태였는지 먼저 읽는다

  2) 백엔드를 멈춘다. 안 멈추면 복구 중에 새 연결이 들어온다.
       cd ~/thispatch/infra && sudo docker compose -f compose.server.yaml stop backend

  3) 지금 상태를 한 번 더 떠 두고, 빈 DB 를 새로 만든다
     ⚠ 이 단계를 건너뛰면 되돌릴 곳이 없어진다.
       sudo -u postgres pg_dump -Fc thispatch > ~/before-restore.dump
       sudo -u postgres psql -c "DROP DATABASE thispatch;"
       sudo -u postgres psql -c "CREATE DATABASE thispatch OWNER thispatch;"

  4) 역할이 없는 새 서버라면 globals 를 먼저 넣는다
     (이미 thispatch 역할이 있으면 건너뛴다. 에러가 나도 무시해도 된다)
       sudo -u postgres psql -f globals.sql

  5) 확장을 슈퍼유저로 먼저 깐다
     ⚠ 덤프 안의 CREATE EXTENSION 은 일반 역할로 실행되지 않는다.
       sudo -u postgres psql -d thispatch -c "CREATE EXTENSION IF NOT EXISTS vector;"
       sudo -u postgres psql -d thispatch -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"

  6) 되살리기
       sudo -u postgres pg_restore -d thispatch --no-owner --role=thispatch thispatch.dump

     ⚠ 여기서 이런 에러가 두 줄 뜼다. 무해하다.
         ERROR:  must be owner of extension pg_trgm
         ERROR:  must be owner of extension vector
       확장은 5) 에서 postgres 가 깔았는데 복구는 thispatch 역할로 돌아서,
       덤프 안의 COMMENT ON EXTENSION 을 실행할 권한이 없어서 나는 것이다.
       설명 주석 한 줄을 못 달았을 뿐, 테이블과 데이터와는 상관없다.
       (2026-09-13 복구 훈련에서 확인)

  7) 대조. meta.txt 의 행 수와 지금 행 수를 비교한다.
       sudo -u postgres psql -d thispatch -c "select relname, n_live_tup from pg_stat_user_tables order by relname"
       sudo -u postgres psql -d thispatch -c "select version, script, success from flyway_schema_history order by installed_rank"

  8) 백엔드를 다시 올린다
       sudo docker compose -f compose.server.yaml start backend
       curl -s -o /dev/null -w '%{http_code}' https://j15a202.p.ssafy.io/api/ ; echo
       # 401 이면 정상이다

  ⚠ Flyway 기록까지 같이 복구된다.
    복구한 DB 의 마이그레이션 버전과 지금 코드가 어긋나면 백엔드가 뜨다 멈춘다.
    오래된 백업으로 되돌릴 때는 코드도 같은 시점으로 맞춰야 한다.
GUIDE
  ;;

*)
  sed -n '2,10p' "$0"
  exit 1
  ;;
esac
