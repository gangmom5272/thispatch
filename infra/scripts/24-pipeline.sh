#!/usr/bin/env bash
# 매일 도는 배치를 순서대로 잇는다 — 마스터에서 실행
#
#   bash 24-pipeline.sh daily              전체를 순서대로
#   bash 24-pipeline.sh daily --from convert   그 단계부터 다시
#   bash 24-pipeline.sh daily --only news      그 단계만
#   bash 24-pipeline.sh daily --dry-run        무엇을 할지만 보고 아무것도 안 함
#   bash 24-pipeline.sh stages             단계 목록
#   bash 24-pipeline.sh install            타이머 등록 (매일 09:05 KST)
#   bash 24-pipeline.sh remove             타이머 해제
#   bash 24-pipeline.sh status             타이머 · 최근 실행
#
# 왜 필요한가
#   지금은 사람이 순서를 지킨다. 그런데 순서를 틀리면 조용히 망가지는 곳이 많다.
#   2026-09-15 하루에 겪은 것만 적어도 이렇다.
#
#     수집 중에 카탈로그가 돌면   game 테이블이 바뀌어 이어 돌리기가 어긋난다
#     재투입을 건너뛰면           COMPLETED 가 아니라서 증분 기준 시각이 안 옮겨간다
#     compaction 과 집계가 겹치면 적게 읽고도 오류가 안 난다 (화면 숫자만 줄어든다)
#
# ⚠ systemd 유닛을 여러 개 만들어 OnSuccess= 로 잇지 않는다.
#   유닛이 늘수록 「매니저는 죽었는데 유닛은 active」 같은 일이 늘고(2026-09-15 실측),
#   전체 순서가 유닛 파일 여러 개에 흩어져 아무도 못 읽는다. 순서는 여기 한 곳에 둔다.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR=${STATE_DIR:-$HOME/.thispatch}
LOCK="$STATE_DIR/pipeline.lock"
LAST="$STATE_DIR/pipeline-last"

SPARK_SUBMIT=${SPARK_SUBMIT:-/opt/spark/bin/spark-submit}
SPARK_JAR=${SPARK_JAR:-$(cd "$HERE/../.." && pwd)/spark/build/libs/thispatch-spark.jar}

# 배치 DB — collect 단계 앞의 안전장치가 본다. 23-collect-retry.sh 와 같은 값.
BATCH_DB_HOST=${BATCH_DB_HOST:-127.0.0.1}
BATCH_DB_NAME=${BATCH_DB_NAME:-thispatch_batch}
BATCH_DB_USER=${BATCH_DB_USER:-thispatch}
BATCH_DB_PASSWORD=${BATCH_DB_PASSWORD:-dispatch-batch-local}

# 안전장치를 끄는 탈출구. 정말로 전량을 다시 받을 때만 쓴다.
COLLECT_GUARD=${COLLECT_GUARD:-on}

HOOK_FILE=${HOOK_FILE:-$STATE_DIR/mattermost-webhook}
WEBHOOK=${MATTERMOST_WEBHOOK:-}
[ -z "$WEBHOOK" ] && [ -f "$HOOK_FILE" ] && WEBHOOK=$(head -1 "$HOOK_FILE")

mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR" 2>/dev/null || true

# ── 단계 ─────────────────────────────────────────────────────
#
# 이름|설명|언제 도는가
#   daily  매일
#   fri    금요일만
#   todo   아직 만들지 않았다 — 자리만 잡아 둔다
#
# ⚠ 순서를 바꾸기 전에 위 「왜 필요한가」를 읽을 것.
STAGES=(
  "catalog|스팀 카탈로그 전량|daily"
  "collect|리뷰 증분 수집|daily"
  "retry|실패 조각 재투입|daily"
  "convert|리뷰 landing → delta|daily"
  "news|공지 수집|daily"
  "news-convert|공지 landing → news_raw|daily"
  "compact|delta → base 병합|fri"
  "topics|토픽 분류|todo"
  "aggregate|Spark 집계|todo"
  "load|PostgreSQL 적재|todo"
)

CMD=${1:-status}; shift || true
FROM=""; ONLY=""; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="${2:-}"; shift 2 ;;
    --only) ONLY="${2:-}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) shift ;;
  esac
done

name_of() { echo "${1%%|*}"; }
desc_of() { local r="${1#*|}"; echo "${r%%|*}"; }
when_of() { echo "${1##*|}"; }

# ── Mattermost ───────────────────────────────────────────────
notify() {
  local level="$1" title="$2" body="$3"
  [ -n "$WEBHOOK" ] || return 0
  LEVEL="$level" TITLE="$title" BODY="$body" WEBHOOK="$WEBHOOK" python3 - <<'PY' >/dev/null 2>&1
import os, json, datetime, subprocess
E = os.environ
mark = {"OK": ":white_check_mark:", "RUN": ":arrow_forward:", "BAD": ":x:"}[E["LEVEL"]]
color = {"OK": "#2E7D32", "RUN": "#2E5C8A", "BAD": "#B00020"}[E["LEVEL"]]
now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime("%m-%d %H:%M")
subprocess.run(["curl", "-s", "-o", "/dev/null", "-m", "15", "-X", "POST",
                "-H", "Content-Type: application/json",
                "-d", json.dumps({
                    "username": "디스패치 배치",
                    "text": "%s **%s**" % (mark, E["TITLE"]),
                    "attachments": [{"color": color, "text": E["BODY"] + "\n\n_%s_" % now}],
                }, ensure_ascii=False), E["WEBHOOK"]])
PY
}

# ── 단계 실행 ────────────────────────────────────────────────
run_stage() {
  case "$1" in
    catalog)      bash "$HERE/21-catalog.sh" now ;;
    # ⚠ COLLECT_INCREMENTAL 을 여기서 못박는다. application.yaml 의 기본값도 true 지만,
    #   환경에 false 가 남아 있으면 조용히 전량이 된다. 파이프라인에서는 그럴 수 없다.
    collect)      guard_collect && ensure_workers                     && COLLECT_INCREMENTAL=true bash "$HERE/12-deploy-collector.sh" run ;;
    retry)        bash "$HERE/23-collect-retry.sh" now ;;
    convert)      spark_job com.ssafy.thispatch.spark.JsonToParquet && convert_today com.ssafy.thispatch.spark.JsonToParquet /review_landing ;;
    news)         ensure_workers && bash "$HERE/12-deploy-collector.sh" run-news ;;
    news-convert) spark_job com.ssafy.thispatch.spark.NewsToParquet && convert_today com.ssafy.thispatch.spark.NewsToParquet /news_landing ;;
    compact)      spark_job com.ssafy.thispatch.spark.Compaction ;;
    topics|aggregate|load)
      echo "  아직 만들지 않았다. 건너뛴다."
      return 0
      ;;
    *) echo "  모르는 단계: $1" >&2; return 1 ;;
  esac
}

spark_job() {
  [ -f "$SPARK_JAR" ] || { echo "  jar 가 없다: $SPARK_JAR" >&2; return 1; }
  "$SPARK_SUBMIT" --class "$1" --master yarn --deploy-mode client "$SPARK_JAR" "${@:2}"
}

# ── 오늘 날짜 것도 변환한다 ──────────────────────────────────
#
# ⚠ 이것이 없으면 자정을 넘긴 밤에 방금 받은 것이 통째로 안 변환된다.
#
#   landing 의 dt 는 잡 파라미터가 아니라 '파일을 쓴 시각' 으로 정해진다
#   (ReviewLandingWriter · NewsLandingWriter 둘 다 TimeRule.partition(collectedAt)).
#   그래서 수집이 자정을 넘기면 파티션이 갈린다 — 실제로 갈렸다:
#     /review_landing/dt=2026-09-14   수정 시각 2026-09-15 00:00
#
#   JsonToParquet·NewsToParquet 은 '오늘' 파티션을 건너뛴다. 수집기가 아직
#   쓰고 있는 중에 변환하면 반쪽짜리가 '변환 완료' 로 남기 때문이다. 맞는 판단이다.
#
#   그런데 파이프라인 안에서는 사정이 다르다. 단계가 순서대로 도니까
#   convert 가 시작될 때 수집은 이미 끝나 있다. 건너뛸 이유가 없고,
#   건너뛰면 아침에 raw 가 비어 있다.
#
#   그래서 인자 없이 한 번(밀린 날짜들), 오늘 날짜를 주고 한 번 더 부른다.
#   쓰기 모드가 Overwrite 라 같은 날짜를 두 번 만들어도 안전하다.
HDFS_BIN=${HDFS_BIN:-/opt/hadoop/bin/hdfs}

convert_today() {
  local cls="$1" root="$2" dt
  dt=$(date +%F)
  if ! "$HDFS_BIN" dfs -test -d "$root/dt=$dt" 2>/dev/null; then
    echo "  오늘($dt) landing 이 없다 — 더 변환할 것 없음."
    return 0
  fi
  echo "  오늘($dt) 것도 변환한다 (이 단계에서는 수집이 이미 끝났다)."
  spark_job "$cls" "$dt"
}

# ⚠ 워커가 떠 있지 않으면 매니저가 조각을 뿌려 놓고 영원히 기다린다. 에러도 안 난다.
ensure_workers() {
  local up
  up=$(bash "$HERE/12-deploy-collector.sh" status 2>/dev/null | grep -c "systemd active")
  if [ "${up:-0}" -ge 1 ]; then
    echo "  워커 $up 대 떠 있음"
    return 0
  fi
  echo "  워커가 하나도 없다. 띄운다."
  bash "$HERE/12-deploy-collector.sh" start >/dev/null 2>&1
  up=$(bash "$HERE/12-deploy-collector.sh" status 2>/dev/null | grep -c "systemd active")
  [ "${up:-0}" -ge 1 ] || { echo "  워커를 띄우지 못했다." >&2; return 1; }
  echo "  워커 $up 대 기동"
}

# ── collect 앞의 안전장치 ────────────────────────────────────
#
# 이 단계는 '증분' 이다. 전량 수집이 아니다.
#
#   전량은 2026-09-15 에 한 번 받았고 다시 받을 일이 없다. 그래서 파이프라인이
#   전량을 돌릴 수 있는 길 자체를 막는다. 막는 게 사고를 줄이는 것보다 쉽다.
#
# 전량과 증분은 무엇이 가르는가
#   단계가 아니라 ManagerConfig.resolveSince 가 실행할 때 정한다.
#   '성공으로 끝난 지난 수집' 이 있으면 그 시작 시각 이후만 받고(증분),
#   하나도 없으면 0 을 돌려준다 — 그것이 '끝까지 받는다' 는 뜻이다.
#
#   그래서 여기서 보는 것은 하나다. 기준으로 삼을 수집이 있는가.
#   없으면 이 단계는 전량이 되어 버리므로 돌리지 않는다.
#
# ⚠ 이것이 흔한 상황이라는 뜻은 아니다.
#   2026-09-11 리허설 3건이 COMPLETED 로 남아 있어서 기준은 이미 있다.
#   여기는 그 기준이 사라졌을 때를 위한 마지막 방어선이지, 일상적인 검사가 아니다.
#   일상적으로 걸리는 것은 아래 '이미 돌고 있다' 쪽이다 — 2026-09-15 실측.
#
# ⚠ DB 를 못 읽으면 통과시키지 않고 멈춘다.
#   모르는 채로 돌렸을 때의 최악이 전량 재수집(약 10시간)이다. 멈추는 쪽이 싸다.
q_batch() {
  PGPASSWORD="$BATCH_DB_PASSWORD" psql -h "$BATCH_DB_HOST" -U "$BATCH_DB_USER"     -d "$BATCH_DB_NAME" -tAq -c "$1" 2>/dev/null
}

guard_collect() {
  [ "$COLLECT_GUARD" = off ] && { echo "  안전장치 꺼짐 (COLLECT_GUARD=off)"; return 0; }

  local total done_ running
  total=$(q_batch "select count(*) from batch_job_execution e
                   join batch_job_instance i using(job_instance_id)
                   where i.job_name='collectJob';")
  case "${total:-}" in ''|*[!0-9]*)
    echo "  배치 DB 를 읽지 못했다 ($BATCH_DB_HOST/$BATCH_DB_NAME)." >&2
    echo "  전량을 다시 받을 수도 있어서 멈춘다. DB 를 확인할 것." >&2
    return 1 ;;
  esac

  running=$(q_batch "select count(*) from batch_job_execution e
                     join batch_job_instance i using(job_instance_id)
                     where i.job_name='collectJob' and e.status in ('STARTED','STARTING');")
  if [ "${running:-0}" -ge 1 ]; then
    echo "  수집이 이미 돌고 있다. 또 띄우지 않는다." >&2
    return 1
  fi

  done_=$(q_batch "select count(*) from batch_job_execution e
                   join batch_job_instance i using(job_instance_id)
                   where i.job_name='collectJob' and e.status='COMPLETED';")
  if [ "${done_:-0}" -ge 1 ]; then
    echo "  성공으로 끝난 지난 수집 $done_ 건 — 증분으로 돈다."
    return 0
  fi

  echo "  기준으로 삼을 수집이 없다 (전체 $total 건 · 성공 0 건)." >&2
  echo "  이대로 돌리면 증분이 아니라 '전량' 이 된다 (1.47억 건 · 약 10시간)." >&2
  echo "  파이프라인은 전량을 돌리지 않는다. 여기서 멈춘다." >&2
  echo >&2
  echo "  지난 수집이 파킹된 조각 때문에 안 끝난 것이라면, 살려서 COMPLETED 로 만든다:" >&2
  echo "    bash 23-collect-retry.sh now" >&2
  echo "    bash 24-pipeline.sh daily --from convert      # 수집을 건너뛰고 이어서" >&2
  echo >&2
  echo "  정말로 전량을 다시 받을 작정이라면 파이프라인 밖에서 손으로 돌릴 것:" >&2
  echo "    COLLECT_INCREMENTAL=false bash 12-deploy-collector.sh run" >&2
  return 1
}

# ── 오늘 돌 단계를 고른다 ────────────────────────────────────
selected() {
  local today started=0
  # ⚠ %a 가 아니라 %u 를 쓴다. %a 는 로캘을 탄다 — 한국어 로캘이 깔린 노트북에서는
  #   "금" 이 나와서 "Fri" 와 절대 같아지지 않고, compaction 이 영영 안 돈다.
  #   %u 는 어느 로캘에서도 금요일이 5 다 (실측).
  today=$(date +%u)   # 1=월 … 5=금 … 7=일
  for s in "${STAGES[@]}"; do
    local n w
    n=$(name_of "$s"); w=$(when_of "$s")
    if [ -n "$ONLY" ]; then
      [ "$n" = "$ONLY" ] && echo "$s"
      continue
    fi
    if [ -n "$FROM" ] && [ "$started" = 0 ]; then
      [ "$n" = "$FROM" ] && started=1 || continue
    fi
    # ⚠ compaction 은 금요일에만 돈다.
    #   주말에는 노트북이 각자 집으로 흩어져 IP 가 바뀐다. 클러스터가 아예
    #   구성되지 않으므로 토요일에 걸어 두면 영영 안 돈다. (2026-09-15 결정)
    if [ "$w" = fri ] && [ "$today" != 5 ]; then
      continue
    fi
    echo "$s"
  done
}

case "$CMD" in
stages)
  echo "== 단계 =="
  for s in "${STAGES[@]}"; do
    printf "  %-13s %-26s %s\n" "$(name_of "$s")" "$(desc_of "$s")" "$(when_of "$s")"
  done
  ;;

daily)
  # ⚠ 두 번 돌면 안 된다. compaction 이 둘 겹치면 base 가 망가진다.
  exec 9>"$LOCK"
  if ! flock -n 9; then
    echo "이미 돌고 있다 ($LOCK). 아무것도 하지 않는다." >&2
    exit 1
  fi

  mapfile -t TODO < <(selected)
  if [ "${#TODO[@]}" = 0 ]; then
    echo "돌 단계가 없다."
    exit 0
  fi

  echo "══ 배치 시작 $(date '+%m-%d %H:%M') ══════════════════"
  echo "  오늘 돌 단계: $(for s in "${TODO[@]}"; do printf '%s ' "$(name_of "$s")"; done)"
  echo

  if [ "$DRY" = 1 ]; then
    echo "  --dry-run 이라 여기서 멈춘다."
    exit 0
  fi

  notify RUN "배치 시작" "$(for s in "${TODO[@]}"; do printf -- "- %s\n" "$(desc_of "$s")"; done)"

  STARTED=$(date +%s)
  for s in "${TODO[@]}"; do
    n=$(name_of "$s"); d=$(desc_of "$s")
    echo "── $n · $d ──────────────────────────"
    t0=$(date +%s)
    if ! run_stage "$n"; then
      spent=$(( $(date +%s) - STARTED ))
      echo
      echo "✖ '$n' 에서 멈췄다. 다음으로 넘어가지 않는다." >&2
      # ⚠ 잘못된 데이터로 집계해서 화면에 이상한 숫자를 띄우느니 멈추는 편이 낫다.
      notify BAD "배치 실패 — $d" \
        "\`$n\` 단계에서 멈췄습니다.\n\n고친 뒤 이어서 돌리려면:\n\`\`\`\nbash 24-pipeline.sh daily --from $n\n\`\`\`\n\n여기까지 $((spent / 60))분"
      echo "$(date +%s) FAILED $n" > "$LAST"
      exit 1
    fi
    echo "  $n 끝 ($(( ($(date +%s) - t0) / 60 ))분)"
    echo
  done

  spent=$(( $(date +%s) - STARTED ))
  echo "══ 배치 끝 · $((spent / 60))분 ══════════════════════"
  notify OK "배치 끝" "$(for s in "${TODO[@]}"; do printf -- "- %s\n" "$(desc_of "$s")"; done)
총 $((spent / 60))분"
  echo "$(date +%s) OK" > "$LAST"
  ;;

install)
  [ "$(id -u)" = 0 ] && { echo "sudo 없이 그냥 실행하세요." >&2; exit 1; }
  echo "== 타이머 등록 (매일 09:05 KST) =================="
  SELF=$(readlink -f "$0")
  sudo tee /etc/systemd/system/thispatch-pipeline.service >/dev/null <<UNIT
[Unit]
Description=디스패치 일일 배치
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$USER
WorkingDirectory=$HOME
Environment=STATE_DIR=$STATE_DIR
# 몇 시간이 걸린다. systemd 가 중간에 끊지 않게 한다.
TimeoutStartSec=0
ExecStart=/usr/bin/env bash $SELF daily
UNIT
  sudo tee /etc/systemd/system/thispatch-pipeline.timer >/dev/null <<'UNIT'
[Unit]
Description=디스패치 일일 배치 — 매일 09:05

[Timer]
# ⚠ 새벽이 아니라 아침이다. 수집은 서버가 아니라 노트북에서 돈다.
#   교육장 노트북은 밤에 꺼져 있으므로 새벽에 걸어 두면 영영 안 돈다.
OnCalendar=*-*-* 09:05:00 Asia/Seoul
# 노트북이 꺼져 있어 걸렀으면 켜지자마자 한 번 따라잡는다
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable --now thispatch-pipeline.timer
  systemctl list-timers thispatch-pipeline.timer --no-pager | sed -n '1,3p'
  echo
  echo "  ⚠ 카탈로그 타이머(09:00)는 이제 필요 없다. 파이프라인이 부른다:"
  echo "     bash $HERE/21-catalog.sh remove"
  echo "  로그: journalctl -u thispatch-pipeline -n 50"
  ;;

remove)
  sudo systemctl disable --now thispatch-pipeline.timer 2>/dev/null
  sudo rm -f /etc/systemd/system/thispatch-pipeline.service \
             /etc/systemd/system/thispatch-pipeline.timer
  sudo systemctl daemon-reload
  echo "  해제했습니다."
  ;;

status)
  echo "== 배치 타이머 =================================="
  echo "  $(systemctl is-active thispatch-pipeline.timer 2>/dev/null) / $(systemctl is-enabled thispatch-pipeline.timer 2>/dev/null)"
  systemctl list-timers thispatch-pipeline.timer --no-pager 2>/dev/null | sed -n '2p' | sed 's/^/  /'
  echo
  if [ -f "$LAST" ]; then
    read -r ts st rest < "$LAST"
    echo "  최근 실행: $(date -d "@$ts" '+%m-%d %H:%M') · $st $rest"
  else
    echo "  최근 실행 기록 없음"
  fi
  echo
  echo "  오늘 돌 단계: $(selected | while read -r s; do printf '%s ' "$(name_of "$s")"; done)"
  ;;

*)
  sed -n '2,12p' "$0"
  exit 1
  ;;
esac
