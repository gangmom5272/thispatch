#!/usr/bin/env bash
# 실패한 조각이 없어질 때까지 수집 잡을 다시 돌린다
#
#   bash 23-collect-retry.sh status   지금 상태만 본다 (아무것도 안 고침)
#   bash 23-collect-retry.sh now      실패 조각이 0 이 될 때까지 다시 돌린다
#   bash 23-collect-retry.sh unstick  STARTED 로 굳은 조각만 풀어 준다
#
# 왜 필요한가
#   실패한 조각은 큐로 돌아가지 않는다. Spring Batch 의 원격 파티셔닝은
#   실패한 스텝을 그 판 안에서 재시도하지 않는다. 사람이 잡을 다시 띄워야
#   이어진다.
#
#   2026-09-15 최초 전량 수집에서 조각 235개 중 86개(37%)가 이렇게 파킹됐다.
#     67건  JobRepository failure forcing rollback   네트워크 끊김
#     17건  Filesystem closed                        워커 재시작
#      2건  HDFS IO interrupted                      워커 재시작
#
#   교육장 무선망이 끊기는 것 자체는 못 고친다. 끊김은 몇 분이고 데이터도
#   안 날아간다. 손해는 그 몇 분 때문에 조각이 통째로 파킹되는 쪽에서 난다.
#
# 어떻게 이어지는가
#   같은 잡 파라미터(dt)로 다시 띄우면 Spring Batch 가 완료된 조각은 건너뛰고
#   실패한 것만 저장된 지점부터 한다. 조각마다 자기 게임 목록(appids)과
#   재개 지점(review.appIndex)을 ExecutionContext 에 들고 있다 —
#   2026-09-15 에 실제로 풀어서 확인했다.
#
# ⚠ 무한히 돌지 않는다.
#   특정 게임이 계속 실패하는 것이라면 몇 번을 돌려도 같다. 실패 수가 줄지
#   않으면 멈추고 Mattermost 로 알린다.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$HERE/12-deploy-collector.sh"
PROGRESS="$HERE/22-collect-progress.sh"

BATCH_DB_HOST=${BATCH_DB_HOST:-127.0.0.1}
BATCH_DB_NAME=${BATCH_DB_NAME:-thispatch_batch}
BATCH_DB_USER=${BATCH_DB_USER:-thispatch}
BATCH_DB_PASSWORD=${BATCH_DB_PASSWORD:-dispatch-batch-local}

# 몇 번까지 다시 돌릴 것인가. 한 번에 몇 시간이 걸리므로 크게 잡지 않는다.
MAX_ROUNDS=${MAX_ROUNDS:-5}

# 매니저를 띄우는 systemd 임시 유닛 이름.
# ⚠ nohup 이나 & 로 띄우면 이 셸이 끝날 때 같이 죽는다. 2026-09-14 에 겪었다 —
#   로그 파일조차 만들어지지 않았다.
UNIT=${UNIT:-thispatch-collect}

CMD=${1:-status}

q() {
  PGPASSWORD="$BATCH_DB_PASSWORD" psql -h "$BATCH_DB_HOST" -U "$BATCH_DB_USER" \
    -d "$BATCH_DB_NAME" -tAq -F'|' -c "$1" 2>/dev/null
}

# ── 지금 잡이 어떤 상태인가 ───────────────────────────────────
read_state() {
  local row
  row=$(q "
    select e.job_execution_id, e.status, p.parameter_value
    from batch_job_execution e
    join batch_job_instance i using(job_instance_id)
    left join batch_job_execution_params p
           on p.job_execution_id = e.job_execution_id and p.parameter_name = 'dt'
    where i.job_name = 'collectJob'
    order by e.job_execution_id desc limit 1;")
  JOB_ID=$(echo "$row" | cut -d'|' -f1)
  JOB_ST=$(echo "$row" | cut -d'|' -f2)
  JOB_DT=$(echo "$row" | cut -d'|' -f3)
  : "${JOB_ID:=0}" "${JOB_ST:=NONE}" "${JOB_DT:=}"

  P_DONE=$(q "select count(*) from batch_step_execution
              where job_execution_id=$JOB_ID and status='COMPLETED';")
  P_FAIL=$(q "select count(*) from batch_step_execution
              where job_execution_id=$JOB_ID and status='FAILED';")
  P_RUN=$(q "select count(*) from batch_step_execution
             where job_execution_id=$JOB_ID and status in ('STARTED','STARTING');")
  : "${P_DONE:=0}" "${P_FAIL:=0}" "${P_RUN:=0}"

  # 처음 돌 때 조각을 몇 개로 나눴는가.
  #
  # ⚠ 이걸 그대로 다시 줘야 한다. AppidPartitioner 는 게임을 번갈아 나눠 담고
  #   이름을 partition0..N-1 로 붙인다. 조각 수가 달라지면 같은 이름이 전혀
  #   다른 게임 묶음을 뜻하게 되고, 늘어난 조각들이 이미 받은 게임을 처음부터
  #   다시 받는다. 오류는 안 난다 — 그래서 더 위험하다.
  #
  #   이 잡 인스턴스의 모든 실행을 통틀어 세야 한다. 재시작한 실행에는 끝난
  #   조각이 빠져 있어서 그것만 세면 수가 줄어든다.
  ORIG_PARTS=$(q "
    select count(distinct se.step_name)
    from batch_step_execution se
    join batch_job_execution e using(job_execution_id)
    where e.job_instance_id = (select job_instance_id from batch_job_execution
                               where job_execution_id=$JOB_ID)
      and se.step_name like 'collect.worker:partition%';")
  : "${ORIG_PARTS:=0}"
}

show() {
  read_state
  echo "  잡 #$JOB_ID · $JOB_ST · dt=$JOB_DT"
  echo "  조각  완료 $P_DONE · 실패 $P_FAIL · 안 끝남 $P_RUN  (처음 나눈 수 $ORIG_PARTS)"
  echo "  매니저 유닛: $(systemctl is-active "$UNIT" 2>/dev/null)"
}

# ── STARTED 로 굳은 것을 풀어 준다 ────────────────────────────
#
# ⚠ 이걸 안 하면 재시작이 이렇게 거부된다.
#     Cannot restart step from STARTED status
#   Spring Batch 는 그 조각이 「지금 돌고 있는 것」인지 「죽은 것」인지 구분하지
#   못한다. 워커가 죽으면 STARTED 인 채로 영원히 남는다.
#
# ⚠ 진짜로 돌고 있을 때 하면 안 된다. 매니저가 떠 있으면 거부한다.
unstick() {
  if [ "$(systemctl is-active "$UNIT" 2>/dev/null)" = active ]; then
    echo "  ⚠ 매니저가 아직 돌고 있다. 먼저 멈춰라:  sudo systemctl stop $UNIT" >&2
    return 1
  fi
  read_state
  if [ "$P_RUN" = 0 ] && [ "$JOB_ST" != STARTED ]; then
    echo "  굳은 것이 없다."
    return 0
  fi
  echo "  조각 $P_RUN 개와 잡 #$JOB_ID 를 실패로 찍는다 (재시작이 집어가게)"
  q "update batch_step_execution
        set status='FAILED', exit_code='FAILED',
            exit_message = coalesce(exit_message,'') ||
              '[23-collect-retry.sh] 워커가 죽은 채 STARTED 로 남아 재시작을 막고 있었다',
            end_time = coalesce(end_time, now()), last_updated = now()
      where job_execution_id=$JOB_ID and status in ('STARTED','STARTING');" >/dev/null
  q "update batch_job_execution
        set status='FAILED', exit_code='FAILED',
            end_time = coalesce(end_time, now()), last_updated = now()
      where job_execution_id=$JOB_ID and status in ('STARTED','STARTING','STOPPING');" >/dev/null
  echo "  풀었다."
}

# ── 매니저를 한 판 돌리고 끝날 때까지 기다린다 ────────────────
run_once() {
  local dt="$1" parts="$2"
  sudo -n systemctl reset-failed "$UNIT" 2>/dev/null
  sudo -n systemd-run \
      --unit="$UNIT" \
      --uid=1000 --gid=1000 \
      --setenv=HOME="$HOME" \
      --setenv=DT="$dt" \
      --setenv=PARTITIONS="$parts" \
      --setenv=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      --working-directory="$HOME" \
      --collect \
      /bin/bash "$DEPLOY" run >/dev/null || return 1

  echo -n "  돌고 있다"
  while [ "$(systemctl is-active "$UNIT" 2>/dev/null)" = active ]; do
    sleep 60
    echo -n "."
  done
  echo " 끝났다."
}

alert() {
  [ -x "$PROGRESS" ] || return 0
  bash "$PROGRESS" now -f >/dev/null 2>&1 || true
}

case "$CMD" in
status)
  echo "== 수집 잡 상태 =============================="
  show
  ;;

unstick)
  echo "== 굳은 조각 풀기 ============================"
  # ⚠ 거부했으면 종료코드로도 알려야 한다. 0 을 돌려주면 이걸 부르는 쪽이
  #   (오케스트레이션이든 사람이든) 풀린 줄 알고 다음으로 넘어간다.
  if ! unstick; then
    echo
    show
    exit 1
  fi
  echo
  show
  ;;

now)
  echo "== 실패 조각 다시 돌리기 ======================"
  read_state
  if [ -z "$JOB_DT" ]; then
    echo "  수집 잡 기록이 없다. 처음이라면 12-deploy-collector.sh run 을 쓴다." >&2
    exit 1
  fi
  # ⚠ 조각 수를 못 읽으면 돌리지 않는다. 그냥 돌리면 조각이 새로 나뉘어
  #   이미 받은 게임을 처음부터 다시 받는다. 오류도 안 난다.
  if [ "${ORIG_PARTS:-0}" -lt 1 ]; then
    echo "  처음 나눈 조각 수를 읽지 못했다. 그대로 돌리면 조각이 다시 나뉜다." >&2
    exit 1
  fi
  echo "  대상: 잡 #$JOB_ID · dt=$JOB_DT · 조각 $ORIG_PARTS 개로 고정"
  echo

  for round in $(seq 1 "$MAX_ROUNDS"); do
    read_state
    before=$P_FAIL

    if [ "$JOB_ST" = COMPLETED ] && [ "$before" = 0 ]; then
      echo "  다 끝났다. 실패 조각 없음."
      exit 0
    fi
    if [ "$before" = 0 ] && [ "$P_RUN" = 0 ]; then
      echo "  실패한 조각이 없다."
      exit 0
    fi

    echo "── $round 번째 ──────────────────────────────"
    echo "  시작 전 실패 $before 개"
    unstick || exit 1

    # ⚠ 워커가 떠 있어야 조각을 집어간다. 꺼져 있으면 매니저가 영원히 기다린다.
    echo "  워커 확인"
    bash "$DEPLOY" status 2>&1 | sed -n '2,8p'

    run_once "$JOB_DT" "$ORIG_PARTS" \
      || { echo "  매니저를 띄우지 못했다." >&2; exit 1; }

    read_state
    echo "  끝난 뒤 실패 $P_FAIL 개 (완료 $P_DONE)"

    if [ "$P_FAIL" = 0 ]; then
      echo
      echo "  ✔ 실패 조각이 없어졌다."
      alert
      exit 0
    fi

    # ⚠ 줄지 않으면 더 돌려도 같다. 특정 게임이 계속 실패하는 것이다.
    if [ "$P_FAIL" -ge "$before" ]; then
      echo
      echo "  ✖ $before -> $P_FAIL. 줄지 않았다. 멈춘다." >&2
      echo "    같은 조각이 계속 실패하는 것이니 사유를 봐야 한다:" >&2
      echo "    psql -d $BATCH_DB_NAME -c \"select step_name, left(exit_message,200)" >&2
      echo "      from batch_step_execution where status='FAILED' limit 5;\"" >&2
      alert
      exit 1
    fi
    echo
  done

  echo "  $MAX_ROUNDS 번 돌렸는데 아직 실패 $P_FAIL 개가 남았다. 멈춘다." >&2
  alert
  exit 1
  ;;

*)
  sed -n '2,6p' "$0"
  exit 1
  ;;
esac
