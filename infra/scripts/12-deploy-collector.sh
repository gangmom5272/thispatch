#!/usr/bin/env bash
# collector jar 를 워커 노트북에 뿌리고 띄운다 — 마스터에서 실행
#
#   bash 12-deploy-collector.sh build     jar 를 새로 빌드한다
#   bash 12-deploy-collector.sh deploy    워커에 뿌린다
#   bash 12-deploy-collector.sh install   워커에 systemd 유닛 등록 (부팅 시 자동 기동)
#   bash 12-deploy-collector.sh start     워커에서 띄운다
#   bash 12-deploy-collector.sh stop      워커에서 내린다
#   bash 12-deploy-collector.sh status    어느 워커가 떠 있나
#   bash 12-deploy-collector.sh log       워커 로그 꼬리 (N=20 으로 줄 수 조절)
#   bash 12-deploy-collector.sh run       매니저를 돌린다 (마스터에서)
#   bash 12-deploy-collector.sh all       build + deploy + start
#
# 워커 목록은 하둡의 workers 파일을 그대로 쓴다. 마스터 자신은 뺀다.
# 수집은 워커의 IP 에서 나가야 하고, 마스터까지 수집하면 나누는 일과 겹친다.
#
# 지금은 ssh 로 띄운다. 상시 운영으로 넘어가면 systemd 로 올린다
# (하둡 데몬처럼). 그때는 08-systemd-hadoop.sh 를 참고한다.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$ROOT/collector/build/libs/collector-0.0.1-SNAPSHOT.jar"

# 홈 기준 상대경로만 쓴다. ssh 는 홈에서 시작한다.
#
# "~/thispatch" 를 변수에 담아 리다이렉션에 쓰면 안 된다. bash 는 변수에서
# 나온 ~ 를 확장하지 않아서 "~" 라는 이름의 디렉터리가 생긴다.
# 2026-09-11 실제로 겪었다 — 로그가 엉뚱한 곳으로 가서 비어 보였다.
REMOTE_DIR="thispatch"
REMOTE_JAR="$REMOTE_DIR/collector.jar"
LOG_NAME="collector-worker.log"

# 프로세스는 PID 파일로 다룬다.
#
# pkill -f / pgrep -f 를 쓰면 안 된다. 원격에서 그 명령을 실행하는 bash 의
# 명령줄에도 같은 문자열이 들어 있어서 자기 자신을 잡는다.
# 2026-09-11 실제로 겪었다 — pkill 이 자기 셸을 죽여 java 가 시작도 못 했고,
# pgrep 은 없는 프로세스를 있다고 보고했다.
PID_NAME="collector.pid"

MASTER_IP="${MASTER_IP:-70.12.108.85}"
BATCH_DB_PASSWORD="${BATCH_DB_PASSWORD:-dispatch-batch-local}"
MQ_PASSWORD="${MQ_PASSWORD:-dispatch-mq-local}"

# ── 서비스 DB (서버1) — 매니저가 game 테이블을 읽을 때만 쓴다 ──────
#
# ⚠ 워커에게는 가지 않는다. 워커는 서버1 에 닿지도 않는다(실측).
#   매니저가 읽어서 appid 목록만 큐로 보낸다.
#
# 서버1 의 5432 는 밖에서 막혀 있어 SSH 터널을 지난다.
SERVICE_DB_PORT="${SERVICE_DB_PORT:-15432}"
SERVICE_DB_URL="${SERVICE_DB_URL:-jdbc:postgresql://127.0.0.1:$SERVICE_DB_PORT/thispatch}"
SERVICE_DB_USER="${SERVICE_DB_USER:-thispatch}"
SERVICE_DB_PW_FILE="${SERVICE_DB_PW_FILE:-$HOME/.thispatch/service-db-password}"
SERVICE_DB_SSH_KEY="${SERVICE_DB_SSH_KEY:-$HOME/.ssh/J15A202T.pem}"
SERVICE_DB_HOST="${SERVICE_DB_HOST:-j15a202.p.ssafy.io}"

# ⚠ 터널을 ssh -f 로 띄우면 부모가 끝나며 SIGHUP 으로 같이 죽는다.
#   같은 셸의 자식으로 두고 trap 으로 닫는다.
TUNNEL_PID=""
open_service_tunnel() {
  if ss -ltn 2>/dev/null | grep -q ":$SERVICE_DB_PORT "; then
    echo "  터널 이미 열려 있음 ($SERVICE_DB_PORT)"
    return 0
  fi
  ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30       -i "$SERVICE_DB_SSH_KEY" -L "$SERVICE_DB_PORT:127.0.0.1:5432"       ubuntu@"$SERVICE_DB_HOST" &
  TUNNEL_PID=$!
  local i
  for i in $(seq 1 20); do
    if ss -ltn 2>/dev/null | grep -q ":$SERVICE_DB_PORT "; then
      echo "  터널 열림 ($SERVICE_DB_PORT)"
      return 0
    fi
    sleep 1
  done
  echo "  [실패] 서비스 DB 터널이 열리지 않았습니다." >&2
  return 1
}
close_service_tunnel() { [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null; }
trap close_service_tunnel EXIT

SSH_OPTS="-o ConnectTimeout=6 -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"

banner() { echo; echo "══ $* ══════════════════════════════════"; }

workers() {
  local f="${HADOOP_CONF_DIR:-/opt/hadoop/etc/hadoop}/workers"
  if [ -f "$f" ]; then
    grep -vE "^[[:space:]]*(#|$)" "$f" | grep -vx "$MASTER_IP"
  else
    printf "%s\n" 70.12.246.76 70.12.247.103 70.12.247.106 70.12.247.164
  fi
}

CMD="${1:-status}"

case "$CMD" in

build)
  banner "빌드"
  export JAVA_HOME="${JAVA_HOME:-$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)}"
  ( cd "$ROOT" && ./gradlew :collector:build --console=plain -q ) || exit 1
  ls -lh "$JAR" | tr -s " " | cut -d" " -f5,9 | sed 's/^/  /'
  ;;

deploy)
  banner "배포"
  [ -f "$JAR" ] || { echo "  jar 가 없다. 먼저 build 하라." >&2; exit 1; }
  for w in $(workers); do
    printf "  %-16s " "$w"
    if ! timeout 20 ssh -n $SSH_OPTS "$w" "mkdir -p $REMOTE_DIR" 2>/dev/null; then
      echo "SSH 안 됨 (꺼졌거나 공개키 미등록)"
      continue
    fi
    if timeout 300 scp -q $SSH_OPTS "$JAR" "$w:$REMOTE_JAR" 2>/dev/null; then
      echo "$(timeout 15 ssh -n $SSH_OPTS "$w" "du -h $REMOTE_JAR | cut -f1" 2>/dev/null) 전송"
    else
      echo "전송 실패"
    fi
  done
  ;;

install)
  banner "워커에 systemd 유닛 등록"
  # ⚠ ssh 로 띄운 프로세스는 노트북을 껐다 켜면 사라진다.
  #   매일 09:00 배치가 도는데 아침에 워커가 없으면 조각만 큐에 쌓이고
  #   아무 일도 안 일어난다. 에러도 안 난다 — 매니저는 계속 기다린다.
  #   하둡 데몬과 같은 방식으로 systemd 에 올린다.
  for w in $(workers); do
    printf "  %-16s " "$w"
    out=$(timeout 40 ssh -n $SSH_OPTS "$w" "
      U=\$(id -un); G=\$(id -gn); H=\$HOME
      J=\$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)
      [ -f \$H/$REMOTE_JAR ] || { echo '배포 안 됨 — 먼저 deploy'; exit 0; }
      sudo -n tee /etc/systemd/system/thispatch-collector.service >/dev/null <<UNIT
[Unit]
Description=디스패치 리뷰 수집 워커
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=\$U
Group=\$G
WorkingDirectory=\$H/$REMOTE_DIR
Environment=JAVA_HOME=\$J
Environment=HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
Environment=BATCH_DB_HOST=$MASTER_IP
Environment=MQ_HOST=$MASTER_IP
Environment=BATCH_DB_PASSWORD=$BATCH_DB_PASSWORD
Environment=MQ_PASSWORD=$MQ_PASSWORD
ExecStart=\$J/bin/java -jar \$H/$REMOTE_JAR --spring.profiles.active=worker
# Wi-Fi 가 끊겨 죽는 경우가 있어 자동 재시작. 설정 오류로 무한 루프에
# 빠지지 않게 5분에 5회로 제한한다. (하둡 유닛과 같은 규칙)
Restart=on-failure
RestartSec=20
StartLimitBurst=5
StartLimitIntervalSec=300
LimitNOFILE=65536
SuccessExitStatus=143
TimeoutStopSec=60
KillMode=mixed

[Install]
WantedBy=multi-user.target
UNIT
      sudo -n systemctl daemon-reload
      sudo -n systemctl enable thispatch-collector.service >/dev/null 2>&1
      echo '등록 완료 (enable). 지금 도는 프로세스는 건드리지 않았다'
    " 2>&1 | tail -1)
    echo "${out:-SSH 안 됨}"
  done
  echo
  echo "  ⚠ 지금 ssh 로 떠 있는 워커는 그대로 둔다. 다음 재시작부터 systemd 가 맡는다."
  echo "     바로 넘기려면:  $0 stop && $0 start"
  ;;

start)
  banner "워커 기동"
  # systemd 유닛이 있으면 그것으로 띄운다 (install 로 등록해 둔 경우).
  # 없으면 예전 방식(ssh + setsid)으로 띄운다 — 그건 노트북을 껐다 켜면 사라진다.
  for w in $(workers); do
    printf "  %-16s " "$w"
    # setsid 로 떼어놓지 않으면 ssh 가 끊길 때 같이 죽는다.
    out=$(timeout 40 ssh -n $SSH_OPTS "$w" "
      if [ -f /etc/systemd/system/thispatch-collector.service ]; then
        sudo -n systemctl restart thispatch-collector.service
        sleep 3
        echo \"systemd \$(systemctl is-active thispatch-collector.service)\"
        exit 0
      fi
      cd $REMOTE_DIR 2>/dev/null || { echo '폴더 없음 — 먼저 deploy'; exit 0; }
      if [ -f $PID_NAME ]; then kill \$(cat $PID_NAME) 2>/dev/null; sleep 2; fi
      BATCH_DB_PASSWORD='$BATCH_DB_PASSWORD' MQ_PASSWORD='$MQ_PASSWORD' \
      BATCH_DB_HOST='$MASTER_IP' MQ_HOST='$MASTER_IP' \
      setsid nohup java -jar collector.jar --spring.profiles.active=worker \
        > $LOG_NAME 2>&1 < /dev/null &
      echo \$! > $PID_NAME
      sleep 2
      if kill -0 \$(cat $PID_NAME) 2>/dev/null; then echo \"기동 pid \$(cat $PID_NAME)\"
      else echo '바로 죽음 — log 로 확인하라'; fi
    " 2>&1 | tail -1)
    echo "${out:-SSH 안 됨}"
  done
  echo
  echo "  기동 대기 (25초)..."
  sleep 25
  "$0" status
  ;;

stop)
  banner "워커 정지"
  for w in $(workers); do
    printf "  %-16s " "$w"
    # ⚠ 둘 다 끈다. systemd 로 옮기는 중에는 두 가지가 같이 떠 있을 수 있다.
    #   유닛만 멈추면 ssh 로 띄운 옛 프로세스가 살아남아 큐를 계속 집어간다.
    #   2026-09-14 실제로 겪었다 — "정지" 라고 나왔는데 수집이 계속 돌았다.
    out=$(timeout 30 ssh -n $SSH_OPTS "$w" "
      MSG=''
      if [ -f /etc/systemd/system/thispatch-collector.service ]; then
        sudo -n systemctl stop thispatch-collector.service 2>/dev/null
        MSG=\"systemd \$(systemctl is-active thispatch-collector.service)\"
      fi
      cd $REMOTE_DIR 2>/dev/null || { echo \"\${MSG:-배포 안 됨}\"; exit 0; }
      if [ -f $PID_NAME ] && kill \$(cat $PID_NAME) 2>/dev/null; then
        rm -f $PID_NAME; MSG=\"\$MSG · 옛 프로세스 정지\"
      fi
      echo \"\${MSG:-떠 있지 않음}\"
    " 2>&1 | tail -1)
    echo "${out:-SSH 안 됨}"
  done
  ;;

status)
  banner "워커 상태"
  for w in $(workers); do
    printf "  %-16s " "$w"
    out=$(timeout 25 ssh -n $SSH_OPTS "$w" "
      if [ -f /etc/systemd/system/thispatch-collector.service ]; then
        echo \"systemd \$(systemctl is-active thispatch-collector.service) / \$(systemctl is-enabled thispatch-collector.service 2>/dev/null)\"
        exit 0
      fi
      cd $REMOTE_DIR 2>/dev/null || { echo '배포 안 됨'; exit 0; }
      if [ -f $PID_NAME ] && kill -0 \$(cat $PID_NAME) 2>/dev/null; then
        if grep -q 'Started CollectorApplication' $LOG_NAME 2>/dev/null; then echo \"떠 있음 (pid \$(cat $PID_NAME)) — systemd 아님\"
        else echo '기동 중'; fi
      else echo '없음'; fi
    " 2>&1 | tail -1)
    echo "${out:-SSH 안 됨}"
  done
  ;;

log)
  for w in $(workers); do
    banner "$w"
    timeout 20 ssh -n $SSH_OPTS "$w" \
      "tail -${N:-12} $REMOTE_DIR/$LOG_NAME 2>/dev/null || echo '로그 없음'" 2>&1 \
      | sed 's/^/  /'
  done
  ;;

run)
  banner "매니저 실행"
  [ -f "$JAR" ] || { echo "  jar 가 없다." >&2; exit 1; }
  export JAVA_HOME="${JAVA_HOME:-$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)}"

  # APPIDS 를 주면 그것만 수집한다 (리허설).
  # 안 주면 매니저가 서비스 DB 의 game 테이블을 읽는다 — 터널이 필요하다.
  SERVICE_DB_PASSWORD=""
  if [ -z "${APPIDS:-}" ]; then
    [ -f "$SERVICE_DB_PW_FILE" ] || {
      echo "  서비스 DB 비밀번호가 없습니다: $SERVICE_DB_PW_FILE" >&2
      echo "  리허설이면 APPIDS=730,570 처럼 직접 줘도 됩니다." >&2
      exit 1; }
    open_service_tunnel || exit 1
    SERVICE_DB_PASSWORD="$(tr -d '\r\n' < "$SERVICE_DB_PW_FILE")"
  else
    echo "  APPIDS 를 직접 받았습니다: $APPIDS (game 테이블을 읽지 않습니다)"
  fi

  BATCH_DB_PASSWORD="$BATCH_DB_PASSWORD" MQ_PASSWORD="$MQ_PASSWORD" \
  SERVICE_DB_URL="$SERVICE_DB_URL" SERVICE_DB_USER="$SERVICE_DB_USER" \
  SERVICE_DB_PASSWORD="$SERVICE_DB_PASSWORD" \
  "$JAVA_HOME/bin/java" -jar "$JAR" \
    --spring.profiles.active=manager \
    --spring.batch.job.enabled=true \
    --thispatch.collect.grid-size="${GRID:-4}" \
    ${APPIDS:+--thispatch.collect.appids="$APPIDS"} \
    "dt=${DT:-$(date +%Y-%m-%d)}" 2>&1 \
    | grep --line-buffered -E "Job: |Step: |수집 대상|appid 를|ERROR|Exception" \
    | sed -u 's/^/  /'
  close_service_tunnel
  ;;

all)
  "$0" build && "$0" deploy && "$0" start
  ;;

*)
  sed -n '2,12p' "$0"
  exit 1
  ;;
esac
exit 0
