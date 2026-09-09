#!/usr/bin/env bash
# 디스패치 클러스터 — WSL2 Ubuntu 노드 구성
#
#   마스터(노트북1):  ROLE=master ./04-wsl-node.sh
#   워커(노트북2~5):  ROLE=worker ./04-wsl-node.sh
#
# 전제
#   · WSL2 미러링 모드 (%UserProfile%\.wslconfig 에 networkingMode=mirrored)
#     → WSL 이 호스트 랜 IP 를 그대로 쓴다. 포트 포워딩이 필요 없다.
#   · sudo 비밀번호 없음  (/etc/sudoers.d/99-<사용자명>)
#   · Hadoop/Spark 타르볼이 ~/dl 에 있음
set -euo pipefail

ROLE="${ROLE:-worker}"
MASTER_IP="${MASTER_IP:-70.12.108.81}"     # 노트북1 (유선)
HADOOP_VER=3.5.0
SPARK_VER=4.2.0
# Hadoop 3.5.0 은 자바 17 바이트코드로 컴파일돼 있다 (major=61 실측).
# 자바 17 이 네이티브라 --add-opens 우회가 필요 없다.
# Spark 4.2.0 도 자바 17 을 요구하고 hadoop-client 3.5.0 을 품고 있어
# 클러스터 버전과 정확히 일치한다.
JAVA_VER="${JAVA_VER:-17}"

# 노드당 YARN 할당. WSL2 는 호스트 메모리의 약 절반(31GB)만 본다.
#
# 마스터는 컨테이너 외에 NameNode 2G · ResourceManager 1.5G · DataNode 1G ·
# NodeManager 1G 를 쓰고, client 모드 Spark 드라이버 4G 도 YARN 밖에서 뜬다.
# 합계 9.5G 를 빼야 하므로 컨테이너 몫을 10G 로 낮춘다.
# 워커는 DataNode·NodeManager 2G 만 빠지므로 16G 를 준다.
#   클러스터 합계 = 10 + 16x4 = 74GB · 30 vcore
if [ "${ROLE:-worker}" = master ]; then
  NM_MEM_MB="${NM_MEM_MB:-10240}"
else
  NM_MEM_MB="${NM_MEM_MB:-16384}"
fi
NM_VCORES="${NM_VCORES:-6}"

DL=~/dl
OPT=/opt
DATA=/data

if [ "$ROLE" != master ] && [ "$ROLE" != worker ]; then
  echo "ROLE=master 또는 ROLE=worker 로 지정하세요." >&2; exit 1
fi

MYIP=$(ip -4 -o addr show scope global | grep -v " lo " | head -1 | tr -s " " | cut -d" " -f4)

echo "══ 디스패치 노드 구성 ══════════════════════════════"
echo "  역할        $ROLE"
echo "  마스터      $MASTER_IP"
echo "  이 노드     $(hostname)  $MYIP"
echo "  YARN        ${NM_MEM_MB}MB · ${NM_VCORES} vcore"
echo

# ── [1/8] 기본 경로 점검 ────────────────────────────────
# SSAFY 랜에서 죽은 기본 게이트웨이(192.168.0.1)가 미러링 모드로
# 넘어오는 경우가 있다. 밖으로 못 나가면 여기서 잡는다.
echo "── [1/8] 네트워크 ─────────────────────────────────"
GW=$(ip route | grep "^default" | head -1 | tr -s " " | cut -d" " -f3)
IFACE=$(ip route | grep "^default" | head -1 | tr -s " " | cut -d" " -f5)
if ! ping -c1 -W2 -n "$GW" >/dev/null 2>&1; then
  # 같은 인터페이스에 붙은 다른 링크 로컬 게이트웨이를 찾는다
  REAL_GW=$(ip route | grep "scope link" | grep "\.1 " | tr -s " " | cut -d" " -f1 | while read -r c; do
              ping -c1 -W1 -n "$c" >/dev/null 2>&1 && echo "$c" && break; done)
  if [ -n "$REAL_GW" ]; then
    echo "  기본 게이트웨이 $GW 응답 없음 → $REAL_GW 로 교체"
    sudo ip route replace default via "$REAL_GW" dev "$IFACE" metric 35
    GW=$REAL_GW
  else
    echo "  ⚠ 살아있는 게이트웨이를 못 찾았습니다. 수동 확인 필요."
  fi
fi
if ping -c1 -W2 -n "$GW" >/dev/null 2>&1; then
  echo "  게이트웨이 $GW 정상"
else
  echo "  게이트웨이 $GW 실패"
fi
curl -s -o /dev/null -w "  인터넷 %{http_code}\n" --max-time 15 https://dlcdn.apache.org/ || echo "  인터넷 실패"

# ── [2/8] 패키지 ───────────────────────────────────────
echo "── [2/8] 패키지 ───────────────────────────────────"
export DEBIAN_FRONTEND=noninteractive
for i in $(seq 1 120); do
  sudo fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || break
  [ "$i" = 1 ] && echo "  dpkg 락 대기중 (unattended-upgrades)"
  sleep 5
done
sudo apt-get update -qq
sudo apt-get install -y -qq \
  "openjdk-${JAVA_VER}-jdk-headless" openssh-server pdsh rsync curl net-tools python3 >/dev/null
JAVA_HOME_PATH=$(ls -d /usr/lib/jvm/java-${JAVA_VER}-openjdk-* | head -1)
echo "  $("$JAVA_HOME_PATH"/bin/java -version 2>&1 | head -1)"
sudo systemctl enable --now ssh >/dev/null 2>&1 || sudo service ssh start >/dev/null 2>&1 || true
echo "  sshd $(systemctl is-active ssh 2>/dev/null || echo unknown)"

# ── [3/8] /etc/hosts · 디렉터리 ────────────────────────
echo "── [3/8] 호스트 · 디렉터리 ────────────────────────"
# WSL 은 기본적으로 부팅할 때마다 /etc/hosts 를 새로 만든다.
# 그러면 dispatch-master 항목이 매번 사라지고 HDFS 가 이름 해석에 실패한다.
# (실측: WSL 재시작 후 fs.defaultFS 의 dispatch-master 가 UnknownHostException)
if ! grep -q "generateHosts" /etc/wsl.conf 2>/dev/null; then
  printf "
[network]
generateHosts = false
generateResolvConf = true
" | sudo tee -a /etc/wsl.conf >/dev/null
  echo "  /etc/wsl.conf 에 generateHosts=false 추가 (다음 wsl 재시작부터 적용)"
fi
sudo sed -i "/dispatch-master/d" /etc/hosts
echo "$MASTER_IP dispatch-master" | sudo tee -a /etc/hosts >/dev/null
grep dispatch /etc/hosts | sed "s/^/  /"

sudo mkdir -p "$DATA"/hdfs/name "$DATA"/hdfs/data \
             "$DATA"/hadoop/tmp "$DATA"/hadoop/nm-local "$DATA"/hadoop/nm-log \
             "$DATA"/logs/hadoop "$DATA"/logs/spark "$OPT"
sudo chown -R "$USER":"$USER" "$DATA" "$OPT"
# NameNode/DataNode 디렉터리 권한이 느슨하면 기동을 거부한다.
chmod 700 "$DATA"/hdfs/name "$DATA"/hdfs/data

# ── [4/8] SSH 키 (start-dfs.sh 가 워커에 붙는 데 씀) ───
echo "── [4/8] SSH 키 ───────────────────────────────────"
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519 -q
touch ~/.ssh/authorized_keys
grep -qF "$(cat ~/.ssh/id_ed25519.pub)" ~/.ssh/authorized_keys || \
  cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys
chmod 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
printf "Host *\n  StrictHostKeyChecking no\n  UserKnownHostsFile /dev/null\n  LogLevel ERROR\n" > ~/.ssh/config
chmod 600 ~/.ssh/config
# -n 필수: 없으면 ssh 가 표준입력을 삼켜서 이 스크립트를
# 파이프로 넘길 때 뒷부분이 잘린다.
if ssh -n -o BatchMode=yes localhost true 2>/dev/null; then
  echo "  localhost 무암호 SSH 정상"
else
  echo "  ⚠ localhost SSH 실패 — sshd 상태를 확인하세요."
fi

# ── [5/8] Hadoop · Spark 풀기 ──────────────────────────
echo "── [5/8] 압축 해제 ───────────────────────────────"
if [ ! -d "$OPT/hadoop-$HADOOP_VER" ]; then
  if [ ! -s "$DL/hadoop-$HADOOP_VER.tar.gz" ]; then
    echo "  $DL/hadoop-$HADOOP_VER.tar.gz 없음" >&2; exit 1
  fi
  tar -xzf "$DL/hadoop-$HADOOP_VER.tar.gz" -C "$OPT"
fi
if [ ! -d "$OPT/spark-$SPARK_VER-bin-hadoop3" ]; then
  if [ ! -s "$DL/spark-$SPARK_VER-bin-hadoop3.tgz" ]; then
    echo "  $DL/spark-$SPARK_VER-bin-hadoop3.tgz 없음" >&2; exit 1
  fi
  tar -xzf "$DL/spark-$SPARK_VER-bin-hadoop3.tgz" -C "$OPT"
fi
ln -sfn "$OPT/hadoop-$HADOOP_VER" "$OPT/hadoop"
ln -sfn "$OPT/spark-$SPARK_VER-bin-hadoop3" "$OPT/spark"
echo "  hadoop → $(readlink -f "$OPT/hadoop")"
echo "  spark  → $(readlink -f "$OPT/spark")"

HC="$OPT/hadoop/etc/hadoop"

# ── [6/8] Hadoop 설정 ─────────────────────────────────
echo "── [6/8] Hadoop 설정 ─────────────────────────────"

cat > "$HC/core-site.xml" <<XEOF
<?xml version="1.0"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>
  <property><name>fs.defaultFS</name><value>hdfs://dispatch-master:9000</value></property>
  <property><name>hadoop.tmp.dir</name><value>$DATA/hadoop/tmp</value></property>
  <property><name>io.file.buffer.size</name><value>131072</value></property>
  <!-- 팀원마다 WSL 사용자명이 달라서 프록시 사용자를 열어 둔다 -->
  <property><name>hadoop.proxyuser.$USER.hosts</name><value>*</value></property>
  <property><name>hadoop.proxyuser.$USER.groups</name><value>*</value></property>
</configuration>
XEOF

cat > "$HC/hdfs-site.xml" <<XEOF
<?xml version="1.0"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>
  <property><name>dfs.replication</name><value>3</value></property>
  <property><name>dfs.namenode.name.dir</name><value>file://$DATA/hdfs/name</value></property>
  <property><name>dfs.datanode.data.dir</name><value>file://$DATA/hdfs/data</value></property>

  <!-- 랜 전체에서 붙어야 하므로 전 인터페이스 리슨 -->
  <property><name>dfs.namenode.rpc-bind-host</name><value>0.0.0.0</value></property>
  <property><name>dfs.namenode.servicerpc-bind-host</name><value>0.0.0.0</value></property>
  <property><name>dfs.namenode.http-bind-host</name><value>0.0.0.0</value></property>
  <property><name>dfs.datanode.address</name><value>0.0.0.0:9866</value></property>
  <property><name>dfs.datanode.http.address</name><value>0.0.0.0:9864</value></property>
  <property><name>dfs.datanode.ipc.address</name><value>0.0.0.0:9867</value></property>

  <!-- 미러링 모드 WSL 은 호스트명이 DESKTOP-xxxx 이고 역DNS 가 안 된다.
       IP 로만 등록하게 하고 호스트명 검사를 끈다. -->
  <property><name>dfs.namenode.datanode.registration.ip-hostname-check</name><value>false</value></property>
  <property><name>dfs.client.use.datanode.hostname</name><value>false</value></property>
  <property><name>dfs.datanode.use.datanode.hostname</name><value>false</value></property>

  <!-- 전 노드가 Wi-Fi AP 하나를 공유한다.
       잠깐 끊긴 노드를 죽었다고 판단하면 재복제가 몰려 AP 를 마비시킨다.
       사망 판정을 10.5분 → 20.5분으로 늘리고 재복제·밸런서 대역을 묶는다. -->
  <property><name>dfs.namenode.heartbeat.recheck-interval</name><value>600000</value></property>
  <property><name>dfs.namenode.replication.max-streams</name><value>2</value></property>
  <property><name>dfs.datanode.balance.bandwidthPerSec</name><value>10485760</value></property>

  <!-- 쓰기 중 노드가 빠져도 남은 노드로 계속 쓴다 -->
  <property><name>dfs.client.block.write.replace-datanode-on-failure.policy</name><value>DEFAULT</value></property>
  <property><name>dfs.client.block.write.replace-datanode-on-failure.best-effort</name><value>true</value></property>

  <property><name>dfs.permissions.enabled</name><value>false</value></property>
  <property><name>dfs.webhdfs.enabled</name><value>true</value></property>
</configuration>
XEOF

cat > "$HC/yarn-site.xml" <<XEOF
<?xml version="1.0"?>
<configuration>
  <property><name>yarn.resourcemanager.hostname</name><value>dispatch-master</value></property>
  <property><name>yarn.resourcemanager.bind-host</name><value>0.0.0.0</value></property>
  <property><name>yarn.nodemanager.bind-host</name><value>0.0.0.0</value></property>
  <property><name>yarn.timeline-service.bind-host</name><value>0.0.0.0</value></property>

  <property><name>yarn.nodemanager.aux-services</name><value>mapreduce_shuffle</value></property>
  <property><name>yarn.nodemanager.resource.memory-mb</name><value>$NM_MEM_MB</value></property>
  <property><name>yarn.nodemanager.resource.cpu-vcores</name><value>$NM_VCORES</value></property>
  <property><name>yarn.scheduler.minimum-allocation-mb</name><value>1024</value></property>
  <property><name>yarn.scheduler.maximum-allocation-mb</name><value>$NM_MEM_MB</value></property>
  <property><name>yarn.scheduler.maximum-allocation-vcores</name><value>$NM_VCORES</value></property>

  <!-- WSL 은 가상 메모리를 과하게 잡아서 vmem 검사에 걸린다 -->
  <property><name>yarn.nodemanager.vmem-check-enabled</name><value>false</value></property>
  <property><name>yarn.nodemanager.pmem-check-enabled</name><value>true</value></property>

  <property><name>yarn.nodemanager.local-dirs</name><value>$DATA/hadoop/nm-local</value></property>
  <property><name>yarn.nodemanager.log-dirs</name><value>$DATA/hadoop/nm-log</value></property>
  <property><name>yarn.log-aggregation-enable</name><value>true</value></property>
  <property><name>yarn.nodemanager.delete.debug-delay-sec</name><value>600</value></property>

  <!-- Wi-Fi 가 끊겨 AM 이 죽는 경우가 있어 재시도를 준다 -->
  <property><name>yarn.resourcemanager.am.max-attempts</name><value>3</value></property>
  <property><name>yarn.nm.liveness-monitor.expiry-interval-ms</name><value>1200000</value></property>
</configuration>
XEOF

cat > "$HC/mapred-site.xml" <<XEOF
<?xml version="1.0"?>
<configuration>
  <property><name>mapreduce.framework.name</name><value>yarn</value></property>
  <property><name>yarn.app.mapreduce.am.env</name><value>HADOOP_MAPRED_HOME=$OPT/hadoop</value></property>
  <property><name>mapreduce.map.env</name><value>HADOOP_MAPRED_HOME=$OPT/hadoop</value></property>
  <property><name>mapreduce.reduce.env</name><value>HADOOP_MAPRED_HOME=$OPT/hadoop</value></property>
  <property><name>mapreduce.map.memory.mb</name><value>2048</value></property>
  <property><name>mapreduce.reduce.memory.mb</name><value>4096</value></property>
  <property><name>yarn.app.mapreduce.am.command-opts</name><value>-Xmx1024m</value></property>
  <property><name>mapreduce.map.java.opts</name><value>-Xmx1638m</value></property>
  <property><name>mapreduce.reduce.java.opts</name><value>-Xmx3276m</value></property>
</configuration>
XEOF

# hadoop-env.sh
sed -i "/# === dispatch ===/,/# === dispatch end ===/d" "$HC/hadoop-env.sh"
cat >> "$HC/hadoop-env.sh" <<XEOF
# === dispatch ===
export JAVA_HOME=$JAVA_HOME_PATH
export HADOOP_HOME=$OPT/hadoop
export HADOOP_LOG_DIR=$DATA/logs/hadoop
export HADOOP_PID_DIR=$DATA/hadoop/tmp
export PDSH_RCMD_TYPE=ssh
export HDFS_NAMENODE_OPTS="-Xmx2g"
export HDFS_DATANODE_OPTS="-Xmx1g"
export YARN_RESOURCEMANAGER_OPTS="-Xmx1500m"
export YARN_NODEMANAGER_OPTS="-Xmx1g"
# === dispatch end ===
XEOF

# workers — start-dfs.sh / start-yarn.sh 가 SSH 로 붙는 대상.
# 워커 노트북 IP 가 정해지면 여기에 한 줄씩 추가한다.
if [ "$ROLE" = master ]; then
  echo "$MASTER_IP" > "$HC/workers"
  echo "  workers: $(tr "\n" " " < "$HC/workers")"
fi
echo "  core/hdfs/yarn/mapred-site.xml 작성 완료"

# ── [7/8] Spark 설정 ──────────────────────────────────
echo "── [7/8] Spark 설정 ──────────────────────────────"
# SPARK_LOCAL_IP 가 필수다.
# 미러링 모드 WSL 에는 lo 에 10.255.255.254/32 가 붙어 있어서 Spark 가
# 자기 주소를 그걸로 잡는다. 그러면 원격 노드의 익스큐터가 드라이버·
# 블록매니저에 붙지 못한다 (10.255.255.254 는 각 노드의 로컬 주소).
# 랜에서 보이는 IP 를 명시한다.
cat > "$OPT/spark/conf/spark-env.sh" <<XEOF
#!/usr/bin/env bash
export JAVA_HOME=$JAVA_HOME_PATH
export HADOOP_HOME=$OPT/hadoop
export HADOOP_CONF_DIR=$HC
export YARN_CONF_DIR=$HC
export SPARK_LOG_DIR=$DATA/logs/spark
export PYSPARK_PYTHON=python3

# 이 노드의 랜 IP 를 매번 다시 찾는다 (하드코딩하지 않는다)
SPARK_LOCAL_IP=\$(ip -4 -o addr show scope global 2>/dev/null | grep -v " lo " | head -1 | tr -s " " | cut -d" " -f4 | cut -d/ -f1)
export SPARK_LOCAL_IP
export SPARK_PUBLIC_DNS=\$SPARK_LOCAL_IP
XEOF
chmod +x "$OPT/spark/conf/spark-env.sh"

cat > "$OPT/spark/conf/spark-defaults.conf" <<'XEOF'
spark.master                       yarn
spark.submit.deployMode            client
spark.driver.memory                4g
spark.executor.memory              5g
spark.executor.cores               3
spark.executor.instances           8
spark.dynamicAllocation.enabled    false

# 노드 5대 · 코어 30개. 셔플이 전부 Wi-Fi 를 타므로
# 파티션을 과하게 쪼개지 않는다 (코어수 x2).
spark.sql.shuffle.partitions       60
spark.default.parallelism          60
spark.sql.adaptive.enabled         true
spark.sql.adaptive.coalescePartitions.enabled true
spark.sql.parquet.compression.codec zstd
spark.serializer                   org.apache.spark.serializer.KryoSerializer

spark.eventLog.enabled             true
spark.eventLog.dir                 hdfs://dispatch-master:9000/spark-logs
spark.history.fs.logDirectory      hdfs://dispatch-master:9000/spark-logs

# Spark 4 는 ANSI SQL 모드가 기본 켜짐이다. 잘못된 캐스팅·숫자 넘침에서
# null 대신 예외를 던진다. 스팀 JSON 은 필드 타입이 흔들려서(is_early_access
# 키 누락 등 실측) 매일 09시 배치가 그 자리에서 멈출 수 있다.
# 파이프라인이 안정될 때까지 Spark 3 동작을 유지한다.
# 대신 캐스팅 지점마다 null 개수를 세서 배치 로그에 남긴다.
spark.sql.ansi.enabled             false

# 드라이버·블록매니저 포트를 고정한다.
# 기본값은 임의 포트라 Windows 방화벽으로 열 수가 없다.
# port.maxRetries 32 → 아래 각 시작 포트에서 최대 32개까지만 쓴다.
spark.driver.port                  17177
spark.driver.blockManager.port     17210
spark.blockManager.port            17240
spark.port.maxRetries              30
spark.ui.port                      4040

# Wi-Fi 가 끊겼을 때 즉시 실패하지 않게 여유를 준다
spark.network.timeout              300s
spark.executor.heartbeatInterval   30s
spark.rpc.askTimeout               300s
spark.task.maxFailures             6
spark.stage.maxConsecutiveAttempts 8
XEOF
echo "  spark-env.sh · spark-defaults.conf 작성 완료"

# ── [8/8] 환경변수 ────────────────────────────────────
echo "── [8/8] ~/.bashrc ───────────────────────────────"
sed -i "/# === dispatch env ===/,/# === dispatch env end ===/d" ~/.bashrc
cat >> ~/.bashrc <<XEOF
# === dispatch env ===
export JAVA_HOME=$JAVA_HOME_PATH
export HADOOP_HOME=$OPT/hadoop
export HADOOP_CONF_DIR=$HC
export YARN_CONF_DIR=$HC
export SPARK_HOME=$OPT/spark
export HADOOP_LOG_DIR=$DATA/logs/hadoop
export PDSH_RCMD_TYPE=ssh
export PYSPARK_PYTHON=python3
export PATH=\$PATH:\$HADOOP_HOME/bin:\$HADOOP_HOME/sbin:\$SPARK_HOME/bin
# === dispatch env end ===
XEOF
echo "  등록 완료"

echo
echo "✔ $ROLE 노드 구성 완료 — $(hostname) $MYIP"
if [ "$ROLE" = master ]; then
  echo
  echo "  다음:  source ~/.bashrc"
  echo "         hdfs namenode -format -force -nonInteractive"
  echo "         start-dfs.sh && start-yarn.sh"
else
  echo
  echo "  마스터에서 이 노드 IP 를 \$HADOOP_CONF_DIR/workers 에 추가한 뒤"
  echo "  start-dfs.sh · start-yarn.sh 를 다시 돌리세요."
fi
