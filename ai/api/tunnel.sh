#!/bin/bash
# AI 서버(8100)를 서버1 안쪽으로 넘기는 SSH 역터널 (WSL 판). 설명은 tunnel.ps1 과 같다.
#
#   EC2 -> 노트북 라우팅이 없어 포트를 열어도 닿지 않는다. 반대로 노트북 -> 서버1 22번은 열려 있다.
#   백엔드가 컨테이너라 도커 브리지(172.17.0.1)에 바인딩해야 보이고,
#   그러려면 서버1 sshd_config 에 GatewayPorts clientspecified 가 있어야 한다.
#
# 사용   bash tunnel.sh                     (기본값)
#        KEY=~/.ssh/J15A202T.pem bash tunnel.sh
set -u
SERVER_HOST="${SERVER_HOST:-j15a202.p.ssafy.io}"
USER_NAME="${USER_NAME:-ubuntu}"
KEY="${KEY:-$HOME/.ssh/J15A202T.pem}"
PORT="${PORT:-8100}"
BIND="${BIND:-172.17.0.1}"
RETRY="${RETRY:-10}"

if [ ! -f "$KEY" ]; then
  echo "SSH 키가 없습니다: $KEY"
  echo "인프라 담당에게 받아 그 경로에 두거나 KEY=... 로 지정하세요."
  exit 1
fi

if ! curl -fsS --max-time 5 "http://127.0.0.1:$PORT/health" > /dev/null; then
  echo "로컬 $PORT 에 AI 서버가 없습니다. bash start.sh 로 먼저 켜세요."
  exit 1
fi
echo "로컬 AI 서버 확인"
echo "역터널 연결: $USER_NAME@$SERVER_HOST 안쪽 $BIND:$PORT -> 이 노트북 127.0.0.1:$PORT"
echo "끊기면 ${RETRY}초 뒤 다시 붙습니다. 중지하려면 Ctrl+C."

while true; do
  started=$(date +%s)
  ssh -N \
    -o ExitOnForwardFailure=yes \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -o StrictHostKeyChecking=accept-new \
    -i "$KEY" \
    -R "$BIND:$PORT:127.0.0.1:$PORT" \
    "$USER_NAME@$SERVER_HOST"
  lasted=$(( $(date +%s) - started ))
  if [ "$lasted" -lt 5 ]; then
    echo
    echo "${lasted}초 만에 끊겼습니다. 설정을 확인하세요."
    echo "  - 서버1 sshd_config 의 GatewayPorts clientspecified (없으면 $BIND 바인딩이 거부됩니다)"
    echo "  - 서버에서 $PORT 중복 사용 여부 (ss -lntp | grep $PORT)"
    echo "  - 키 권한(chmod 600)과 사용자 이름"
    exit 1
  fi
  echo "연결이 끊겼습니다(${lasted}초 유지). ${RETRY}초 뒤 재연결합니다."
  sleep "$RETRY"
done
