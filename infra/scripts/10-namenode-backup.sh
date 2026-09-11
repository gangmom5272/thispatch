#!/usr/bin/env bash
# NameNode 메타데이터 백업 — 마스터 노드에서만 실행
#
#   ./10-namenode-backup.sh now        지금 한 번 백업
#   ./10-namenode-backup.sh install    systemd 타이머 등록 (매시 정각)
#   ./10-namenode-backup.sh list       로컬·원격에 쌓인 백업 확인
#   ./10-namenode-backup.sh restore    복구 절차 출력 (실행하지는 않는다)
#   ./10-namenode-backup.sh remove     타이머 해제
#
# 왜 필요한가
#   HDFS 의 "어떤 파일이 어떤 블록으로 쪼개져 어느 노드에 있는지" 는 전부
#   마스터 한 대의 /data/hdfs/name 안에 있다. 크기는 수십 MB 밖에 안 되는데,
#   이게 날아가면 워커 5대에 흩어진 수십 GB 가 통째로 못 읽는 조각이 된다.
#   워커의 DataNode 는 블록 파일만 갖고 있을 뿐 그게 무슨 파일인지 모른다.
#   노트북은 매일 켜고 끄는 장비라 디스크 사고 확률이 서버보다 높다.
#
# 무엇을 담는가
#   fsimage   NameNode 가 들고 있는 네임스페이스 스냅샷 (dfsadmin -fetchImage)
#   VERSION   clusterID · namespaceID. 이게 없으면 DataNode 가 붙지 않는다
#   설정      core-site · hdfs-site · yarn-site · workers
#   목록      hdfs dfs -ls -R / 결과. 복구 후 대조용
#
# 한계 (알고 쓰자)
#   fetchImage 는 "마지막 체크포인트 시점" 의 이미지를 가져온다. 그 이후의
#   변경은 edits 로그에만 있고 이 백업에는 없다. SecondaryNameNode 가
#   기본 1시간마다 체크포인트하므로 최악의 경우 1시간치 네임스페이스 변경이
#   빠진다. 우리는 하루 한 번 배치로 쓰기 때문에 이 정도면 충분하다.
#   완벽히 맞추려면 NameNode 를 멈춰야 하는데, 그건 하지 않는다.
set -uo pipefail

export HADOOP_HOME=${HADOOP_HOME:-/opt/hadoop}
export HADOOP_CONF_DIR=${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}
export PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)
  export JAVA_HOME
fi

# ── 백업 대상 서버 ────────────────────────────────────────────
# 환경변수로 덮어쓸 수 있다.  BACKUP_HOST=... ./10-namenode-backup.sh now
BACKUP_HOST=${BACKUP_HOST:-j15a202a.p.ssafy.io}
BACKUP_USER=${BACKUP_USER:-ubuntu}
BACKUP_KEY=${BACKUP_KEY:-$HOME/.ssh/J15A202T.pem}
BACKUP_DIR=${BACKUP_DIR:-/home/ubuntu/dispatch-backup/namenode}

# 로컬 보관처. 원격이 안 닿아도 여기에는 남는다.
LOCAL_DIR=${LOCAL_DIR:-/data/backup/namenode}
KEEP=${KEEP:-24}          # 로컬·원격 각각 최근 몇 개를 남길지

NAME_DIR=${NAME_DIR:-/data/hdfs/name}
SSH_OPTS="-o ConnectTimeout=10 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"

CMD=${1:-now}
banner() { echo; echo "══ $* ══════════════════════════════════"; }

remote_ready() {
  [ -f "$BACKUP_KEY" ] || return 1
  ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" true 2>/dev/null
}

case "$CMD" in

now)
  banner "NameNode 메타데이터 백업"

  if ! jps 2>/dev/null | grep -q NameNode; then
    echo "  NameNode 가 떠 있지 않습니다. fetchImage 는 살아있는 NameNode 에서 받아옵니다."
    echo "  sudo systemctl start hadoop-namenode  후 다시 실행하세요."
    exit 1
  fi

  TS=$(date +%Y%m%d-%H%M)
  WORK=$(mktemp -d /tmp/nnbak.XXXXXX)
  trap 'rm -rf "$WORK"' EXIT
  STAGE="$WORK/namenode-$TS"
  mkdir -p "$STAGE"

  # 1) fsimage
  if ! hdfs dfsadmin -fetchImage "$STAGE" >/dev/null 2>&1; then
    echo "  fetchImage 실패. NameNode 웹 주소(9870)에 닿는지 확인하세요."
    exit 1
  fi
  IMG=$(ls "$STAGE" | head -1)
  echo "  fsimage        $IMG  ($(du -h "$STAGE/$IMG" | cut -f1))"

  # NameNode 는 기동할 때 fsimage 를 같은 이름의 .md5 와 대조한다.
  # 없으면 "No MD5 file found corresponding to image file" 로 로드를 거부한다.
  # fetchImage 는 .md5 를 만들어주지 않으므로 여기서 만든다.
  # 형식은 하둡이 쓰는 것과 같아야 한다:  <md5> *<파일이름>
  # 2026-09-11 복구 리허설에서 빠진 것을 발견했다.
  printf "%s *%s\n" "$(md5sum "$STAGE/$IMG" | cut -d' ' -f1)" "$IMG" > "$STAGE/$IMG.md5"
  echo "  md5            $(cut -d' ' -f1 < "$STAGE/$IMG.md5" | cut -c1-16)…"

  # 2) VERSION — clusterID 가 여기 있다. 없으면 DataNode 가 안 붙는다.
  if [ -f "$NAME_DIR/current/VERSION" ]; then
    cp "$NAME_DIR/current/VERSION" "$STAGE/VERSION"
    echo "  VERSION        $(grep clusterID "$STAGE/VERSION")"
  else
    echo "  [주의] $NAME_DIR/current/VERSION 이 없습니다"
  fi

  # 3) 설정
  mkdir -p "$STAGE/conf"
  for f in core-site.xml hdfs-site.xml yarn-site.xml mapred-site.xml workers; do
    [ -f "$HADOOP_CONF_DIR/$f" ] && cp "$HADOOP_CONF_DIR/$f" "$STAGE/conf/"
  done
  echo "  설정           $(ls "$STAGE/conf" | tr '\n' ' ')"

  # 4) 파일 목록 + 용량 요약. 복구 후 무엇이 빠졌는지 대조하는 용도.
  hdfs dfs -ls -R / > "$STAGE/hdfs-ls.txt" 2>/dev/null
  hdfs dfsadmin -report > "$STAGE/hdfs-report.txt" 2>/dev/null
  echo "  파일 목록      $(wc -l < "$STAGE/hdfs-ls.txt") 줄"

  # 5) 묶기
  mkdir -p "$LOCAL_DIR"
  TAR="$LOCAL_DIR/namenode-$TS.tar.gz"
  tar -czf "$TAR" -C "$WORK" "namenode-$TS"
  SUM=$(sha256sum "$TAR" | cut -d' ' -f1)
  echo "  묶음           $(basename "$TAR")  $(du -h "$TAR" | cut -f1)"
  echo "  sha256         ${SUM:0:16}…"

  # 6) 원격으로 보내기
  if remote_ready; then
    ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" "mkdir -p $BACKUP_DIR" 2>/dev/null
    if scp -q -i "$BACKUP_KEY" $SSH_OPTS "$TAR" "$BACKUP_USER@$BACKUP_HOST:$BACKUP_DIR/" 2>/dev/null; then
      RSUM=$(ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" \
             "sha256sum $BACKUP_DIR/$(basename "$TAR") | cut -d' ' -f1" 2>/dev/null)
      if [ "$RSUM" = "$SUM" ]; then
        echo "  원격 전송      $BACKUP_HOST:$BACKUP_DIR  체크섬 일치"
      else
        echo "  [실패] 원격 체크섬 불일치. 전송이 깨졌습니다."
        exit 1
      fi
      # 원격 정리
      ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" \
        "ls -1t $BACKUP_DIR/namenode-*.tar.gz 2>/dev/null | tail -n +$((KEEP+1)) | xargs -r rm -f" 2>/dev/null
    else
      echo "  [경고] scp 실패. 로컬 사본만 남습니다."
    fi
  else
    echo "  [경고] $BACKUP_HOST 에 닿지 않습니다. 로컬 사본만 남습니다."
    echo "         원격 백업 없이는 이 노트북이 죽으면 같이 죽습니다."
  fi

  # 7) 로컬 정리
  ls -1t "$LOCAL_DIR"/namenode-*.tar.gz 2>/dev/null | tail -n +$((KEEP+1)) | xargs -r rm -f
  echo
  echo "  완료."
  ;;

list)
  banner "로컬  $LOCAL_DIR"
  ls -lht "$LOCAL_DIR"/namenode-*.tar.gz 2>/dev/null | head -"$KEEP" || echo "  (없음)"
  banner "원격  $BACKUP_HOST:$BACKUP_DIR"
  if remote_ready; then
    ssh -n -i "$BACKUP_KEY" $SSH_OPTS "$BACKUP_USER@$BACKUP_HOST" \
      "ls -lht $BACKUP_DIR/namenode-*.tar.gz 2>/dev/null | head -$KEEP" || echo "  (없음)"
  else
    echo "  (닿지 않음)"
  fi
  banner "타이머"
  systemctl status dispatch-nnbackup.timer --no-pager 2>/dev/null | sed -n '1,6p' || echo "  (등록 안 됨)"
  ;;

install)
  if [ "$(id -u)" = 0 ]; then echo "sudo 없이 그냥 실행하세요. 필요할 때만 sudo 를 씁니다." >&2; exit 1; fi
  banner "systemd 타이머 등록"
  SELF=$(readlink -f "$0")
  sudo tee /etc/systemd/system/dispatch-nnbackup.service >/dev/null <<UNIT
[Unit]
Description=디스패치 NameNode 메타데이터 백업
After=hadoop-namenode.service
Requires=hadoop-namenode.service

[Service]
Type=oneshot
User=$USER
Environment=HADOOP_HOME=$HADOOP_HOME
Environment=HADOOP_CONF_DIR=$HADOOP_CONF_DIR
Environment=JAVA_HOME=$JAVA_HOME
Environment=BACKUP_HOST=$BACKUP_HOST
Environment=BACKUP_USER=$BACKUP_USER
Environment=BACKUP_KEY=$BACKUP_KEY
Environment=BACKUP_DIR=$BACKUP_DIR
Environment=LOCAL_DIR=$LOCAL_DIR
Environment=KEEP=$KEEP
ExecStart=/usr/bin/env bash $SELF now
UNIT

  sudo tee /etc/systemd/system/dispatch-nnbackup.timer >/dev/null <<'UNIT'
[Unit]
Description=디스패치 NameNode 백업 — 매시 정각

[Timer]
OnCalendar=hourly
# WSL 이 꺼져 있어 걸렀으면 켜지자마자 한 번 따라잡는다
Persistent=true
RandomizedDelaySec=120

[Install]
WantedBy=timers.target
UNIT

  sudo mkdir -p "$LOCAL_DIR"
  sudo chown "$USER:$USER" "$LOCAL_DIR"
  sudo systemctl daemon-reload
  sudo systemctl enable --now dispatch-nnbackup.timer
  echo "  등록 완료. 다음 실행:"
  systemctl list-timers dispatch-nnbackup.timer --no-pager | sed -n '1,3p'
  echo
  echo "  지금 한 번 돌려보려면:  sudo systemctl start dispatch-nnbackup.service"
  echo "  로그:                   journalctl -u dispatch-nnbackup -n 40"
  ;;

remove)
  banner "타이머 해제"
  sudo systemctl disable --now dispatch-nnbackup.timer 2>/dev/null
  sudo rm -f /etc/systemd/system/dispatch-nnbackup.{service,timer}
  sudo systemctl daemon-reload
  echo "  해제했습니다. 백업 파일은 지우지 않았습니다."
  ;;

restore)
  banner "복구 절차 — 읽고 손으로 하세요"
  cat <<'GUIDE'
  마스터 노트북을 잃었거나 /data/hdfs/name 이 깨졌을 때의 절차다.
  자동화하지 않는다. 잘못 돌리면 멀쩡한 것을 덮어쓴다.

  1) 백업 가져오기
       scp -i ~/.ssh/J15A202T.pem \
         ubuntu@j15a202a.p.ssafy.io:/home/ubuntu/dispatch-backup/namenode/namenode-<시각>.tar.gz .
       tar -xzf namenode-<시각>.tar.gz

  2) NameNode 정지
       sudo systemctl stop dispatch-cluster.target

  3) 새 마스터에 하둡을 설치하고 (04-wsl-node.sh ROLE=master) 포맷은 하지 않는다.
     포맷하면 clusterID 가 새로 생겨서 기존 DataNode 가 전부 거부된다.

  4) 디렉터리 복원
       sudo mkdir -p /data/hdfs/name/current
       sudo cp namenode-<시각>/fsimage_*        /data/hdfs/name/current/
       # fsimage_*.md5 도 같이 들어 있다. 이게 없으면 NameNode 가 기동을 거부한다.
       sudo cp namenode-<시각>/VERSION          /data/hdfs/name/current/
       sudo chown -R $USER:$USER /data/hdfs/name

  5) fsimage 에 맞는 seen_txid 를 적는다. 파일명 끝 숫자가 트랜잭션 ID 다.
       ls /data/hdfs/name/current/fsimage_*
       # fsimage_0000000000000012345 이면
       echo 12345 | sudo tee /data/hdfs/name/current/seen_txid

  6) 기동. Safe mode 에서 DataNode 들이 블록을 보고할 때까지 기다린다.
       sudo systemctl start dispatch-cluster.target
       hdfs dfsadmin -safemode wait

  7) 대조. 백업의 목록과 지금 목록을 비교한다.
       hdfs dfs -ls -R / > now.txt
       diff namenode-<시각>/hdfs-ls.txt now.txt
       hdfs fsck / | tail -30

     마지막 체크포인트 이후에 쓴 파일은 없을 수 있다. 그건 다시 넣으면 된다.
     fsck 가 MISSING 을 말하면 그 블록을 가진 DataNode 가 아직 안 올라온 것이다.
GUIDE
  ;;

*)
  sed -n '2,8p' "$0"
  exit 1
  ;;
esac
