#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
#  디스패치 노트북 클러스터 — 노드 설치 (WSL2 Ubuntu 안에서 실행)
#
#  팀원 전원이 이 스크립트 하나만 돌리면 됩니다.
#    master 노트북:  ROLE=master bash 03-node-setup.sh
#    worker 노트북:  ROLE=worker bash 03-node-setup.sh
#
#  사전 조건 (Windows 쪽에서 04-windows-prep.ps1 이 처리합니다)
#    · WSL2 + Ubuntu
#    · %USERPROFILE%\.wslconfig 에 networkingMode=mirrored
#    · 방화벽 인바운드 허용
# ═══════════════════════════════════════════════════════════════════
set -euo pipefail

HADOOP_VER=3.4.3
SPARK_VER=3.5.9
ROLE="${ROLE:-worker}"
REG_HOST="${REG_HOST:-j15a202a.p.ssafy.io}"   # master IP 레지스트리 (서버2A)
MIRROR=https://dlcdn.apache.org

MY_IP=$(ip -4 addr show scope global | grep -oP 'inet \K[0-9.]+' | grep -E '^70\.' | head -1)
[ -z "$MY_IP" ] && { echo "✗ SSAFY 랜 IP(70.x)를 못 찾았습니다. 미러 네트워킹이 켜져 있습니까?" >&2; exit 1; }

echo "═══════════════════════════════════════════════════════"
echo "  역할 : $ROLE"
echo "  IP   : $MY_IP"
echo "  호스트: $(hostname)"
echo "═══════════════════════════════════════════════════════"
echo

echo "── [1/7] 패키지 ───────────────────────────────────────"
sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
     openjdk-17-jdk-headless openssh-server rsync curl python3 python3-pip \
     postgresql-client >/dev/null
JAVA_HOME_PATH=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
echo "  java  $(java -version 2>&1 | head -1 | cut -d'"' -f2)"

echo "── [2/7] SSH (하둡이 노드를 띄울 때 씁니다) ────────────"
sudo sed -i 's/^#*Port .*/Port 22/' /etc/ssh/sshd_config
sudo service ssh restart >/dev/null 2>&1 || sudo systemctl restart ssh
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -q -t ed25519 -N "" -f ~/.ssh/id_ed25519
grep -qf ~/.ssh/id_ed25519.pub ~/.ssh/authorized_keys 2>/dev/null || \
  cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys
chmod 700 ~/.ssh; chmod 600 ~/.ssh/authorized_keys
echo "  공개키 (master 에 등록해야 합니다):"
echo "    $(cat ~/.ssh/id_ed25519.pub)"

echo "── [3/7] Hadoop $HADOOP_VER ───────────────────────────"
if [ ! -d /opt/hadoop ]; then
  cd /tmp
  curl -fL# -o hadoop.tgz "$MIRROR/hadoop/common/hadoop-$HADOOP_VER/hadoop-$HADOOP_VER.tar.gz"
  sudo tar -xzf hadoop.tgz -C /opt
  sudo ln -sfn /opt/hadoop-$HADOOP_VER /opt/hadoop
  sudo chown -R "$USER":"$USER" /opt/hadoop-$HADOOP_VER
  rm -f hadoop.tgz
fi
echo "  $(/opt/hadoop/bin/hadoop version 2>/dev/null | head -1)"

echo "── [4/7] Spark $SPARK_VER ─────────────────────────────"
if [ ! -d /opt/spark ]; then
  cd /tmp
  curl -fL# -o spark.tgz "$MIRROR/spark/spark-$SPARK_VER/spark-$SPARK_VER-bin-hadoop3.tgz"
  sudo tar -xzf spark.tgz -C /opt
  sudo ln -sfn /opt/spark-$SPARK_VER-bin-hadoop3 /opt/spark
  sudo chown -R "$USER":"$USER" /opt/spark-$SPARK_VER-bin-hadoop3
  rm -f spark.tgz
fi
echo "  spark $SPARK_VER"

echo "── [5/7] 디렉터리 · 환경변수 ──────────────────────────"
sudo mkdir -p /data/hdfs/{name,data} /data/logs /data/yarn
sudo chown -R "$USER":"$USER" /data
sed -i '/# === dispatch ===/,/# === dispatch end ===/d' ~/.bashrc
cat >> ~/.bashrc <<EOF
# === dispatch ===
export JAVA_HOME=$JAVA_HOME_PATH
export HADOOP_HOME=/opt/hadoop
export SPARK_HOME=/opt/spark
export HADOOP_CONF_DIR=\$HADOOP_HOME/etc/hadoop
export HADOOP_LOG_DIR=/data/logs
export PATH=\$PATH:\$HADOOP_HOME/bin:\$HADOOP_HOME/sbin:\$SPARK_HOME/bin:\$SPARK_HOME/sbin
export DISPATCH_ROLE=$ROLE
export DISPATCH_REG=$REG_HOST
# === dispatch end ===
EOF
export JAVA_HOME=$JAVA_HOME_PATH HADOOP_HOME=/opt/hadoop SPARK_HOME=/opt/spark
export HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop

echo "── [6/7] master 주소 확인 ─────────────────────────────"
# 노트북 IP 는 DHCP 로 바뀌므로 master 가 자기 IP 를 서버에 올려두고
# worker 는 서버에서 읽어온다. 노트북 → 서버는 나가는 방향이라 항상 된다.
if [ "$ROLE" = "master" ]; then
  MASTER_IP=$MY_IP
  echo "  내가 master → 레지스트리에 등록"
  curl -fsS --max-time 10 -X POST "http://$REG_HOST:8080/master?ip=$MY_IP" \
    2>/dev/null && echo "    등록 완료" || \
    echo "    ⚠ 레지스트리 없음 — 수동으로 알려야 합니다 ($MY_IP)"
else
  MASTER_IP=$(curl -fsS --max-time 10 "http://$REG_HOST:8080/master" 2>/dev/null || true)
  if [ -z "$MASTER_IP" ]; then
    echo "  ⚠ 레지스트리에서 master 를 못 찾았습니다."
    echo "    MASTER_IP 환경변수로 직접 주세요:  MASTER_IP=70.12.x.x ROLE=worker bash $0"
    MASTER_IP="${MASTER_IP_OVERRIDE:-}"
    [ -z "$MASTER_IP" ] && exit 1
  fi
  echo "  master = $MASTER_IP"
fi

echo "── [7/7] 하둡 설정 파일 ───────────────────────────────"
C=$HADOOP_CONF_DIR

cat > $C/core-site.xml <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>fs.defaultFS</name><value>hdfs://$MASTER_IP:9000</value></property>
  <property><name>hadoop.tmp.dir</name><value>/data/hdfs/tmp</value></property>
  <!-- 노트북 IP 가 바뀌므로 호스트명 대신 IP 를 쓴다 -->
  <property><name>dfs.client.use.datanode.hostname</name><value>false</value></property>
</configuration>
EOF

# 노트북 5~6대 · 2대까지 결석 허용 → replication 3
cat > $C/hdfs-site.xml <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>dfs.replication</name><value>3</value></property>
  <property><name>dfs.namenode.name.dir</name><value>file:///data/hdfs/name</value></property>
  <property><name>dfs.datanode.data.dir</name><value>file:///data/hdfs/data</value></property>
  <property><name>dfs.datanode.address</name><value>0.0.0.0:9866</value></property>
  <property><name>dfs.datanode.http.address</name><value>0.0.0.0:9864</value></property>
  <property><name>dfs.datanode.ipc.address</name><value>0.0.0.0:9867</value></property>
  <property><name>dfs.namenode.http-address</name><value>0.0.0.0:9870</value></property>
  <!-- 노드가 빠졌을 때 빨리 알아채도록 (기본 10분 30초 → 2분) -->
  <property><name>dfs.namenode.heartbeat.recheck-interval</name><value>45000</value></property>
  <property><name>dfs.blocksize</name><value>134217728</value></property>
  <property><name>dfs.permissions.enabled</name><value>false</value></property>
</configuration>
EOF

# 노트북 1대당 16코어 63.5GB. 개발도 해야 하므로 절반만 준다.
cat > $C/yarn-site.xml <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>yarn.resourcemanager.hostname</name><value>$MASTER_IP</value></property>
  <property><name>yarn.nodemanager.aux-services</name><value>mapreduce_shuffle</value></property>
  <property><name>yarn.nodemanager.resource.memory-mb</name><value>24576</value></property>
  <property><name>yarn.nodemanager.resource.cpu-vcores</name><value>8</value></property>
  <property><name>yarn.scheduler.maximum-allocation-mb</name><value>16384</value></property>
  <property><name>yarn.scheduler.minimum-allocation-mb</name><value>1024</value></property>
  <property><name>yarn.scheduler.maximum-allocation-vcores</name><value>8</value></property>
  <!-- WSL 에서 가상메모리 검사가 오탐을 냅니다 -->
  <property><name>yarn.nodemanager.vmem-check-enabled</name><value>false</value></property>
  <property><name>yarn.nodemanager.pmem-check-enabled</name><value>false</value></property>
  <property><name>yarn.nodemanager.local-dirs</name><value>/data/yarn/local</value></property>
  <property><name>yarn.nodemanager.log-dirs</name><value>/data/yarn/log</value></property>
  <property><name>yarn.log-aggregation-enable</name><value>true</value></property>
</configuration>
EOF

cat > $C/mapred-site.xml <<'EOF'
<?xml version="1.0"?>
<configuration>
  <property><name>mapreduce.framework.name</name><value>yarn</value></property>
  <property><name>yarn.app.mapreduce.am.env</name><value>HADOOP_MAPRED_HOME=/opt/hadoop</value></property>
  <property><name>mapreduce.map.env</name><value>HADOOP_MAPRED_HOME=/opt/hadoop</value></property>
  <property><name>mapreduce.reduce.env</name><value>HADOOP_MAPRED_HOME=/opt/hadoop</value></property>
</configuration>
EOF

sed -i "s|^# export JAVA_HOME=.*|export JAVA_HOME=$JAVA_HOME_PATH|" $C/hadoop-env.sh
grep -q "^export JAVA_HOME=" $C/hadoop-env.sh || \
  echo "export JAVA_HOME=$JAVA_HOME_PATH" >> $C/hadoop-env.sh
echo "export HADOOP_LOG_DIR=/data/logs" >> $C/hadoop-env.sh

# Spark 는 YARN 위에서 돌린다
cat > $SPARK_HOME/conf/spark-defaults.conf <<EOF
spark.master                       yarn
spark.submit.deployMode            client
spark.driver.memory                2g
spark.executor.memory              6g
spark.executor.cores               3
spark.executor.instances           4
spark.dynamicAllocation.enabled    false
spark.sql.shuffle.partitions       24
spark.sql.parquet.compression.codec zstd
spark.hadoop.fs.defaultFS          hdfs://$MASTER_IP:9000
spark.eventLog.enabled             true
spark.eventLog.dir                 hdfs://$MASTER_IP:9000/spark-logs
EOF
cat > $SPARK_HOME/conf/spark-env.sh <<EOF
export JAVA_HOME=$JAVA_HOME_PATH
export HADOOP_CONF_DIR=$HADOOP_CONF_DIR
export SPARK_LOCAL_IP=$MY_IP
EOF
chmod +x $SPARK_HOME/conf/spark-env.sh

echo
echo "═══════════════════════════════════════════════════════"
echo "✔ 설치 완료 — $ROLE ($MY_IP)"
echo "═══════════════════════════════════════════════════════"
if [ "$ROLE" = "master" ]; then
cat <<EOF

다음 단계 (master 에서만):

  1. worker 들의 공개키를 ~/.ssh/authorized_keys 에 추가
  2. worker IP 를 \$HADOOP_HOME/etc/hadoop/workers 에 한 줄씩
  3. NameNode 포맷 (최초 1회만!)
       hdfs namenode -format -force
  4. 시작
       start-dfs.sh && start-yarn.sh
       hdfs dfs -mkdir -p /spark-logs /review_raw /review_topic
  5. 확인
       hdfs dfsadmin -report | head -20
       http://$MY_IP:9870   (HDFS)
       http://$MY_IP:8088   (YARN)
EOF
else
cat <<EOF

이 노드는 준비됐습니다. master 쪽에서 아래를 해주면 클러스터에 붙습니다:

  1. 위에 출력된 공개키를 master 의 ~/.ssh/authorized_keys 에 추가
  2. master 의 \$HADOOP_HOME/etc/hadoop/workers 에 $MY_IP 추가
  3. master 에서  start-dfs.sh && start-yarn.sh  재실행
EOF
fi
