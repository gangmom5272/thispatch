#!/usr/bin/env bash
# collector jar 를 워커 노트북에 뿌리고 띄운다 — 마스터에서 실행
#
#   bash 12-deploy-collector.sh build     jar 를 새로 빌드한다
#   bash 12-deploy-collector.sh deploy    워커에 뿌린다
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
# "~/dispatch" 를 변수에 담아 리다이렉션에 쓰면 안 된다. bash 는 변수에서
# 나온 ~ 를 확장하지 않아서 "~" 라는 이름의 디렉터리가 생긴다.
# 2026-09-11 실제로 겪었다 — 로그가 엉뚱한 곳으로 가서 비어 보였다.
REMOTE_DIR="dispatch"
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

start)
  banner "워커 기동"
  for w in $(workers); do
    printf "  %-16s " "$w"
    # setsid 로 떼어놓지 않으면 ssh 가 끊길 때 같이 죽는다.
    out=$(timeout 30 ssh -n $SSH_OPTS "$w" "
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
    out=$(timeout 20 ssh -n $SSH_OPTS "$w" "
      cd $REMOTE_DIR 2>/dev/null || { echo '배포 안 됨'; exit 0; }
      if [ -f $PID_NAME ] && kill \$(cat $PID_NAME) 2>/dev/null; then rm -f $PID_NAME; echo 정지
      else echo '떠 있지 않음'; fi
    " 2>&1 | tail -1)
    echo "${out:-SSH 안 됨}"
  done
  ;;

status)
  banner "워커 상태"
  for w in $(workers); do
    printf "  %-16s " "$w"
    out=$(timeout 20 ssh -n $SSH_OPTS "$w" "
      cd $REMOTE_DIR 2>/dev/null || { echo '배포 안 됨'; exit 0; }
      if [ -f $PID_NAME ] && kill -0 \$(cat $PID_NAME) 2>/dev/null; then
        if grep -q 'Started CollectorApplication' $LOG_NAME 2>/dev/null; then echo \"떠 있음 (pid \$(cat $PID_NAME))\"
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
  BATCH_DB_PASSWORD="$BATCH_DB_PASSWORD" MQ_PASSWORD="$MQ_PASSWORD" \
  "$JAVA_HOME/bin/java" -jar "$JAR" \
    --spring.profiles.active=manager \
    --spring.batch.job.enabled=true \
    --dispatch.collect.grid-size="${GRID:-4}" \
    "dt=${DT:-$(date +%Y-%m-%d)}" 2>&1 \
    | grep -E "Job: |Step: |ERROR|Exception" | sed 's/^/  /'
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
