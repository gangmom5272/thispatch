#!/bin/bash
# This Patch AI 서버 기동 (WSL/Linux). 사용자가 직접 실행한다.  bash start.sh [port]
PORT=${1:-8100}; OLLAMA_URL=${OLLAMA_URL:-http://127.0.0.1:11434}; export OLLAMA_URL
cd "$(dirname "$0")"
curl -sf -m 3 "$OLLAMA_URL/api/version" >/dev/null || { echo "[..] Ollama 시작"; nohup ollama serve >/tmp/ollama.log 2>&1 & sleep 5; }
python -m uvicorn main:app --host 0.0.0.0 --port "$PORT" &
SRV=$!
for i in $(seq 3 3 240); do sleep 3; H=$(curl -sf -m 3 "http://127.0.0.1:$PORT/health" || true)
  echo "[$i s] $H" | cut -c1-160; echo "$H" | grep -q '"ready":true' && { echo "[ok] READY :$PORT"; break; }; done
wait $SRV
