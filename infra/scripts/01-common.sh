#!/usr/bin/env bash
# 공통 준비 — 두 서버 모두에서 실행
#   Java 17 · /etc/hosts · 디렉터리 · 커널 파라미터
set -euo pipefail

MASTER_IP=172.26.7.158     # j15a202a — NameNode / ResourceManager
WORKER_IP=172.26.8.198     # j15a202  — DataNode / NodeManager / PostgreSQL

echo "── [1/5] apt 갱신 · Java 17 ────────────────────────────"
sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
     openjdk-17-jdk-headless ssh pdsh rsync curl >/dev/null
java -version 2>&1 | head -1

JAVA_HOME_PATH=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
echo "JAVA_HOME = $JAVA_HOME_PATH"

echo "── [2/5] /etc/hosts ────────────────────────────────────"
sudo sed -i '/dispatch-master/d;/dispatch-worker/d' /etc/hosts
sudo tee -a /etc/hosts >/dev/null <<EOF
$MASTER_IP dispatch-master
$WORKER_IP dispatch-worker
EOF
grep dispatch /etc/hosts

echo "── [3/5] 디렉터리 ───────────────────────────────────────"
sudo mkdir -p /opt /data/hdfs/{name,data} /data/logs
sudo chown -R ubuntu:ubuntu /data /opt
ls -ld /data/hdfs/name /data/hdfs/data

echo "── [4/5] 환경변수 (~/.bashrc) ──────────────────────────"
sed -i '/# === dispatch env ===/,/# === dispatch env end ===/d' ~/.bashrc
cat >> ~/.bashrc <<EOF
# === dispatch env ===
export JAVA_HOME=$JAVA_HOME_PATH
export HADOOP_HOME=/opt/hadoop
export SPARK_HOME=/opt/spark
export HADOOP_CONF_DIR=\$HADOOP_HOME/etc/hadoop
export PATH=\$PATH:\$HADOOP_HOME/bin:\$HADOOP_HOME/sbin:\$SPARK_HOME/bin:\$SPARK_HOME/sbin
export PDSH_RCMD_TYPE=ssh
# === dispatch env end ===
EOF
echo "  등록 완료"

echo "── [5/5] 커널 파라미터 ─────────────────────────────────"
# HDFS/Spark 는 파일 디스크립터를 많이 씁니다.
# 그리고 스팀 수집기가 동시 연결을 열기 때문에 포트 범위도 넓혀 둡니다.
sudo tee /etc/security/limits.d/99-dispatch.conf >/dev/null <<'EOF'
ubuntu soft nofile 65536
ubuntu hard nofile 65536
ubuntu soft nproc  32768
ubuntu hard nproc  32768
EOF
sudo tee /etc/sysctl.d/99-dispatch.conf >/dev/null <<'EOF'
net.ipv4.ip_local_port_range = 10240 65535
net.ipv4.tcp_fin_timeout = 15
net.core.somaxconn = 4096
vm.swappiness = 10
EOF
sudo sysctl -q --system
echo "  nofile=$(ulimit -n) (재로그인 후 65536)"

echo
echo "✔ 공통 준비 완료 — $(hostname)"
