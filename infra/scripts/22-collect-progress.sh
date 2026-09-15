#!/usr/bin/env bash
# 리뷰 수집 진행률을 Mattermost 로 알린다
#
#   bash 22-collect-progress.sh now       지금 한 번 보낸다
#   bash 22-collect-progress.sh check     화면에만 출력 (안 보냄)
#   bash 22-collect-progress.sh test      연결 시험
#   bash 22-collect-progress.sh install   systemd 타이머 등록 (30분마다)
#   bash 22-collect-progress.sh remove    타이머 해제
#
# 왜 따로 만드는가
#   20-mattermost-alert.sh 는 노드와 서비스가 살아 있는지만 본다.
#   노드가 전부 초록불인데 수집만 멈춘 상황을 못 잡는다.
#   2026-09-14 밤 전량 수집에서 실제로 그랬다 — RabbitMQ 가 30분
#   응답 시한을 넘긴 조각의 채널을 끊어 조각 62개가 실패했는데,
#   아침에 사람이 직접 들여다보기 전까지 아무도 몰랐다.
#
#   주기도 다르다. 노드 감시는 10분, 수집 진행은 30분이다.
#   한 메시지에 섞으면 둘 다 안 읽게 된다.
#
# ⚠ 마스터에서만 돈다. 배치 DB·HDFS·RabbitMQ 가 전부 여기 있다.
#
# ⚠ 수집이 돌고 있을 때만 보낸다.
#   30분마다 '수집 안 함' 이 오면 아무도 안 읽는다. 끝난 직후 한 번은
#   결과를 보내고, 그 뒤로는 조용하다.
set -uo pipefail

# ── 웹훅 주소 ─────────────────────────────────────────────────
# ⚠ 저장소에 넣지 말 것. 20-mattermost-alert.sh 와 같은 파일을 쓴다.
STATE_DIR=${STATE_DIR:-$HOME/.thispatch}
HOOK_FILE=${HOOK_FILE:-$STATE_DIR/mattermost-webhook}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$(dirname "$HERE")/.env"

WEBHOOK=${MATTERMOST_WEBHOOK:-}
if [ -z "$WEBHOOK" ] && [ -f "$HOOK_FILE" ]; then WEBHOOK=$(head -1 "$HOOK_FILE"); fi
if [ -z "$WEBHOOK" ] && [ -f "$ENV_FILE" ]; then
  WEBHOOK=$(grep -s "^MATTERMOST_WEBHOOK=" "$ENV_FILE" | cut -d= -f2- | head -1)
fi

# ── 어디를 보는가 ─────────────────────────────────────────────
HDFS_BIN=${HDFS_BIN:-/opt/hadoop/bin/hdfs}
[ -x "$HDFS_BIN" ] || HDFS_BIN=$(command -v hdfs || echo /opt/hadoop/bin/hdfs)
export JAVA_HOME="${JAVA_HOME:-$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)}"

LANDING=${LANDING:-/review_landing}
BATCH_DB_HOST=${BATCH_DB_HOST:-127.0.0.1}
BATCH_DB_NAME=${BATCH_DB_NAME:-thispatch_batch}
BATCH_DB_USER=${BATCH_DB_USER:-thispatch}
BATCH_DB_PASSWORD=${BATCH_DB_PASSWORD:-dispatch-batch-local}
MQ_VHOST=${MQ_VHOST:-thispatch}
MQ_QUEUE=${MQ_QUEUE:-thispatch.collect.requests}

# 한 파일이 리뷰 한 페이지다. 스팀이 한 페이지에 100건을 준다.
# 마지막 페이지만 덜 차서 실측 평균이 97 이다 — 2026-09-15 표본 8개.
REVIEWS_PER_FILE=${REVIEWS_PER_FILE:-97}

# 전량 목표. 노션 「데이터 정리」 실측값이다. 추측이 아니다.
TOTAL_REVIEWS=${TOTAL_REVIEWS:-146600000}

CMD=${1:-now}
FORCE=0
[ "${2:-}" = "-f" ] && FORCE=1
[ "${2:-}" = "--force" ] && FORCE=1

mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR" 2>/dev/null || true
SNAP="$STATE_DIR/collect-progress"     # 직전 측정: 시각 파일수 잡번호
DONE_F="$STATE_DIR/collect-done"       # 끝난 잡을 이미 알렸는지

psqlq() {
  PGPASSWORD="$BATCH_DB_PASSWORD" psql -h "$BATCH_DB_HOST" -U "$BATCH_DB_USER" \
    -d "$BATCH_DB_NAME" -tAq -F'|' -c "$1" 2>/dev/null
}

# ══ 1) 잡이 도는가 ════════════════════════════════════════════
JOB=$(psqlq "
  select e.job_execution_id, e.status,
         to_char(e.start_time, 'MM-DD HH24:MI'),
         extract(epoch from (now() - e.start_time))::bigint
  from batch_job_execution e join batch_job_instance i using(job_instance_id)
  where i.job_name = 'collectJob' order by e.job_execution_id desc limit 1;")

JOB_ID=$(echo "$JOB"   | cut -d'|' -f1)
JOB_ST=$(echo "$JOB"   | cut -d'|' -f2)
JOB_FROM=$(echo "$JOB" | cut -d'|' -f3)
JOB_AGE=$(echo "$JOB"  | cut -d'|' -f4)
: "${JOB_ID:=0}" "${JOB_ST:=NONE}" "${JOB_FROM:=-}" "${JOB_AGE:=0}"

# ══ 2) 조각 ═══════════════════════════════════════════════════
part() {
  psqlq "select count(*) from batch_step_execution
         where job_execution_id = $JOB_ID and status = '$1';" | head -1
}
P_DONE=$(part COMPLETED); P_RUN=$(part STARTED)
P_WAIT=$(part STARTING);  P_FAIL=$(part FAILED)
: "${P_DONE:=0}" "${P_RUN:=0}" "${P_WAIT:=0}" "${P_FAIL:=0}"
P_ALL=$((P_DONE + P_RUN + P_WAIT + P_FAIL))

# ══ 3) HDFS ═══════════════════════════════════════════════════
# ⚠ -ls 로 세지 않는다. 파일이 수십만 개라 목록을 받아오는 데만 몇 분이 걸리고,
#   수집이 끝날 때쯤이면 백만 개가 넘는다.
#   -count 는 NameNode 가 이미 들고 있는 숫자를 그대로 준다 — 즉시 끝난다.
read -r FILES BYTES <<<"$("$HDFS_BIN" dfs -count "$LANDING/dt=*" 2>/dev/null \
  | awk '{f += $2; b += $3} END {print (f ? f : 0), (b ? b : 0)}')"
: "${FILES:=0}" "${BYTES:=0}"
REVIEWS=$((FILES * REVIEWS_PER_FILE))

# ══ 4) 큐 ═════════════════════════════════════════════════════
read -r Q_MSG Q_CON <<<"$(sudo -n rabbitmqctl list_queues -p "$MQ_VHOST" name messages consumers 2>/dev/null \
  | awk -v q="$MQ_QUEUE" '$1 == q {print $2, $3}')"
: "${Q_MSG:=0}" "${Q_CON:=0}"

# ══ 5) 직전 측정과 비교해 속도를 낸다 ═════════════════════════
NOW=$(date +%s)
PREV_T=0; PREV_F=0; PREV_JOB=0
[ -f "$SNAP" ] && read -r PREV_T PREV_F PREV_JOB < "$SNAP"
: "${PREV_T:=0}" "${PREV_F:=0}" "${PREV_JOB:=0}"

# 잡이 바뀌었으면 직전 값은 기준이 안 된다.
if [ "$PREV_JOB" != "$JOB_ID" ]; then PREV_T=0; PREV_F=0; fi

D_SEC=$((NOW - PREV_T)); D_FILE=$((FILES - PREV_F))
if [ "$PREV_T" -gt 0 ] && [ "$D_SEC" -gt 0 ] && [ "$D_FILE" -ge 0 ]; then
  HAVE_RATE=1
else
  HAVE_RATE=0; D_SEC=0; D_FILE=0
fi

# ══ 6) 등급 ═══════════════════════════════════════════════════
#   멈춘 것을 잡는 게 이 알림의 목적이다.
LEVEL=OK; WHY=""
if [ "$JOB_ST" = STARTED ]; then
  if [ "$Q_CON" = 0 ] && [ "$Q_MSG" -gt 0 ]; then
    LEVEL=BAD; WHY="큐에 조각이 ${Q_MSG}개 쌓였는데 듣는 워커가 없습니다"
  elif [ "$HAVE_RATE" = 1 ] && [ "$D_FILE" = 0 ]; then
    LEVEL=BAD; WHY="지난 측정 이후 한 건도 안 늘었습니다 — 멈춘 것 같습니다"
  elif [ "$P_FAIL" -gt 0 ]; then
    LEVEL=WARN; WHY="조각 ${P_FAIL}개가 실패했습니다. 1차가 끝나면 다시 돌려야 합니다"
  fi
elif [ "$JOB_ST" = FAILED ]; then
  LEVEL=WARN; WHY="잡이 실패로 끝났습니다"
fi

# ══ 출력 ══════════════════════════════════════════════════════
render() {
  JOB_ST="$JOB_ST" JOB_FROM="$JOB_FROM" JOB_AGE="$JOB_AGE" \
  FILES="$FILES" BYTES="$BYTES" REVIEWS="$REVIEWS" TOTAL="$TOTAL_REVIEWS" \
  P_DONE="$P_DONE" P_RUN="$P_RUN" P_WAIT="$P_WAIT" P_FAIL="$P_FAIL" P_ALL="$P_ALL" \
  Q_MSG="$Q_MSG" Q_CON="$Q_CON" RPF="$REVIEWS_PER_FILE" \
  HAVE_RATE="$HAVE_RATE" D_SEC="$D_SEC" D_FILE="$D_FILE" \
  LEVEL="$LEVEL" WHY="$WHY" MODE="$1" WEBHOOK="$WEBHOOK" \
  python3 - <<'PY'
import os, json, datetime, subprocess, sys

E = os.environ
i = lambda k: int(E.get(k) or 0)

reviews, total = i("REVIEWS"), i("TOTAL")
pct = 100.0 * reviews / total if total else 0
age = i("JOB_AGE")


def dur(s):
    s = int(s)
    if s < 3600:
        return "%d분" % (s // 60)
    if s < 86400:
        return "%d시간 %d분" % (s // 3600, (s % 3600) // 60)
    return "%d일 %d시간" % (s // 86400, (s % 86400) // 3600)


def man(n):
    # 한국어로 큰 수는 '만' 단위가 읽힌다. 2,304만 이 23,040,000 보다 빠르다.
    if n >= 100000000:
        return "%.2f억 건" % (n / 100000000)
    if n >= 10000:
        return "%s만 건" % format(n // 10000, ",")
    return "%s 건" % format(n, ",")


rows = [
    ("진행", "%s / %s  (%.1f%%)" % (man(reviews), man(total), pct)),
    ("경과", "%s  (%s 시작)" % (dur(age), E["JOB_FROM"])),
    ("조각", "완료 %s · 진행 %s · 대기 %s · 실패 %s  / %s"
             % (E["P_DONE"], E["P_RUN"], E["P_WAIT"], E["P_FAIL"], E["P_ALL"])),
]

if i("HAVE_RATE") and i("D_SEC") > 0:
    got = i("D_FILE") * i("RPF")
    rate = got / i("D_SEC")
    rows.append(("지난 %s" % dur(i("D_SEC")),
                 "+%s  (%s 리뷰/초)" % (man(got), format(int(rate), ","))))
    left = total - reviews
    if rate > 0 and left > 0:
        eta = left / rate
        done_at = (datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9)))
                   + datetime.timedelta(seconds=eta))
        rows.append(("남은 시간", "약 %s  (%s 무렵)" % (dur(eta), done_at.strftime("%m-%d %H시"))))
else:
    rows.append(("속도", "다음 측정부터 나옵니다"))

rows.append(("용량", "%.2f GB  (파일 %s 개)" % (i("BYTES") / 1073741824, format(i("FILES"), ","))))
rows.append(("워커", "소비자 %s · 큐에 %s 조각" % (E["Q_CON"], E["Q_MSG"])))

level = E["LEVEL"]
mark = {"OK": ":bar_chart:", "WARN": ":warning:", "BAD": ":x:"}[level]
color = {"OK": "#2E7D32", "WARN": "#B8860B", "BAD": "#B00020"}[level]
head = {"STARTED": "수집 중", "COMPLETED": "수집 끝남",
        "FAILED": "수집이 실패로 끝남", "NONE": "수집 기록 없음"}.get(E["JOB_ST"], E["JOB_ST"])

if E["MODE"] == "plain":
    print("== 리뷰 수집 — %s  [%s]" % (head, level))
    for k, v in rows:
        print("  %-12s %s" % (k, v))
    if E["WHY"]:
        print("  ! %s" % E["WHY"])
    sys.exit(0)

lines = ["| 항목 | 값 |", "|---|---|"] + ["| %s | %s |" % (k, v) for k, v in rows]
body = "\n".join(lines)
if E["WHY"]:
    body += "\n\n:point_right: **%s**" % E["WHY"]
now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime("%m-%d %H:%M")
body += "\n\n_%s_" % now

payload = json.dumps({
    "username": "디스패치 수집",
    "text": "%s **리뷰 수집** — %s" % (mark, head),
    "attachments": [{"color": color, "text": body}],
}, ensure_ascii=False)

code = subprocess.run(
    ["curl", "-s", "-o", "/tmp/mm-progress.out", "-w", "%{http_code}",
     "-m", "15", "-X", "POST", "-H", "Content-Type: application/json",
     "-d", payload, E["WEBHOOK"]],
    capture_output=True, text=True).stdout.strip()
if code == "200":
    print("  보냈습니다.")
else:
    print("  보내기 실패 (%s): %s" % (code, open("/tmp/mm-progress.out").read()[:200]))
    sys.exit(1)
PY
}

case "$CMD" in
check)
  render plain
  echo
  echo "  보내지 않았습니다. 보내려면 now 를 쓰세요."
  ;;

now)
  render plain
  echo
  SEND=0; REASON=""
  if [ "$FORCE" = 1 ]; then
    SEND=1; REASON="강제"
  elif [ "$JOB_ST" = STARTED ]; then
    SEND=1; REASON="수집 중"
  elif [ "$JOB_ST" = COMPLETED ] || [ "$JOB_ST" = FAILED ]; then
    # 끝난 잡은 딱 한 번만 알린다. 안 그러면 30분마다 같은 결과가 온다.
    if [ "$(cat "$DONE_F" 2>/dev/null || echo 0)" != "$JOB_ID" ]; then
      SEND=1; REASON="방금 끝남"; echo "$JOB_ID" > "$DONE_F"
    fi
  fi
  if [ "$SEND" = 1 ]; then
    echo "  보냅니다 ($REASON)"
    [ -n "$WEBHOOK" ] || { echo "  웹훅 주소가 없습니다: $HOOK_FILE" >&2; exit 1; }
    render send
  else
    echo "  수집이 돌고 있지 않습니다. 보내지 않습니다."
  fi
  # ⚠ 측정값은 보냈든 안 보냈든 남긴다. 다음 번 속도 계산의 기준이다.
  echo "$NOW $FILES $JOB_ID" > "$SNAP"
  ;;

test)
  echo "== 연결 시험 =================================="
  if [ -z "$WEBHOOK" ]; then
    echo "  웹훅 주소가 없습니다. 20-mattermost-alert.sh 와 같은 파일을 씁니다:"
    echo "    $HOOK_FILE"
    exit 1
  fi
  echo "  주소 ...$(printf '%s' "$WEBHOOK" | tail -c 8)"
  render send
  ;;

install)
  [ "$(id -u)" = 0 ] && { echo "sudo 없이 그냥 실행하세요." >&2; exit 1; }
  [ -n "$WEBHOOK" ] || { echo "먼저 웹훅 주소를 넣으세요. bash $0 test" >&2; exit 1; }
  echo "== 타이머 등록 (30분마다) ======================"
  SELF=$(readlink -f "$0")
  sudo tee /etc/systemd/system/thispatch-collect-progress.service >/dev/null <<UNIT
[Unit]
Description=디스패치 리뷰 수집 진행률 알림
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$USER
Environment=STATE_DIR=$STATE_DIR
Environment=BATCH_DB_PASSWORD=$BATCH_DB_PASSWORD
Environment=JAVA_HOME=$JAVA_HOME
ExecStart=/usr/bin/env bash $SELF now
UNIT
  sudo tee /etc/systemd/system/thispatch-collect-progress.timer >/dev/null <<'UNIT'
[Unit]
Description=디스패치 수집 진행률 — 30분마다

[Timer]
OnBootSec=5min
OnUnitActiveSec=30min
# 노트북이 꺼져 있어 걸렀으면 켜지자마자 한 번 따라잡는다
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable --now thispatch-collect-progress.timer
  systemctl list-timers thispatch-collect-progress.timer --no-pager | sed -n '1,3p'
  echo
  echo "  로그: journalctl -u thispatch-collect-progress -n 30"
  ;;

remove)
  sudo systemctl disable --now thispatch-collect-progress.timer 2>/dev/null
  sudo rm -f /etc/systemd/system/thispatch-collect-progress.service \
             /etc/systemd/system/thispatch-collect-progress.timer
  sudo systemctl daemon-reload
  echo "  해제했습니다."
  ;;

*)
  sed -n '2,9p' "$0"
  exit 1
  ;;
esac
