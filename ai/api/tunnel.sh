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
KEY="${KEY:-$HOME/.ssh/thispatch-ai-tunnel}"
PORT="${PORT:-8100}"
BIND="${BIND:-172.17.0.1}"
RETRY="${RETRY:-10}"
BUSY_RETRY="${BUSY_RETRY:-30}"      # 서버1 포트가 이전 접속에 잡혀 있을 때 재시도 간격
BUSY_MAX_MIN="${BUSY_MAX_MIN:-10}"  # 그 상태가 이만큼 이어지면 사람을 부른다

if [ ! -f "$KEY" ]; then
  echo "SSH 키가 없습니다: $KEY"
  echo "터널 전용 키를 만들고 공개키를 서버1 에 등록해야 합니다:"
  echo "  ssh-keygen -t ed25519 -f ~/.ssh/thispatch-ai-tunnel -N '' -C thispatch-ai-tunnel@ai-node"
  echo "  그 뒤 .pub 내용을 인프라 담당에게 전달 (docs/backend-connection.md 참고)"
  exit 1
fi

if ! curl -fsS --max-time 5 "http://127.0.0.1:$PORT/health" > /dev/null; then
  echo "로컬 $PORT 에 AI 서버가 없습니다. bash start.sh 로 먼저 켜세요."
  exit 1
fi
echo "로컬 AI 서버 확인"
echo "역터널 연결: $USER_NAME@$SERVER_HOST 안쪽 $BIND:$PORT -> 이 노트북 127.0.0.1:$PORT"
echo "끊기면 ${RETRY}초 뒤 다시 붙습니다. 중지하려면 Ctrl+C."

# 로그를 파일에도 남긴다. 터널은 조용히 끊기고 백엔드 쪽에서만 실패로 보인다(9/21).
LOG_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/tunnel-$(date +%Y%m%d-%H%M%S).log"
say() { printf '%s %s
' "$(date +%H:%M:%S)" "$1" | tee -a "$LOG"; }
say "로그: $LOG"
say "역터널 시작 $BIND:$PORT -> 127.0.0.1:$PORT ($USER_NAME@$SERVER_HOST)"

# 곧바로 끊기는 경우를 둘로 나눈다(9/21).
#   포트 점유  서버1 에 이전 접속이 아직 포트를 잡고 있는 것. 우리가 고칠 수 없고 몇 분이면 풀린다.
#             여기서 종료하면 네트워크가 돌아와도 사람이 손대기 전까지 터널이 죽은 채로 남는다.
#   그 외      키·권한·GatewayPorts 같은 설정 문제. 기다려도 낫지 않으므로 바로 멈춘다.
busy_since=""
while true; do
  started=$(date +%s)
  err=$(mktemp)
  ssh -N     -o ExitOnForwardFailure=yes     -o ServerAliveInterval=30     -o ServerAliveCountMax=3     -o StrictHostKeyChecking=accept-new     -i "$KEY"     -R "$BIND:$PORT:127.0.0.1:$PORT"     "$USER_NAME@$SERVER_HOST" 2> >(tee -a "$err" >&2)
  lasted=$(( $(date +%s) - started ))
  stderr_text=$(cat "$err"); rm -f "$err"
  [ -n "$stderr_text" ] && printf '%s
' "$stderr_text" >> "$LOG"

  if [ "$lasted" -lt 5 ] && printf '%s' "$stderr_text" | grep -q "remote port forwarding failed"; then
    [ -z "$busy_since" ] && busy_since=$(date +%s)
    waited=$(( ( $(date +%s) - busy_since ) / 60 ))
    if [ "$waited" -ge "$BUSY_MAX_MIN" ]; then
      say "서버1 의 $PORT 가 ${BUSY_MAX_MIN}분째 풀리지 않습니다. 인프라 담당에게 확인을 요청하세요."
      say "  서버1 에서: sudo ss -tlnp | grep $PORT  (남아 있는 sshd: ubuntu 세션 종료)"
      exit 1
    fi
    say "서버1 의 $PORT 가 아직 이전 접속에 잡혀 있습니다. ${BUSY_RETRY}초 뒤 다시 시도합니다(${waited}분째)."
    sleep "$BUSY_RETRY"
    continue
  fi

  if [ "$lasted" -lt 5 ]; then
    say "${lasted}초 만에 끊겼습니다. 설정을 확인하세요."
    say "  - 서버1 sshd_config 의 GatewayPorts clientspecified (없으면 $BIND 바인딩이 거부됩니다)"
    say "  - 키 권한(chmod 600)과 사용자 이름"
    exit 1
  fi

  busy_since=""
  say "연결이 끊겼습니다(${lasted}초 유지). ${RETRY}초 뒤 재연결합니다."
  sleep "$RETRY"
done
