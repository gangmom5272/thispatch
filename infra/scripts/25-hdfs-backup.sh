#!/usr/bin/env bash
# HDFS 데이터 백업 — 마스터 노드에서만 실행
#
#   ./25-hdfs-backup.sh plan       뭘 보낼지만 보여준다. 데이터를 읽지 않는다
#   ./25-hdfs-backup.sh now        안 보낸 것만 보낸다
#   ./25-hdfs-backup.sh list       원격에 뭐가 쌓였나
#   ./25-hdfs-backup.sh install    systemd 타이머 등록 (매일 04:30)
#   ./25-hdfs-backup.sh restore    복구 절차 출력 (실행하지는 않는다)
#   ./25-hdfs-backup.sh remove     타이머 해제
#
#   ONLY=/news_landing ./25-hdfs-backup.sh now     한 경로만
#   MAX_UNITS=1        ./25-hdfs-backup.sh now     한 덩어리만 (시험용)
#
# 왜 필요한가
#   10-namenode-backup.sh 가 서버2A 로 보내는 것은 NameNode 메타데이터뿐이고,
#   18-db-backup.sh 가 보내는 것은 서비스 DB 뿐이다. 리뷰 원본은 교육장
#   노트북 5대의 HDFS 에만 있다.
#
#   복제가 3 이라 노트북 한두 대가 죽어도 버틴다. 하지만 5대가 같은 방에서
#   같은 랜을 쓴다. 전량 수집을 다시 받는 데 22시간이 넘게 걸린다
#   (2026-09-16 실측 — Counter-Strike 2 한 게임이 986만 건이다).
#
# 무엇을 담는가
#   /review_landing   스팀이 준 원본 JSON.gz. 되받으려면 22시간
#   /review_raw       그것을 변환한 파케이. 원본에서 다시 만들 수 있지만
#                     Spark 를 한 번 더 돌려야 한다
#   /news_landing     공지 원본
#   /news_raw         그것을 변환한 파케이
#
# 어떻게 담는가 — 날짜(dt=) 단위로 쪼갠다
#   1. 끊겨도 그 날짜만 다시 보낸다. 교육장 무선망은 하루에도 몇 번 끊긴다
#      (12-deploy-collector.sh · application.yaml 주석 참고).
#   2. 지난 날짜는 더 안 변하므로 한 번 보내면 다시 안 보낸다.
#   3. 파일이 161만 개다(2026-09-16). 하나씩 보내면 끝나지 않는다.
#
#   ⚠ tar 로 묶기만 하고 다시 압축하지 않는다(-z 없음). 안에 든 것이 이미
#     gzip·parquet 이라 CPU 만 쓰고 크기는 안 줄어든다.
#
# ⚠ 수집이 도는 중에는 돌리지 않는다
#   데이터를 HDFS 에서 읽으면 마스터의 NameNode 를 지난다. 그 마스터가
#   워커 4대의 HDFS 쓰기를 전부 받아내고 있다. 여기에 수십 GB 읽기를 얹으면
#   수집이 통째로 느려진다. 'now' 는 수집 중이면 스스로 거부한다.
set -uo pipefail

export HADOOP_HOME=${HADOOP_HOME:-/opt/hadoop}
export HADOOP_CONF_DIR=${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}
export PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)
  export JAVA_HOME
fi
HDFS=${HDFS:-$HADOOP_HOME/bin/hdfs}

# ── 백업 대상 서버 ────────────────────────────────────────────
# ⚠ 키 기본값이 10-namenode-backup.sh 와 같다. 둘 다 마스터에서 돌기 때문이다.
#   18-db-backup.sh 는 서버1 에서 돌아서 thispatch-backup 키를 쓴다.
BACKUP_HOST=${BACKUP_HOST:-j15a202a.p.ssafy.io}
BACKUP_USER=${BACKUP_USER:-ubuntu}
BACKUP_KEY=${BACKUP_KEY:-$HOME/.ssh/J15A202T.pem}
BACKUP_DIR=${BACKUP_DIR:-/home/ubuntu/thispatch-backup/hdfs}

# 묶는 동안 쓰는 자리. 덩어리 하나가 끝나면 지운다.
STAGE=${STAGE:-/data/backup/hdfs-stage}

# 보낼 경로. ONLY 로 좁힐 수 있다.
SOURCES=${SOURCES:-"/review_landing /review_raw /news_landing /news_raw"}
ONLY=${ONLY:-}
MAX_UNITS=${MAX_UNITS:-0}          # 0 이면 제한 없음

SSH_OPTS="-o ConnectTimeout=10 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"

CMD=${1:-plan}
banner() { echo; echo "══ $* ══════════════════════════════════"; }
say() { printf '  %s\n' "$*"; }

# 물어보기만 할 때. -n 으로 표준입력을 막아서, 반복문 안에서 ssh 가 남의
# 입력을 먹어치우는 사고를 막는다.
rsh() { ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "$@"; }

# 보낼 때. ⚠ 여기에 -n 을 붙이면 안 된다. 표준입력으로 tar 를 흘려보내는데
#   -n 이 그것을 /dev/null 로 막아 버린다.
rsh_send() { ssh -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "$@"; }

remote_ready() {
  [ -f "$BACKUP_KEY" ] || { say "백업 키가 없다: $BACKUP_KEY"; return 1; }
  rsh true 2>/dev/null || { say "서버2A 에 닿지 않는다: $BACKUP_USER@$BACKUP_HOST"; return 1; }
}

collecting() {
  for u in thispatch-collect thispatch-retry thispatch-chain; do
    [ "$(systemctl is-active "$u" 2>/dev/null)" = active ] && { echo "$u"; return 0; }
  done
  return 1
}

# ── 보낼 덩어리 목록 ──────────────────────────────────────────
#
# 덩어리는 dt= 폴더다. 단, 그 안이 다시 폴더로 나뉘어 있으면(시각 분할)
# 그 아래를 덩어리로 쓴다. 2026-09-15 의 HDFS 디렉터리 한도 사고 이후
# 랜딩이 dt=날짜/시(HH) 로 나뉘어서, 날짜 하나가 너무 커지는 것을 막는다.
#
# ⚠ -ls -R 를 쓰지 않는다. /review_landing 한 곳에만 파일이 161만 개라
#   (2026-09-16) 전부 나열하면 NameNode 가 그 목록을 만드느라 멈춘다.
#   폴더만 한 단계씩 내려간다. 깊이는 root/delta/dt=날짜/시 가 최대다.
subdirs() { "$HDFS" dfs -ls "$1" 2>/dev/null | awk '/^d/{print $NF}'; }

units() {
  local root d dd depth queue next
  for root in $SOURCES; do
    [ -n "$ONLY" ] && [[ "$root" != "$ONLY"* ]] && continue
    "$HDFS" dfs -test -d "$root" 2>/dev/null || continue

    queue="$root"
    for depth in 1 2 3; do
      next=""
      for d in $queue; do
        case "${d##*/}" in
          dt=*)
            # dt= 를 찾았다. 그 아래가 폴더로 또 나뉘면 그것들이 덩어리다.
            dd=$(subdirs "$d")
            if [ -n "$dd" ]; then printf '%s\n' $dd; else printf '%s\n' "$d"; fi
            ;;
          *)
            next="$next $(subdirs "$d")"
            ;;
        esac
      done
      queue="$next"
      [ -n "$(printf '%s' "$queue" | tr -d '[:space:]')" ] || break
    done
  done
}

# HDFS 경로를 파일 이름 하나로 바꾼다. /review_landing/dt=2026-09-16/03
#   -> review_landing__dt=2026-09-16__03
flatten() { printf '%s' "${1#/}" | sed 's|/|__|g'; }

# 그 덩어리의 지문. NameNode 메타데이터만 읽는다 — 데이터를 안 건드린다.
# hdfs dfs -count 는 "폴더수 파일수 바이트수" 를 준다.
fingerprint() {
  "$HDFS" dfs -count "$1" 2>/dev/null | awk '{print $1"/"$2"/"$3}'
}

human() { numfmt --to=iec --suffix=B "${1:-0}" 2>/dev/null || echo "${1:-0}"; }

case "$CMD" in

# ────────────────────────────────────────────────────────────
plan)
  banner "보낼 덩어리 (데이터는 읽지 않는다)"
  printf '  %-46s %10s %12s\n' "덩어리" "파일" "크기"
  total=0; n=0
  while read -r u; do
    [ -n "$u" ] || continue
    fp=$(fingerprint "$u"); files=$(echo "$fp" | cut -d/ -f2); bytes=$(echo "$fp" | cut -d/ -f3)
    total=$((total + bytes)); n=$((n + 1))
    printf '  %-46s %10s %12s\n' "$u" "$files" "$(human "$bytes")"
  done < <(units)
  echo
  say "덩어리 $n 개 · 합계 $(human "$total")"
  if remote_ready 2>/dev/null; then
    free=$(rsh "df -B1 --output=avail '$BACKUP_DIR' 2>/dev/null || df -B1 --output=avail /home | tail -1" 2>/dev/null | tail -1)
    say "서버2A 여유 $(human "${free:-0}")"
  fi
  if w=$(collecting); then
    echo
    say "⚠ $w 가 돌고 있다. 지금 'now' 를 쓰면 거부된다."
  fi
  ;;

# ────────────────────────────────────────────────────────────
now)
  if w=$(collecting) && [ -z "${FORCE:-}" ]; then
    banner "거부"
    say "$w 가 돌고 있다."
    say "수집 중에 수십 GB 를 읽으면 NameNode 를 거쳐 나가 수집이 느려진다."
    say "정말 지금 해야 하면  FORCE=1 $0 now"
    exit 2
  fi
  remote_ready || exit 1

  mkdir -p "$STAGE" || exit 1
  rsh "mkdir -p '$BACKUP_DIR'" || exit 1

  banner "HDFS 백업 → $BACKUP_USER@$BACKUP_HOST:$BACKUP_DIR"
  sent=0; skipped=0; failed=0; done_units=0

  while read -r u; do
    [ -n "$u" ] || continue
    [ "$MAX_UNITS" -gt 0 ] && [ "$done_units" -ge "$MAX_UNITS" ] && break
    done_units=$((done_units + 1))

    name=$(flatten "$u")
    fp=$(fingerprint "$u")
    [ -n "$fp" ] || { say "건너뜀(읽을 수 없음) $u"; failed=$((failed + 1)); continue; }

    # 지난번과 같은 내용이면 보내지 않는다.
    old=$(rsh "cat '$BACKUP_DIR/$name.fingerprint' 2>/dev/null" 2>/dev/null)
    if [ "$old" = "$fp" ]; then
      skipped=$((skipped + 1))
      say "그대로  $u"
      continue
    fi

    bytes=$(echo "$fp" | cut -d/ -f3)
    say "보냄    $u  ($(human "$bytes"))"

    work="$STAGE/$name"
    rm -rf "$work"; mkdir -p "$work" || { failed=$((failed + 1)); continue; }

    if ! "$HDFS" dfs -get "$u" "$work/data" 2>/dev/null; then
      say "        HDFS 에서 못 가져왔다"
      rm -rf "$work"; failed=$((failed + 1)); continue
    fi

    # 묶으면서 동시에 지문을 뜨고 동시에 보낸다. 로컬에 tar 를 또 만들지 않는다.
    shafile="$STAGE/$name.sha"
    if ! tar -cf - -C "$work" data \
         | tee >(sha256sum | awk '{print $1}' > "$shafile") \
         | rsh_send "cat > '$BACKUP_DIR/$name.tar.part'"; then
      say "        전송 실패"
      rm -rf "$work" "$shafile"
      rsh "rm -f '$BACKUP_DIR/$name.tar.part'" 2>/dev/null
      failed=$((failed + 1)); continue
    fi

    local_sha=$(cat "$shafile" 2>/dev/null)
    remote_sha=$(rsh "sha256sum '$BACKUP_DIR/$name.tar.part' 2>/dev/null | awk '{print \$1}'" 2>/dev/null)
    if [ -z "$local_sha" ] || [ "$local_sha" != "$remote_sha" ]; then
      say "        지문이 다르다 — 버린다 (로컬 ${local_sha:0:12} / 원격 ${remote_sha:0:12})"
      rsh "rm -f '$BACKUP_DIR/$name.tar.part'" 2>/dev/null
      rm -rf "$work" "$shafile"; failed=$((failed + 1)); continue
    fi

    # 온전히 도착한 것만 제 이름을 갖는다. 중간에 죽어도 .part 만 남는다.
    rsh "mv '$BACKUP_DIR/$name.tar.part' '$BACKUP_DIR/$name.tar' \
         && printf '%s' '$local_sha' > '$BACKUP_DIR/$name.sha256' \
         && printf '%s' '$fp'        > '$BACKUP_DIR/$name.fingerprint'" || {
      say "        원격 마무리 실패"
      rm -rf "$work" "$shafile"; failed=$((failed + 1)); continue
    }

    rm -rf "$work" "$shafile"
    sent=$((sent + 1))
  done < <(units)

  echo
  say "보냄 $sent · 그대로 $skipped · 실패 $failed"
  [ "$failed" -gt 0 ] && exit 1
  exit 0
  ;;

# ────────────────────────────────────────────────────────────
list)
  remote_ready || exit 1
  banner "서버2A 에 쌓인 것"
  rsh "ls -la '$BACKUP_DIR' 2>/dev/null | grep -E '\.tar$' || echo '  (없음)'" | sed 's/^/  /'
  echo
  rsh "du -sh '$BACKUP_DIR' 2>/dev/null; df -h '$BACKUP_DIR' 2>/dev/null | tail -1" | sed 's/^/  /'
  ;;

# ────────────────────────────────────────────────────────────
restore)
  banner "복구 절차 (이 스크립트는 실행하지 않는다)"
  cat <<TEXT
  1. 서버2A 에서 덩어리를 받는다
       scp -i $BACKUP_KEY \\
           $BACKUP_USER@$BACKUP_HOST:$BACKUP_DIR/review_landing__dt=2026-09-16__03.tar .

  2. 지문을 맞춰 본다 — 받는 중에 깨졌는지 본다
       ssh -i $BACKUP_KEY $BACKUP_USER@$BACKUP_HOST \\
           cat $BACKUP_DIR/review_landing__dt=2026-09-16__03.sha256
       sha256sum review_landing__dt=2026-09-16__03.tar

  3. 푼다. 안에는 'data' 라는 이름으로 들어 있다
       mkdir -p /data/restore && tar -xf ...tar -C /data/restore

  4. HDFS 에 올린다. 이름에서 __ 를 / 로 되돌리면 원래 경로다
       $HDFS dfs -mkdir -p /review_landing/dt=2026-09-16
       $HDFS dfs -put /data/restore/data/* /review_landing/dt=2026-09-16/03

  ⚠ NameNode 가 통째로 날아간 경우라면 이것보다 10-namenode-backup.sh restore
    가 먼저다. 메타데이터가 없으면 블록이 있어도 파일로 못 읽는다.
    clusterID 는 'dispatch' 다. 절대 바꾸지 않는다.
TEXT
  ;;

# ────────────────────────────────────────────────────────────
install)
  UNIT=/etc/systemd/system/thispatch-hdfsbackup.service
  TIMER=/etc/systemd/system/thispatch-hdfsbackup.timer
  sudo tee "$UNIT" >/dev/null <<UNITEOF
[Unit]
Description=thispatch HDFS 데이터 백업 (서버2A)
After=network-online.target

[Service]
Type=oneshot
User=$USER
Environment=HADOOP_HOME=$HADOOP_HOME
Environment=HADOOP_CONF_DIR=$HADOOP_CONF_DIR
Environment=BACKUP_HOST=$BACKUP_HOST
Environment=BACKUP_KEY=$BACKUP_KEY
Environment=BACKUP_DIR=$BACKUP_DIR
Environment=STAGE=$STAGE
ExecStart=/usr/bin/env bash $(readlink -f "$0") now
UNITEOF
  sudo tee "$TIMER" >/dev/null <<TIMEREOF
[Unit]
Description=thispatch HDFS 데이터 백업 매일

[Timer]
OnCalendar=*-*-* 04:30:00
Persistent=true

[Install]
WantedBy=timers.target
TIMEREOF
  sudo systemctl daemon-reload
  sudo systemctl enable --now thispatch-hdfsbackup.timer
  banner "등록됨"
  systemctl list-timers thispatch-hdfsbackup.timer --no-pager | sed 's/^/  /'
  ;;

remove)
  sudo systemctl disable --now thispatch-hdfsbackup.timer 2>/dev/null
  sudo rm -f /etc/systemd/system/thispatch-hdfsbackup.{service,timer}
  sudo systemctl daemon-reload
  banner "해제됨"
  ;;

*)
  sed -n '2,12p' "$0"
  exit 1
  ;;
esac
