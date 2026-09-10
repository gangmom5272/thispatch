#!/usr/bin/env bash
# 디스패치 클러스터 제어 — 마스터 노드에서만 실행
#
#   ./07-cluster.sh format     NameNode 최초 포맷 (한 번만)
#   ./07-cluster.sh start      HDFS + YARN 기동
#   ./07-cluster.sh stop       정지
#   ./07-cluster.sh status     데몬 · 노드 상태
#   ./07-cluster.sh verify     쓰기/읽기 + Spark 잡 실전 검증
#   ./07-cluster.sh addworker 70.12.xxx.xxx    워커 등록
#   ./07-cluster.sh setmaster 70.12.xxx.xxx    마스터 IP 변경 (전 노드에서 실행)
set -uo pipefail

export HADOOP_HOME=${HADOOP_HOME:-/opt/hadoop}
export SPARK_HOME=${SPARK_HOME:-/opt/spark}
export HADOOP_CONF_DIR=${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}
export PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin:$SPARK_HOME/bin
export PDSH_RCMD_TYPE=ssh
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)
  export JAVA_HOME
fi

CMD=${1:-status}

banner() { echo; echo "══ $* ══════════════════════════════════"; }

case "$CMD" in

format)
  banner "NameNode 포맷"
  if [ -d /data/hdfs/name/current ]; then
    echo "이미 포맷되어 있습니다. 다시 하면 HDFS 전체가 날아갑니다."
    echo "정말 다시 하려면:  rm -rf /data/hdfs/name/* /data/hdfs/data/*  후 재실행"
    exit 1
  fi
  hdfs namenode -format -force -nonInteractive -clusterId dispatch
  echo "완료."
  ;;

start)
  banner "HDFS 기동"
  start-dfs.sh
  banner "YARN 기동"
  start-yarn.sh
  banner "HDFS 기본 디렉터리"
  # Spark 이벤트 로그와 사용자 홈은 미리 만들어 둔다
  hdfs dfs -mkdir -p /spark-logs /user/"$USER" /dispatch/raw /dispatch/curated 2>/dev/null
  hdfs dfs -chmod -R 777 /spark-logs 2>/dev/null
  hdfs dfs -ls / 2>/dev/null
  echo
  echo "웹 UI:  NameNode  http://localhost:9870"
  echo "        YARN      http://localhost:8088"
  ;;

stop)
  banner "정지"
  stop-yarn.sh
  stop-dfs.sh
  ;;

status)
  banner "데몬 (이 노드)"
  jps | grep -vw Jps || echo "  없음"
  banner "HDFS 노드"
  hdfs dfsadmin -report 2>/dev/null | grep -E "^(Live|Dead|Configured Capacity|DFS Used|DFS Remaining|Name:|Hostname:|Decommission)" || echo "  NameNode 응답 없음"
  banner "YARN 노드"
  yarn node -list 2>/dev/null | tail -n +2 || echo "  ResourceManager 응답 없음"
  banner "복제 상태"
  hdfs fsck / 2>/dev/null | grep -E "Total blocks|Under-replicated|Missing|Corrupt|Average block replication|HEALTHY|CORRUPT" || echo "  확인 불가"
  ;;

setmaster)
  # 마스터 IP 는 고정이 아니다. 무선↔유선을 바꾸면 서브넷까지 달라진다
  # (실측: 70.12.246.60/21 → 70.12.108.81/24).
  # 설정 XML 은 dispatch-master 라는 이름만 쓰므로 /etc/hosts 한 줄만 고치면 된다.
  IP=${2:-}
  if [ -z "$IP" ]; then
    echo "사용법: $0 setmaster 70.12.xxx.xxx" >&2
    echo "현재: $(getent hosts dispatch-master || echo 등록 없음)" >&2
    exit 1
  fi
  banner "마스터 주소 변경"
  echo "  이전: $(getent hosts dispatch-master | tr -s " " | cut -d" " -f1 || echo 없음)"
  sudo sed -i "/dispatch-master/d" /etc/hosts
  echo "$IP dispatch-master" | sudo tee -a /etc/hosts >/dev/null
  echo "  이후: $(getent hosts dispatch-master | tr -s " " | cut -d" " -f1)"

  # WSL 이 부팅마다 /etc/hosts 를 새로 만들면 이 항목이 사라진다
  if ! grep -q "generateHosts" /etc/wsl.conf 2>/dev/null; then
    printf "
[network]
generateHosts = false
generateResolvConf = true
" | sudo tee -a /etc/wsl.conf >/dev/null
    echo "  /etc/wsl.conf 에 generateHosts=false 추가"
  fi

  # 마스터 자신이면 workers 파일의 자기 항목도 갱신한다
  W=$HADOOP_CONF_DIR/workers
  MYIP=$(ip -4 -o addr show scope global | grep -v " lo " | head -1 | tr -s " " | cut -d" " -f4 | cut -d/ -f1)
  if [ -f "$W" ] && [ "$MYIP" = "$IP" ]; then
    if ! grep -qx "$IP" "$W"; then
      sed -i "1i $IP" "$W"
      echo "  workers 에 $IP 추가"
    fi
  fi


  # 이 노드 자신의 IP 도 바눵을 수 있다.
  # yarn.nodemanager.* 는 자기 IP 를 박고 있어서 /etc/hosts 만 고쳐도 부족하다.
  # 실제로 마스터 IP 가 하루 반 사이 세 번 바뀌었고(무선→유선→IP 추돌),
  # 그럴 때마다 04-wsl-node.sh 를 다시 돌려야 했다. 여기서 함께 고친다.
  SELFIP=$(ip route get "$IP" 2>/dev/null | grep -oE "src [0-9.]+" | cut -d" " -f2 | head -1)
  if [ -z "${SELFIP:-}" ]; then
    DEFIF=$(ip route | grep "^default" | head -1 | tr -s " " | cut -d" " -f5)
    SELFIP=$(ip -4 -o addr show dev "$DEFIF" scope global 2>/dev/null | tr -s " " | cut -d" " -f4 | cut -d/ -f1 | head -1)
  fi

  Y=$HADOOP_CONF_DIR/yarn-site.xml
  if [ -n "${SELFIP:-}" ] && [ -f "$Y" ]; then
    OLD=$(grep -oE "<name>yarn.nodemanager.address</name><value>[0-9.]+" "$Y" \
          | grep -oE "[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+" | head -1)
    if [ -n "$OLD" ] && [ "$OLD" != "$SELFIP" ]; then
      sed -i "s|>${OLD}:8041<|>${SELFIP}:8041<|; s|>${OLD}:8040<|>${SELFIP}:8040<|; s|>${OLD}:8042<|>${SELFIP}:8042<|; s|<value>${OLD}</value>|<value>${SELFIP}</value>|" "$Y"
      echo "  yarn-site.xml 의 자기 주소 $OLD → $SELFIP"
    else
      echo "  yarn-site.xml 의 자기 주소 $SELFIP (변경 없음)"
    fi
  fi

  # workers 에 남은 옛 마스터 항목을 걷어낸다
  if [ -f "$W" ] && [ -n "${SELFIP:-}" ] && [ "$SELFIP" = "$IP" ]; then
    for old in $(grep -vx "$IP" "$W" | grep -E "^${IP%.*}\." || true); do
      sed -i "/^${old}$/d" "$W"
      echo "  workers 에서 옛 마스터 $old 제거"
    done
  fi
  echo
  echo "  데몬 재시작:  sudo systemctl restart dispatch-cluster.target"
  ;;

addworker)
  IP=${2:-}
  [ -n "$IP" ] || { echo "사용법: $0 addworker 70.12.xxx.xxx" >&2; exit 1; }
  W=$HADOOP_CONF_DIR/workers
  if grep -qx "$IP" "$W" 2>/dev/null; then
    echo "$IP 는 이미 등록되어 있습니다."
  else
    echo "$IP" >> "$W"
    echo "$IP 등록. 현재 워커: $(tr "\n" " " < "$W")"
  fi
  echo
  # Docker Desktop 이 깔린 노트북은 자기 IP 가 host.docker.internal 로
  # 역방향 조회되어 NodeManager 가 그 이름으로 등록한다. 워커 쪽에서
  # dispatch-w<마지막 옥텟> 으로 덮으므로 마스터도 그 이름을 알아야 한다.
  NODE_NAME="dispatch-w${IP##*.}"
  if grep -qE "^${IP}[[:space:]]+${NODE_NAME}$" /etc/hosts 2>/dev/null; then
    echo "  /etc/hosts: $NODE_NAME 이미 있음"
  else
    sudo sed -i "/[[:space:]]${NODE_NAME}$/d" /etc/hosts
    echo "$IP $NODE_NAME" | sudo tee -a /etc/hosts >/dev/null
    echo "  /etc/hosts 에 $IP $NODE_NAME 등록"
  fi

  echo "무암호 SSH 확인:"
  if ssh -n -o BatchMode=yes -o ConnectTimeout=5 "$IP" true 2>/dev/null; then
    echo "  $IP 정상"
    echo
    echo "이 노드의 데몬만 띄우려면:"
    echo "  ssh $IP \"/opt/hadoop/bin/hdfs --daemon start datanode\""
    echo "  ssh $IP \"/opt/hadoop/bin/yarn --daemon start nodemanager\""
  else
    echo "  $IP SSH 실패 — 아래 공개키를 그 노드의 ~/.ssh/authorized_keys 에 넣어야 합니다:"
    echo
    cat ~/.ssh/id_ed25519.pub
  fi
  ;;

verify)
  banner "1. HDFS 쓰기/읽기"
  T=/tmp/dispatch-verify-$$.txt
  # 블록 경계를 넘기려면 파일이 커야 의미가 있다. 64MB 를 만든다.
  head -c 64M /dev/urandom | base64 > "$T"
  SRC_SUM=$(sha256sum "$T" | cut -d" " -f1)
  SRC_SZ=$(stat -c %s "$T")
  echo "  원본 $((SRC_SZ/1048576))MB  sha256 ${SRC_SUM:0:16}..."

  hdfs dfs -rm -f -skipTrash /dispatch/verify.txt >/dev/null 2>&1
  if ! hdfs dfs -put "$T" /dispatch/verify.txt; then
    echo "  쓰기 실패"; rm -f "$T"; exit 1
  fi
  echo "  쓰기 성공"
  # -stat 포맷 문자열에 한글을 넣으면 깨진다. 영문으로 받아서 붙인다.
  echo "  $(hdfs dfs -stat "size=%b repl=%r block=%o" /dispatch/verify.txt)"

  DST_SUM=$(hdfs dfs -cat /dispatch/verify.txt | sha256sum | cut -d" " -f1)
  echo "  읽기 sha256 ${DST_SUM:0:16}..."
  if [ "$SRC_SUM" = "$DST_SUM" ]; then echo "  ✔ 무손실 왕복"; else echo "  ✘ 내용 불일치"; fi
  echo "  블록 배치:"
  hdfs fsck /dispatch/verify.txt -files -blocks -locations 2>/dev/null | grep -E "^0\.|len=" | head -5 | sed "s/^/    /"
  rm -f "$T"

  banner "2. YARN MapReduce (pi)"
  JAR=$(ls "$HADOOP_HOME"/share/hadoop/mapreduce/hadoop-mapreduce-examples-*.jar | head -1)
  if yarn jar "$JAR" pi 4 200 2>&1 | tail -3; then :; fi

  banner "3. Spark on YARN"
  spark-submit --master yarn --deploy-mode client \
    --num-executors 2 --executor-memory 2g --executor-cores 2 \
    --class org.apache.spark.examples.SparkPi \
    "$SPARK_HOME"/examples/jars/spark-examples_*.jar 100 2>&1 \
    | grep -E "Pi is roughly|ERROR|Exception" | head -5

  banner "4. Spark → HDFS Parquet 왕복"
  # 실측 주의: partitionBy 로 쓴 BOOLEAN 컬럼은 읽을 때 STRING 으로 돌아온다.
  # 파티션 값이 디렉터리명(voted_up=true)에서 복원되기 때문이다.
  #   partitionBy("voted_up")  → struct<...,voted_up:string>
  #   일반 컬럼                 → struct<...,voted_up:boolean>
  # 그래서 HDFS 파티션은 날짜(stat_date)로만 잡고 voted_up 은 일반 컬럼으로 둔다.
  cat > /tmp/pq-$$.py <<"PYEOF"
from pyspark.sql import SparkSession, functions as F
s = SparkSession.builder.appName("dispatch-verify-parquet").getOrCreate()
df = s.range(0, 2_000_000).withColumn("appid", (F.col("id") % 74000).cast("bigint")) \
      .withColumn("voted_up", (F.col("id") % 3 != 0)) \
      .withColumn("stat_date", F.date_add(F.lit("2026-09-01").cast("date"), (F.col("id") % 14).cast("int")))
p = "hdfs://dispatch-master:9000/dispatch/verify_parquet"
df.write.mode("overwrite").partitionBy("stat_date").parquet(p)
back = s.read.parquet(p)
print("PARQUET_SCHEMA", back.schema.simpleString())
print("PARQUET_ROWS", back.count())
print("PARQUET_POS", back.filter(F.col("voted_up")).count())
print("PARQUET_APPIDS", back.select("appid").distinct().count())
print("PARQUET_DATES", back.select("stat_date").distinct().count())
s.stop()
PYEOF
  spark-submit --master yarn --deploy-mode client \
    --num-executors 3 --executor-memory 3g --executor-cores 2 \
    /tmp/pq-$$.py 2>&1 | grep -E "^PARQUET_|ERROR|Exception" | head -8
  rm -f /tmp/pq-$$.py

  banner "5. 정리"
  hdfs dfs -rm -f -r -skipTrash /dispatch/verify.txt /dispatch/verify_parquet >/dev/null 2>&1
  echo "  임시 데이터 삭제"
  ;;

*)
  sed -n "2,13p" "$0"
  exit 1
  ;;
esac
