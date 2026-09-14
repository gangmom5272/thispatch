#!/usr/bin/env bash
# Hadoop·YARN 데몬을 systemd 서비스로 등록
#
#   마스터:  sudo ROLE=master bash 08-systemd-hadoop.sh
#   워커:    sudo ROLE=worker bash 08-systemd-hadoop.sh
#
# 왜 필요한가
#   start-dfs.sh / start-yarn.sh 로 띄우면 그 셸 세션이 끝날 때
#   데몬이 SIGHUP 을 받고 죽는다 (ResourceManager 에서 실측).
#   또 WSL 은 마지막 세션이 닫히면 배포판을 내려버린다.
#   매일 오전 9시 배치가 돌아야 하므로 부팅 시 자동 기동이 필요하다.
#
# systemd 로 올리면
#   · 세션 종료와 무관하게 유지
#   · WSL 기동 시 자동 시작 (wsl.conf 에 systemd=true 필요 — 이미 켜져 있음)
#   · Wi-Fi 가 끊겨 죽어도 자동 재시작
set -euo pipefail

ROLE="${ROLE:-worker}"
RUN_USER="${RUN_USER:-${SUDO_USER:-$USER}}"
HADOOP_HOME="${HADOOP_HOME:-/opt/hadoop}"
JAVA_HOME_PATH="${JAVA_HOME:-$(ls -d /usr/lib/jvm/java-17-openjdk-* | head -1)}"

if [ "$ROLE" = client ]; then
  echo "client 노드에는 데몬을 띄우지 않습니다." >&2
  echo "AI 노트북은 HDFS 클라이언트로만 쓰며, DataNode/NodeManager 를 띄우면" >&2
  echo "GPU 작업 중에 YARN 컨테이너가 날아와 방해합니다." >&2
  exit 1
fi

[ "$(id -u)" = 0 ] || { echo "sudo 로 실행하세요." >&2; exit 1; }
if [ "$ROLE" != master ] && [ "$ROLE" != worker ]; then
  echo "ROLE=master 또는 ROLE=worker" >&2; exit 1
fi

echo "── systemd 유닛 생성 ──────────────────────────────"
echo "  역할       $ROLE"
echo "  실행 사용자 $RUN_USER"
echo "  JAVA_HOME  $JAVA_HOME_PATH"
echo

# $1=유닛명  $2=설명  $3=실행 커맨드  $4=선행 유닛(비어도 됨)
mkunit() {
  local name=$1 desc=$2 cmd=$3 after=${4:-}
  local extra=""
  [ -n "$after" ] && extra="After=$after
Requires=$after"

  cat > /etc/systemd/system/"$name".service <<UEOF
[Unit]
Description=$desc
After=network-online.target thispatch-route.service
Wants=network-online.target
$extra

[Service]
Type=simple
User=$RUN_USER
Group=$RUN_USER
Environment=JAVA_HOME=$JAVA_HOME_PATH
Environment=HADOOP_HOME=$HADOOP_HOME
Environment=HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop
Environment=HADOOP_LOG_DIR=/data/logs/hadoop
# hadoop-env.sh 가 add-opens(HADOOP_OPTS)를 넣어 주므로 여기서 다시 안 넣는다.
ExecStart=$cmd
# Wi-Fi 가 끊겨 죽는 경우가 있어 자동 재시작. 단, 설정 오류로
# 무한 재시작 루프에 빠지지 않게 5분에 5회로 제한한다.
Restart=on-failure
RestartSec=20
StartLimitBurst=5
StartLimitIntervalSec=300
# HDFS·Spark 는 파일 디스크립터를 많이 쓴다
LimitNOFILE=65536
LimitNPROC=32768
SuccessExitStatus=143
TimeoutStopSec=60
KillMode=mixed

[Install]
WantedBy=multi-user.target
UEOF
  echo "  $name.service"
}

H=$HADOOP_HOME/bin

if [ "$ROLE" = master ]; then
  mkunit hadoop-namenode          "HDFS NameNode"          "$H/hdfs namenode"
  mkunit hadoop-secondarynamenode "HDFS SecondaryNameNode" "$H/hdfs secondarynamenode" hadoop-namenode.service
  mkunit hadoop-datanode          "HDFS DataNode"          "$H/hdfs datanode"
  mkunit yarn-resourcemanager     "YARN ResourceManager"   "$H/yarn resourcemanager"
  mkunit yarn-nodemanager         "YARN NodeManager"       "$H/yarn nodemanager"
  UNITS="hadoop-namenode hadoop-secondarynamenode hadoop-datanode yarn-resourcemanager yarn-nodemanager"
else
  mkunit hadoop-datanode  "HDFS DataNode"    "$H/hdfs datanode"
  mkunit yarn-nodemanager "YARN NodeManager" "$H/yarn nodemanager"
  UNITS="hadoop-datanode yarn-nodemanager"
fi

# 묶어서 켜고 끌 수 있게 타깃을 하나 만든다
cat > /etc/systemd/system/thispatch-cluster.target <<TEOF
[Unit]
Description=디스패치 클러스터 ($ROLE)
Wants=$(for u in $UNITS; do printf "%s.service " "$u"; done)

[Install]
WantedBy=multi-user.target
TEOF
echo "  thispatch-cluster.target"

echo
echo "── 기존 수동 기동 데몬 정지 ───────────────────────"
# start-dfs.sh 로 띄운 게 남아 있으면 포트가 겹친다
# stop-dfs.sh 는 워커에서 돌리면 안 된다.
#   내부적으로 hdfs getconf -namenodes 로 thispatch-master 를 찾아낸 뒤
#   거기로 SSH 해서 마스터의 NameNode 를 정지시킨다.
#   워커가 자기 데몬을 정리하려다 클러스터 전체를 내리는 셈이다.
#   지금은 워커 키가 마스터 authorized_keys 에 없어서 SSH 가 실패해 우연히
#   막히지만, 양방향 키를 깔면 그대로 터진다.
if [ "$ROLE" = master ]; then
  sudo -u "$RUN_USER" env HADOOP_HOME="$HADOOP_HOME" JAVA_HOME="$JAVA_HOME_PATH" \
    bash -c "$HADOOP_HOME/sbin/stop-yarn.sh; $HADOOP_HOME/sbin/stop-dfs.sh" >/dev/null 2>&1 || true
fi
pkill -u "$RUN_USER" -f "org.apache.hadoop" 2>/dev/null || true
sleep 3
echo "  정리 완료 ($ROLE)"

echo
echo "── 등록 · 기동 ────────────────────────────────────"
systemctl daemon-reload
systemctl enable thispatch-cluster.target >/dev/null
for u in $UNITS; do systemctl enable "$u" >/dev/null; done

# NameNode 가 먼저 떠야 DataNode 등록이 깔끔하다
if [ "$ROLE" = master ]; then
  systemctl restart hadoop-namenode
  sleep 8
fi
for u in $UNITS; do
  [ "$u" = hadoop-namenode ] && continue
  systemctl restart "$u"
done
sleep 12

echo
echo "── 상태 ──────────────────────────────────────────"
for u in $UNITS; do
  printf "  %-28s %s\n" "$u" "$(systemctl is-active "$u")"
done
echo
echo "다루는 방법"
echo "  전체 정지    sudo systemctl stop  thispatch-cluster.target"
echo "  전체 기동    sudo systemctl start thispatch-cluster.target"
echo "  로그 보기    journalctl -u yarn-resourcemanager -f"
