#!/usr/bin/env bash
# 스팀 카탈로그 수집 — 마스터 노트북에서만 실행
#
#   ./21-catalog.sh now            지금 한 번 (전체 · 약 15~20분)
#   ./21-catalog.sh now --pages 2  앞 2페이지만 (확인용)
#   ./21-catalog.sh install        systemd 타이머 등록 (매일 09:00 KST)
#   ./21-catalog.sh setup-key      DB 비밀번호를 두는 곳 안내
#   ./21-catalog.sh status         타이머 · 최근 실행 · DB 행 수
#   ./21-catalog.sh remove         타이머 해제
#
# 무엇을 하는가
#   스팀 전체 카탈로그(약 18.5만 개)를 받아 서비스 DB 의 tag · game ·
#   game_tag 를 채운다. 리뷰 수집기가 "어떤 게임을 긁을지" 를 이 표에서 읽는다.
#
# 어디서 도는가 · 왜 여기인가
#   받고 파싱하는 일은 마스터 노트북이 하고, 서버1 에는 SSH 터널로 결과만
#   넣는다. 서버1 은 서비스가 도는 곳이다 — 480MB 를 받아 18만 행을 UPSERT
#   하면 4코어 16GB 를 서비스와 나눠 쓰게 된다.
#
# 왜 분산하지 않는가
#   186콜이다. 리뷰 수집은 수백만 콜이라 노트북 5대로 나눠야 하지만,
#   카탈로그는 나눠봐야 한 대당 37콜이다. 한 IP 로 연속 호출해도 안 막힌다.
#
# 왜 매일 전량을 다시 받는가 (증분하지 않는다)
#   정작 매일 바뀌는 것은 기존 게임의 리뷰 수·긍정률이다. "새 appid 만"
#   받으면 그쪽을 통째로 놓친다. 전체가 20분이라 증분 로직을 붙여 얻는 게 없다.
#   실패하면 그냥 다시 돌리면 된다 — 워터마크도 재시작 지점도 필요 없다.
set -uo pipefail

REPO=$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)
JAR=${JAR:-$HOME/thispatch/catalog.jar}

# 서버1 접속 — 환경변수로 덮어쓸 수 있다
DB_HOST=${DB_HOST:-j15a202.p.ssafy.io}
DB_SSH_USER=${DB_SSH_USER:-ubuntu}
DB_SSH_KEY=${DB_SSH_KEY:-$HOME/.ssh/J15A202T.pem}
DB_NAME=${DB_NAME:-thispatch}
DB_USER=${DB_USER:-thispatch}
LOCAL_PORT=${LOCAL_PORT:-15432}

# ⚠ 비밀번호는 저장소에 넣지 않는다. 마스터의 파일에서 읽는다.
#   서버1 infra/.env 의 POSTGRES_PASSWORD 와 같은 값이다.
PW_FILE=${PW_FILE:-$HOME/.thispatch/service-db-password}

CMD=${1:-now}; shift || true

banner() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# ── 터널을 열고 닫는다 ────────────────────────────────────────
# ⚠ ssh -f 로 띄우면 안 된다. 부모가 끝나면서 SIGHUP 으로 같이 죽는다.
#   같은 셸의 자식으로 두고, 끝날 때 직접 닫는다.
TUNNEL_PID=""
open_tunnel() {
  pkill -f "$LOCAL_PORT:127.0.0.1:5432" 2>/dev/null
  ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
      -i "$DB_SSH_KEY" -L "$LOCAL_PORT:127.0.0.1:5432" \
      "$DB_SSH_USER@$DB_HOST" &
  TUNNEL_PID=$!
  local i
  for i in $(seq 1 20); do
    ss -ltn 2>/dev/null | grep -q ":$LOCAL_PORT " && return 0
    sleep 1
  done
  echo "  [실패] 터널이 열리지 않았습니다. 열쇠를 확인하세요: $DB_SSH_KEY" >&2
  return 1
}
close_tunnel() { [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null; }
trap close_tunnel EXIT

case "$CMD" in
now)
  if [ ! -f "$JAR" ]; then
    echo "  jar 가 없습니다: $JAR"
    echo "  먼저 만들어 올리세요:"
    echo "    cd $REPO && ./gradlew :catalog:build"
    echo "    cp catalog/build/libs/thispatch-catalog.jar $JAR"
    exit 1
  fi
  if [ ! -f "$PW_FILE" ]; then
    echo "  DB 비밀번호 파일이 없습니다. 먼저:  $0 setup-key" >&2
    exit 1
  fi

  banner "터널"
  open_tunnel || exit 1
  echo "  $DB_HOST:5432 → 127.0.0.1:$LOCAL_PORT"

  banner "수집"
  DB_URL="jdbc:postgresql://127.0.0.1:$LOCAL_PORT/$DB_NAME" \
  DB_USER="$DB_USER" \
  DB_PASSWORD="$(tr -d '\r\n' < "$PW_FILE")" \
  java -jar "$JAR" "$@"
  RC=$?

  close_tunnel
  exit $RC
  ;;

setup-key)
  banner "DB 비밀번호 두는 곳"
  mkdir -p "$(dirname "$PW_FILE")"; chmod 700 "$(dirname "$PW_FILE")"
  echo "  파일   $PW_FILE"
  echo "  권한   600 (본인만 읽기)"
  echo
  echo "  서버1 의 값을 그대로 옮깁니다:"
  echo "    ssh -i $DB_SSH_KEY $DB_SSH_USER@$DB_HOST \\"
  echo "      'grep ^POSTGRES_PASSWORD= ~/thispatch/infra/.env | cut -d= -f2' \\"
  echo "      > $PW_FILE && chmod 600 $PW_FILE"
  echo
  if [ -f "$PW_FILE" ]; then
    echo "  지금 상태: 있음 ($(stat -c %a "$PW_FILE"))"
  else
    echo "  지금 상태: 없음"
  fi
  ;;

install)
  if [ "$(id -u)" = 0 ]; then echo "sudo 없이 그냥 실행하세요." >&2; exit 1; fi
  banner "systemd 타이머 등록"
  SELF=$(readlink -f "$0")
  JAVA_BIN=$(command -v java)
  sudo tee /etc/systemd/system/thispatch-catalog.service >/dev/null <<UNIT
[Unit]
Description=디스패치 스팀 카탈로그 수집 (서비스 DB 로)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$USER
Environment=PATH=$(dirname "$JAVA_BIN"):/usr/local/bin:/usr/bin:/bin
Environment=JAR=$JAR
Environment=DB_HOST=$DB_HOST
Environment=DB_SSH_USER=$DB_SSH_USER
Environment=DB_SSH_KEY=$DB_SSH_KEY
Environment=DB_NAME=$DB_NAME
Environment=DB_USER=$DB_USER
Environment=LOCAL_PORT=$LOCAL_PORT
Environment=PW_FILE=$PW_FILE
# 전체가 15~20분이다. 넉넉히 잡되 무한정 매달려 있지는 않게 한다.
TimeoutStartSec=45min
ExecStart=/usr/bin/env bash $SELF now
UNIT

  # ⚠ 새벽이 아니라 오전 9시다.
  #
  #   서버였다면 트래픽이 적은 새벽에 두는 것이 맞다. 그런데 이건 노트북이다.
  #   교육장 노트북은 밤에 꺼져 있어서 새벽에 잡아두면 매일 안 돈다.
  #   Persistent=true 로 "켜지면 한 번 돈다" 를 기대할 수도 있지만, 그러면
  #   출근해서 노트북을 켜는 순간 20분짜리 작업이 시작돼 그날 첫 작업과
  #   대역을 다툰다. 애초에 사람이 있는 시간에 두는 편이 낫다.
  #
  #   시각에 Asia/Seoul 을 명시한다. 시스템 시간대가 바뀌어도 안 흔들린다.
  sudo tee /etc/systemd/system/thispatch-catalog.timer >/dev/null <<'UNIT'
[Unit]
Description=디스패치 카탈로그 수집 타이머 (매일 09:00 KST)

[Timer]
OnCalendar=*-*-* 09:00:00 Asia/Seoul
# 9시에 노트북이 아직 안 켜졌으면, 켜진 뒤에 한 번 돈다.
Persistent=true
# 5대가 동시에 깨어나며 같은 순간에 몰리지 않게 조금 흩뜨린다.
RandomizedDelaySec=5min

[Install]
WantedBy=timers.target
UNIT

  sudo systemctl daemon-reload
  sudo systemctl enable --now thispatch-catalog.timer
  echo "  등록 완료. 다음 실행:"
  systemctl list-timers thispatch-catalog.timer --no-pager | sed -n '1,2p' | sed 's/^/    /'
  ;;

status)
  banner "타이머"
  systemctl list-timers thispatch-catalog.timer --no-pager 2>/dev/null | sed -n '1,2p' | sed 's/^/  /'
  banner "최근 실행"
  journalctl -u thispatch-catalog -n 25 --no-pager 2>/dev/null \
    | sed 's/.*env\[[0-9]*\]: //' | tail -20 | sed 's/^/  /'
  banner "DB 행 수"
  if [ -f "$PW_FILE" ]; then
    open_tunnel >/dev/null 2>&1 && {
      PGPASSWORD="$(tr -d '\r\n' < "$PW_FILE")" psql -w -h 127.0.0.1 -p "$LOCAL_PORT" \
        -U "$DB_USER" -d "$DB_NAME" -tAc \
        "select '  game      '||count(*) from game union all select '  game_tag  '||count(*) from game_tag union all select '  tag       '||count(*) from tag" 2>/dev/null
      close_tunnel
    }
  else
    echo "  비밀번호 파일이 없어 확인하지 못했습니다."
  fi
  ;;

remove)
  banner "타이머 해제"
  sudo systemctl disable --now thispatch-catalog.timer 2>/dev/null
  sudo rm -f /etc/systemd/system/thispatch-catalog.service /etc/systemd/system/thispatch-catalog.timer
  sudo systemctl daemon-reload
  echo "  해제했습니다. jar 와 비밀번호 파일은 그대로 둡니다."
  ;;

*)
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
  ;;
esac
