#!/usr/bin/env bash
# 노드·서비스 상태 감시 — 이상이 생기면 Mattermost 로 알린다
#
#   bash 20-mattermost-alert.sh now       지금 한 번 검사 (바뀌었을 때만 보냄)
#   bash 20-mattermost-alert.sh now -f    바뀌지 않았어도 무조건 보냄
#   bash 20-mattermost-alert.sh check     검사만 하고 화면에 출력 (안 보냄)
#   bash 20-mattermost-alert.sh test      Mattermost 연결 시험
#   bash 20-mattermost-alert.sh install   systemd 타이머 등록 (10분마다)
#   bash 20-mattermost-alert.sh remove    타이머 해제
#
# 왜 필요한가
#   지금은 무언가 죽어도 아무도 모른다. 실제로 2026-09-13 에 마스터 노트북이
#   다른 망으로 옮겨가면서 클러스터가 통째로 내려갔는데, 사람이 직접 조회하기
#   전까지 몰랐다. 고장보다 '고장 난 줄 모르는 시간' 이 더 비싸다.
#
# 어디서 도는가
#   한 스크립트를 두 곳에서 쓴다. 무엇이 깔려 있는지 보고 알아서 판단한다.
#     마스터 노트북 — 하둡이 있으면 클러스터를 본다
#     서버1        — 우리 compose 가 있으면 서비스를 본다
#
#   ⚠ 마스터에서만 돌리면 안 된다.
#     노트북이 꺼지면 감시자도 같이 죽어서 아무 알림도 안 온다.
#     서버1 은 24시간 켜져 있으므로 최소한 서비스 쪽은 계속 감시된다.
#
# ⚠ 매번 보내지 않는다
#   10분마다 '정상입니다' 가 오면 아무도 안 읽는다. 그러면 진짜 경고도 묻힌다.
#   그래서 '상태가 바뀐 순간' 에만 보낸다. 고장났을 때 한 번, 돌아왔을 때 한 번.
#   하루에 한 번은 살아있다는 표시로 보낸다(감시자 자신이 죽은 것과 구분하려고).
set -uo pipefail

# ── 웹훅 주소 ─────────────────────────────────────────────────
#   1순위  환경변수 MATTERMOST_WEBHOOK
#   2순위  ~/.thispatch/mattermost-webhook 파일
#   3순위  서버1 이면 infra/.env 의 MATTERMOST_WEBHOOK
#
# ⚠ 저장소에 넣지 말 것.
#   이 주소를 아는 사람은 누구나 우리 채널에 글을 쓸 수 있다.
STATE_DIR=${STATE_DIR:-$HOME/.thispatch}
HOOK_FILE=${HOOK_FILE:-$STATE_DIR/mattermost-webhook}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$(dirname "$HERE")/.env"

WEBHOOK=${MATTERMOST_WEBHOOK:-}
if [ -z "$WEBHOOK" ] && [ -f "$HOOK_FILE" ]; then WEBHOOK=$(head -1 "$HOOK_FILE"); fi
if [ -z "$WEBHOOK" ] && [ -f "$ENV_FILE" ]; then
  WEBHOOK=$(grep -s "^MATTERMOST_WEBHOOK=" "$ENV_FILE" | cut -d= -f2- | head -1)
fi

HEARTBEAT_SEC=${HEARTBEAT_SEC:-86400}
CMD=${1:-now}
FORCE=0
[ "${2:-}" = "-f" ] && FORCE=1
[ "${2:-}" = "--force" ] && FORCE=1

mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR" 2>/dev/null || true
ROWS=$(mktemp); trap 'rm -f "$ROWS"' EXIT

# 검사 결과를 한 줄씩 쌓는다.  상태|항목|값
#   OK   정상
#   WARN 봐야 하지만 서비스는 산다
#   BAD  고장
row() { printf '%s|%s|%s\n' "$1" "$2" "$3" >> "$ROWS"; }

# ── 어디서 도는지 판단 ────────────────────────────────────────
ROLE=${ROLE:-auto}
if [ "$ROLE" = auto ]; then
  if [ -d /opt/hadoop ]; then ROLE=cluster
  elif [ -f "$(dirname "$HERE")/compose.server.yaml" ] && command -v docker >/dev/null; then ROLE=server
  else ROLE=unknown; fi
fi

# ══ 클러스터 검사 ═════════════════════════════════════════════
check_cluster() {
  local CONF=${HADOOP_CONF_DIR:-/opt/hadoop/etc/hadoop}
  local HB=${HADOOP_HOME:-/opt/hadoop}/bin

  # ⚠ 노드를 호스트명으로 구분하지 않는다.
  #   SSAFY 노트북은 같은 모델이라 윈도우 기본 이름이 그대로 겹친다.
  #   5대 중 4대가 DESKTOP-MR7IIH9 다 (2026-09-14 실측).
  #   그래서 NameNode 의 JMX LiveNodes 목록은 쓸 수 없다 — 그 목록은
  #   '호스트명:포트' 를 열쇠로 쓰는 지도(map)라, 이름이 같으면 한 칸으로
  #   합쳐진다. 실제로 5대가 붙어 있는데 목록에는 2개만 나왔다.
  #
  #   대신 IP 로 구분한다. IP 는 노트북마다 다르고, workers 파일에 기대
  #   목록이 그대로 있어서 '어느 IP 가 빠졌는지' 까지 집어낼 수 있다.

  # 1) 이름이 실제 주소를 가리키는가
  #    ⚠ 이게 어긋나면 자기 자신의 DataNode 조차 NameNode 를 못 찾는다.
  #      망을 옮기면 바로 이렇게 된다. (2026-09-13 실측)
  local MYIP NAMEIP
  MYIP=$(hostname -I 2>/dev/null | awk '{print $1}')
  NAMEIP=$(getent hosts dispatch-master 2>/dev/null | awk '{print $1}')
  if [ -z "$NAMEIP" ]; then
    row BAD "마스터 이름" "dispatch-master 를 못 찾음"
  elif [ "$MYIP" = "$NAMEIP" ]; then
    row OK "마스터 이름" "$NAMEIP"
  else
    row BAD "마스터 이름" "이름은 $NAMEIP 인데 실제는 $MYIP — setmaster 필요"
  fi

  # 2) NameNode 전체 상태 (숫자만 빠르게)
  local ST
  ST=$(curl -s -m 8 "http://localhost:9870/jmx?qry=Hadoop:service=NameNode,name=FSNamesystemState" 2>/dev/null | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)['beans'][0]
    print('%s %s %s %s'%(d.get('NumLiveDataNodes',-1), d.get('FSState','?'),
          d.get('NumberOfMissingBlocks',0), d.get('CapacityTotal',0)))
except Exception: print('-1 ? 0 0')" 2>/dev/null)
  if [ -z "$ST" ]; then row BAD "NameNode" "응답 없음 (9870)"; return; fi

  local LIVE FSSTATE MISSING CAP
  LIVE=$(echo "$ST" | awk '{print $1}')
  FSSTATE=$(echo "$ST" | awk '{print $2}')
  MISSING=$(echo "$ST" | awk '{print $3}')
  CAP=$(echo "$ST" | awk '{print $4}')
  if [ "$LIVE" = "-1" ]; then row BAD "NameNode" "상태를 읽지 못함"; return; fi

  # 3) 기대 목록 (workers 파일의 IP)
  local EXPECT_IPS EXPECT
  EXPECT_IPS=$(grep -oE '^[0-9]+(\.[0-9]+){3}' "$CONF/workers" 2>/dev/null | sort -u)
  EXPECT=$(printf '%s\n' "$EXPECT_IPS" | grep -c .)

  # 4) 지금 붙어 있는 DataNode 를 IP 로 받아온다
  #    dfsadmin -report 는 지도가 아니라 목록이라 이름이 겹쳐도 다 나온다.
  #    자바 명령이라 몇 초 걸린다 — 10분마다 도는 감시에는 충분히 싸다.
  local LIVE_IPS MISSING_IPS
  LIVE_IPS=$(timeout 40 "$HB/hdfs" dfsadmin -report 2>/dev/null \
             | grep -oE '^Name: [0-9]+(\.[0-9]+){3}' | awk '{print $2}' | sort -u)
  if [ -z "$LIVE_IPS" ]; then
    # 조회가 실패하면 숫자만이라도 쓴다
    if [ "$LIVE" -lt "$EXPECT" ]; then row WARN "HDFS 노드" "$LIVE / $EXPECT 대"
    else row OK "HDFS 노드" "$LIVE / $EXPECT 대"; fi
  else
    local N
    N=$(printf '%s\n' "$LIVE_IPS" | grep -c .)
    MISSING_IPS=$(comm -23 <(printf '%s\n' "$EXPECT_IPS") <(printf '%s\n' "$LIVE_IPS") | tr '\n' ' ')
    if [ "$N" -eq 0 ]; then
      row BAD "HDFS 노드" "0 / $EXPECT 대 — 아무도 안 붙어 있다"
    elif [ -n "${MISSING_IPS// /}" ]; then
      row WARN "HDFS 노드" "$N / $EXPECT 대 — 빠진 것 ${MISSING_IPS% }"
    else
      row OK "HDFS 노드" "$N / $EXPECT 대"
    fi
  fi

  # 5) 안전모드 · 블록 손상
  #    ⚠ 안전모드면 읽기만 되고 쓰기가 막힌다. 배치가 조용히 실패한다.
  if [ "$FSSTATE" = "Safemode" ]; then row BAD "안전모드" "켜짐 — 쓰기가 막힌다"
  else row OK "안전모드" "꺼짐"; fi
  if [ "${MISSING:-0}" -gt 0 ]; then row BAD "잃어버린 블록" "$MISSING 개"
  else row OK "블록" "잃어버린 것 없음"; fi
  if [ "${CAP:-0}" -gt 0 ]; then
    row OK "HDFS 용량" "$(python3 -c "print('%.2f TB'%($CAP/1024**4))" 2>/dev/null)"
  fi

  # 6) YARN — 계산을 돌릴 수 있는가
  #    HDFS 가 멀쩡해도 YARN 이 비면 스파크 잡이 한 대에서만 돈다.
  #    ⚠ YARN 은 노드를 이름으로 부르기도 하고 IP 로 부르기도 한다
  #      (dispatch-master, thispatch-w103, 70.12.247.106 이 섞여 나온다).
  #      그래서 전부 IP 로 바꿔서 비교한다.
  local YRAW YARN_IPS
  YRAW=$(curl -s -m 8 "http://localhost:8088/ws/v1/cluster/nodes" 2>/dev/null | python3 -c "
import sys,json
try:
    ns=(json.load(sys.stdin).get('nodes') or {}).get('node',[])
    for n in ns:
        if n.get('state')=='RUNNING':
            print((n.get('id') or ':').split(':')[0])
except Exception: pass" 2>/dev/null)
  if [ -z "$YRAW" ]; then
    row BAD "YARN 노드" "0 대 — 계산을 못 돌린다"
  else
    YARN_IPS=$(while read -r h; do
        [ -z "$h" ] && continue
        case "$h" in
          *[!0-9.]*) getent hosts "$h" 2>/dev/null | awk '{print $1}' ;;
          *) echo "$h" ;;
        esac
      done <<< "$YRAW" | sort -u)
    local YN YMISS
    YN=$(printf '%s\n' "$YARN_IPS" | grep -c .)
    YMISS=$(comm -23 <(printf '%s\n' "$EXPECT_IPS") <(printf '%s\n' "$YARN_IPS") | tr '\n' ' ')
    if [ -n "${YMISS// /}" ]; then row WARN "YARN 노드" "$YN / $EXPECT 대 — 빠진 것 ${YMISS% }"
    else row OK "YARN 노드" "$YN / $EXPECT 대"; fi
  fi

  # 7) 디스크
  local USE
  USE=$(df --output=pcent /data 2>/dev/null | tail -1 | tr -dc 0-9)
  [ -z "$USE" ] && USE=$(df --output=pcent / | tail -1 | tr -dc 0-9)
  if [ "${USE:-0}" -ge 90 ]; then row BAD "디스크" "${USE}% 사용"
  elif [ "${USE:-0}" -ge 80 ]; then row WARN "디스크" "${USE}% 사용"
  else row OK "디스크" "${USE}% 사용"; fi
  # 8) WSL 이 방금 켜졌는가
  #    ⚠ 이 검사가 제일 중요할 수도 있다.
  #      WSL2 는 마지막 세션이 닫히면 60초 뒤 가상머신을 통째로 내린다.
  #      그러면 하둡 데몬이 전부 죽고, 워커들은 사라진 마스터를 찾다가 끊긴다.
  #      겉으로는 "워커가 이상하다" 로 보이지만 범인은 마스터다. (2026-09-14 실측)
  #
  #      막는 장치는 두 가지인데 둘 다 조용히 실패할 수 있다.
  #        .wslconfig 의 vmIdleTimeout  — WSL 2.7 은 이 키를 거부한다
  #        시작프로그램의 keepalive 세션 — 로그인 안 하면 안 돈다
  #      그래서 결과를 직접 본다. 가동 시간이 매번 짧으면 꺼지고 있는 것이다.
  local UPS
  UPS=$(cut -d. -f1 /proc/uptime 2>/dev/null)
  if [ -z "$UPS" ]; then
    row WARN "WSL 가동" "확인 불가"
  elif [ "$UPS" -lt 300 ]; then
    row WARN "WSL 가동" "$((UPS/60))분 — 방금 켜졌다. 절전으로 꺼졌다 왔을 수 있다"
  else
    row OK "WSL 가동" "$((UPS/3600))시간 $(((UPS%3600)/60))분"
  fi

  # 9) WSL 을 붙잡는 세션이 있는가는 리눅스 안에서 볼 수 없다.
  #    대신 하둡 데몬이 최근에 재시작했는지로 간접 확인한다.
  #    5분 안에 두 번 이상 멈췄으면 무언가 반복해서 내리고 있는 것이다.
  local RMSTOP
  RMSTOP=$(journalctl -u yarn-resourcemanager --since "10 min ago" --no-pager 2>/dev/null | grep -c "Stopping YARN")
  if [ "${RMSTOP:-0}" -ge 3 ]; then
    row BAD "RM 재시작" "10분간 ${RMSTOP}회 — WSL 이 반복해서 꺼지고 있다"
  elif [ "${RMSTOP:-0}" -ge 1 ]; then
    row WARN "RM 재시작" "10분간 ${RMSTOP}회"
  else
    row OK "RM 재시작" "없음"
  fi
}

# ══ 서버1 검사 ════════════════════════════════════════════════
check_server() {
  # 1) 컨테이너
  #    ⚠ Up 만 보면 안 된다. 떠 있어도 healthcheck 가 실패 중일 수 있다.
  local c st
  for c in backend jenkins; do
    st=$(sudo docker inspect "$c" --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' </dev/null 2>/dev/null)
    case "$st" in
      "running healthy") row OK "$c" "healthy" ;;
      "running none")    row OK "$c" "running" ;;
      "running "*)       row WARN "$c" "${st#running }" ;;
      "")                row BAD "$c" "컨테이너가 없다" ;;
      *)                 row BAD "$c" "$st" ;;
    esac
  done

  # 2) 호스트 서비스
  for c in nginx postgresql; do
    if systemctl is-active --quiet "$c"; then row OK "$c" "active"; else row BAD "$c" "죽었다"; fi
  done

  # 3) 밖에서 실제로 닿는가
  #    ⚠ 컨테이너가 살아 있어도 nginx 설정이 틀리면 사용자는 못 쓴다.
  #      그래서 안쪽이 아니라 '공인 주소' 로 두드린다.
  local base=https://j15a202.p.ssafy.io
  local p code
  for p in "/:200" "/api/:401" "/jenkins/login:200"; do
    local path=${p%:*} want=${p##*:}
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 15 "$base$path" 2>/dev/null || echo 000)
    if [ "$code" = "$want" ]; then row OK "$path" "$code"
    elif [ "$code" = 000 ]; then row BAD "$path" "응답 없음"
    else row BAD "$path" "$code (기대 $want)"; fi
  done

  # 4) 백업이 최근에 성공했는가
  #    ⚠ 백업은 조용히 실패한다. 실패해도 아무 일이 안 일어나기 때문이다.
  # ⚠ 유닛이 없는데도 systemctl show 는 Result=success 를 돌려준다.
  #   그대로 믿으면 '백업이 아예 안 걸려 있는 상태' 를 정상으로 보고하게 된다.
  #   먼저 유닛이 실제로 있는지부터 본다. (2026-09-14 실측)
  local loaded res newest age BK
  loaded=$(systemctl show thispatch-dbbackup.service -p LoadState --value 2>/dev/null)
  if [ "$loaded" != loaded ]; then
    row WARN "DB 백업" "타이머가 등록되어 있지 않다"
  else
    # ⚠ systemd 가 알려주는 '마지막 실행 시각' 을 쓰면 안 된다.
    #   Type=oneshot 유닛은 끝나는 순간 ExecMainExitTimestamp 가 비워진다.
    #   InactiveEnterTimestamp 도 마찬가지로 비어 있다.
    #   그래서 백업이 잘 돌아도 영원히 '한 번도 안 돌았다' 로 보고했다.
    #   (2026-09-14 실측 — 성공 직후에 봐도 전부 빈 값이었다)
    #
    #   대신 결과물을 본다. 18-db-backup.sh 는 성공할 때만 로컬 사본을
    #   남기므로, 그 파일의 시각이 곧 마지막 성공 시각이다.
    BK=${LOCAL_DIR:-$HOME/db-backup}
    newest=$(ls -t "$BK"/db-*.tar.gz 2>/dev/null | head -1)
    res=$(systemctl show thispatch-dbbackup.service -p Result --value 2>/dev/null)
    if [ "$res" != success ]; then
      row BAD "DB 백업" "마지막 실행 실패 ($res)"
    elif [ -z "$newest" ]; then
      row WARN "DB 백업" "백업 파일이 없다 ($BK)"
    else
      age=$(( ( $(date +%s) - $(stat -c %Y "$newest") ) / 3600 ))
      if [ "$age" -gt 30 ]; then row WARN "DB 백업" "${age}시간 전 (하루 넘게 안 돌았다)"
      else row OK "DB 백업" "${age}시간 전 성공"; fi
    fi
  fi

  # 5) 디스크 · 메모리
  local USE FREEM
  USE=$(df --output=pcent / | tail -1 | tr -dc 0-9)
  if [ "${USE:-0}" -ge 90 ]; then row BAD "디스크" "${USE}% 사용"
  elif [ "${USE:-0}" -ge 80 ]; then row WARN "디스크" "${USE}% 사용"
  else row OK "디스크" "${USE}% 사용"; fi
  FREEM=$(free -m | awk '/Mem:/{print $7}')
  if [ "${FREEM:-9999}" -lt 800 ]; then row WARN "메모리" "${FREEM}MB 여유"
  else row OK "메모리" "${FREEM}MB 여유"; fi
}

# ══ 검사 실행 ═════════════════════════════════════════════════
case "$ROLE" in
  cluster) TITLE="마스터 노트북"; check_cluster ;;
  server)  TITLE="서버1"; check_server ;;
  *) echo "여기가 어디인지 모르겠습니다. ROLE=cluster 또는 ROLE=server 를 주세요." >&2; exit 1 ;;
esac

BAD_N=$(grep -c '^BAD|'  "$ROWS" || true)
WARN_N=$(grep -c '^WARN|' "$ROWS" || true)
if   [ "${BAD_N:-0}" -gt 0 ]; then LEVEL=BAD;  MARK=":red_circle:";      COLOR="#A8452F"
elif [ "${WARN_N:-0}" -gt 0 ]; then LEVEL=WARN; MARK=":large_orange_diamond:"; COLOR="#8A6A1F"
else LEVEL=OK; MARK=":large_green_circle:"; COLOR="#3F6B4A"; fi

# ── 화면 출력 ─────────────────────────────────────────────────
print_plain() {
  echo "== $TITLE  [$LEVEL] =============================="
  while IFS='|' read -r s k v; do printf '  %-5s %-14s %s\n' "$s" "$k" "$v"; done < "$ROWS"
}

# ── Mattermost 로 보내기 ──────────────────────────────────────
send() {
  [ -n "$WEBHOOK" ] || { echo "웹훅 주소가 없습니다. $HOOK_FILE 에 넣으세요." >&2; return 1; }
  local PAY
  # ⚠ 꼬리표는 호스트명이 아니라 IP 를 쓴다.
  #   노트북 이름이 겹치기도 하고(DESKTOP-MR7IIH9 가 4대),
  #   서버는 ip-172-26-8-198 처럼 읽을 수 없는 이름이라 도움이 안 된다.
  PAY=$(ROWS_FILE="$ROWS" TITLE="$TITLE" LEVEL="$LEVEL" MARK="$MARK" COLOR="$COLOR" HOSTN="$(hostname -I 2>/dev/null | awk '{print $1}')" python3 - <<'PY'
import os, json, datetime
rows = [l.rstrip("\n").split("|", 2) for l in open(os.environ["ROWS_FILE"], encoding="utf-8") if l.strip()]
mark = {"OK": ":white_check_mark:", "WARN": ":warning:", "BAD": ":x:"}
level = os.environ["LEVEL"]
head = {"OK": "정상으로 돌아왔습니다", "WARN": "확인이 필요합니다", "BAD": "고장입니다"}[level]
lines = ["| | 항목 | 값 |", "|:--:|---|---|"]
for s, k, v in rows:
    lines.append("| %s | %s | %s |" % (mark.get(s, ""), k, v))
# ⚠ 서버1 은 시간대가 UTC 라 now() 가 9시간 어깰난다.
#   두 곳에서 온 메시지의 시각이 달라지면 순서를 잍어버린다.
now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime("%m-%d %H:%M")
body = "\n".join(lines) + "\n\n_%s · %s_" % (os.environ["HOSTN"], now)
print(json.dumps({
    "username": "디스패치 감시",
    "text": "%s **%s** — %s" % (os.environ["MARK"], os.environ["TITLE"], head),
    "attachments": [{"color": os.environ["COLOR"], "text": body}],
}, ensure_ascii=False))
PY
)
  local code
  code=$(curl -s -o /tmp/mm.out -w '%{http_code}' -m 15 -X POST -H 'Content-Type: application/json' -d "$PAY" "$WEBHOOK")
  if [ "$code" = 200 ]; then echo "  보냈습니다."; else
    echo "  보내기 실패 ($code): $(head -c 200 /tmp/mm.out)" >&2; return 1; fi
}

# ── 상태가 바뀌었을 때만 ──────────────────────────────────────
# 값이 아니라 '항목별 상태' 만 해시한다.
# 디스크 % 처럼 매번 조금씩 달라지는 값까지 넣으면 10분마다 알림이 온다.
STATE_HASH=$(cut -d'|' -f1,2 "$ROWS" | sha256sum | cut -c1-16)
LAST_HASH_F="$STATE_DIR/alert-hash"; LAST_SENT_F="$STATE_DIR/alert-sent"
LAST_HASH=$(cat "$LAST_HASH_F" 2>/dev/null || echo none)
LAST_SENT=$(cat "$LAST_SENT_F" 2>/dev/null || echo 0)
NOW=$(date +%s)

case "$CMD" in
check)
  print_plain
  echo
  echo "  상태 지문 $STATE_HASH (직전 $LAST_HASH)"
  echo "  보내지 않았습니다. 보내려면 now 를 쓰세요."
  ;;

now)
  print_plain
  echo
  REASON=""
  if [ "$FORCE" = 1 ]; then REASON="강제"
  elif [ "$STATE_HASH" != "$LAST_HASH" ]; then REASON="상태가 바뀌었다"
  elif [ $((NOW - LAST_SENT)) -ge "$HEARTBEAT_SEC" ]; then REASON="하루 경과 — 살아있다는 표시"
  fi
  if [ -n "$REASON" ]; then
    echo "  보냅니다 ($REASON)"
    if send; then echo "$NOW" > "$LAST_SENT_F"; fi
  else
    echo "  직전과 같습니다. 보내지 않습니다."
  fi
  echo "$STATE_HASH" > "$LAST_HASH_F"
  ;;

test)
  echo "== Mattermost 연결 시험 =========================="
  if [ -z "$WEBHOOK" ]; then
    echo "  웹훅 주소가 없습니다."
    echo "  Mattermost 에서 Incoming Webhook 을 만든 뒤 주소를 여기에 넣으세요:"
    echo "    mkdir -p $STATE_DIR && chmod 700 $STATE_DIR"
    echo "    echo 'https://meeting.ssafy.com/hooks/...' > $HOOK_FILE"
    echo "    chmod 600 $HOOK_FILE"
    exit 1
  fi
  echo "  주소 ...$(printf '%s' "$WEBHOOK" | tail -c 8)"
  TITLE="$TITLE (연결 시험)"; LEVEL=OK; MARK=":wrench:"; COLOR="#2E5C8A"
  : > "$ROWS"; row OK "연결" "이 메시지가 보이면 성공입니다"
  send
  ;;

install)
  [ "$(id -u)" = 0 ] && { echo "sudo 없이 그냥 실행하세요." >&2; exit 1; }
  [ -n "$WEBHOOK" ] || { echo "먼저 웹훅 주소를 넣으세요. bash $0 test" >&2; exit 1; }
  echo "== 타이머 등록 =================================="
  SELF=$(readlink -f "$0")
  sudo tee /etc/systemd/system/thispatch-alert.service >/dev/null <<UNIT
[Unit]
Description=디스패치 상태 감시 ($TITLE)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$USER
Environment=ROLE=$ROLE
Environment=STATE_DIR=$STATE_DIR
Environment=HEARTBEAT_SEC=$HEARTBEAT_SEC
ExecStart=/usr/bin/env bash $SELF now
UNIT
  sudo tee /etc/systemd/system/thispatch-alert.timer >/dev/null <<'UNIT'
[Unit]
Description=디스패치 상태 감시 — 10분마다

[Timer]
OnBootSec=3min
OnUnitActiveSec=10min
# 꺼져 있어 걸렀으면 켜지자마자 한 번 따라잡는다
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable --now thispatch-alert.timer
  systemctl list-timers thispatch-alert.timer --no-pager | sed -n '1,3p'
  echo
  echo "  로그: journalctl -u thispatch-alert -n 30"
  ;;

remove)
  sudo systemctl disable --now thispatch-alert.timer 2>/dev/null
  sudo rm -f /etc/systemd/system/thispatch-alert.service /etc/systemd/system/thispatch-alert.timer
  sudo systemctl daemon-reload
  echo "  해제했습니다."
  ;;

*)
  sed -n '2,9p' "$0"
  exit 1
  ;;
esac
