#!/usr/bin/env bash
# AI 노드 일일 배치 (WSL 에서 실행). HDFS get → 임베딩·토픽·Qwen → HDFS put.
#
#   DT=2026-09-12 bash ai/batch/run_daily.sh            # 그날 증분
#   DT=2026-09-12 INIT=1 bash ai/batch/run_daily.sh     # 초기 1회: review_raw/base 전체 분류
#
# 환경 변수
#   AI_WORK_DIR   로컬 작업 루트 (기본 ~/ai_work). HDFS 와 같은 하위 구조
#   PYTHON        배치를 돌릴 파이썬. WSL 이면 ~/ai-venv/bin/python, Windows 개발기라면
#                 /mnt/c/Users/<me>/miniforge3/envs/py313/python.exe (WSL 에서 Windows 파이썬 호출 가능)
#   OLLAMA_URL    Qwen 서버 (기본 http://127.0.0.1:11434 — 미러링 네트워크라 WSL 에서도 Windows Ollama 에 붙음)
#   QWEN_SECONDS  Qwen 백필 시간 예산 (기본 4시간). Spark → 임베딩·토픽 뒤 남는 시간만큼
#   HDFS          hdfs://thispatch-master:9000 (common/HdfsPaths.java 와 동일)
set -euo pipefail

DT="${DT:-$(TZ=Asia/Seoul date +%F)}"
AI_WORK_DIR="${AI_WORK_DIR:-$HOME/ai_work}"
PYTHON="${PYTHON:-python3}"
QWEN_SECONDS="${QWEN_SECONDS:-14400}"
HDFS="${HDFS:-hdfs://thispatch-master:9000}"
HERE="$(cd "$(dirname "$0")" && pwd)"
export AI_WORK_DIR OLLAMA_URL="${OLLAMA_URL:-http://127.0.0.1:11434}"

log() { echo "[$(date +%T)] $*"; }
need_success() { hdfs dfs -test -e "$1/_SUCCESS" || { log "입력 준비 안 됨: $1/_SUCCESS 없음"; exit 2; }; }

# 1. 입력 내려받기 (같은 dt 재실행 대비 로컬 폴더 비움)
NEWS_SRC="$HDFS/news_raw/dt=$DT"
need_success "$NEWS_SRC"
rm -rf "$AI_WORK_DIR/in/news_raw/dt=$DT"; mkdir -p "$AI_WORK_DIR/in/news_raw"
hdfs dfs -get "$NEWS_SRC" "$AI_WORK_DIR/in/news_raw/"
log "news_raw 내려받음"

if [[ "${INIT:-0}" == "1" ]]; then
  REV_SRC="$HDFS/review_raw/base"; REV_LOCAL="$AI_WORK_DIR/in/review_raw/base"; REV_FLAG="--base"
else
  REV_SRC="$HDFS/review_raw/delta/dt=$DT"; REV_LOCAL="$AI_WORK_DIR/in/review_raw/delta/dt=$DT"; REV_FLAG=""
fi
need_success "$REV_SRC"
rm -rf "$REV_LOCAL"; mkdir -p "$(dirname "$REV_LOCAL")"
hdfs dfs -get "$REV_SRC" "$(dirname "$REV_LOCAL")/"
log "review_raw 내려받음"

# 2. 처리 (빠른 것 먼저: 임베딩·토픽은 분 단위, Qwen 은 시간 단위)
"$PYTHON" "$HERE/embed_chunks.py" --dt "$DT"
"$PYTHON" "$HERE/classify_reviews.py" --dt "$DT" $REV_FLAG

# 3. 임베딩·토픽 먼저 올린다 → Loader 가 바로 적재 가능
hdfs dfs -mkdir -p "$HDFS/embeddings/patch_chunk" "$HDFS/embeddings/patch_change" "$HDFS/review_topic"
for name in embeddings/patch_chunk embeddings/patch_change review_topic; do
  hdfs dfs -rm -r -f "$HDFS/$name/dt=$DT" >/dev/null
  hdfs dfs -put "$AI_WORK_DIR/out/$name/dt=$DT" "$HDFS/$name/"
done
log "임베딩·토픽 put 완료"

# 4. Qwen 백필 (시간 예산 안에서) → patch_chunk·patch_change 다시 put
"$PYTHON" "$HERE/qwen_backfill.py" --dt "$DT" --max-seconds "$QWEN_SECONDS" --include-skipped
for name in embeddings/patch_chunk embeddings/patch_change; do
  hdfs dfs -rm -r -f "$HDFS/$name/dt=$DT" >/dev/null
  hdfs dfs -put "$AI_WORK_DIR/out/$name/dt=$DT" "$HDFS/$name/"
done
log "Qwen 반영 put 완료. 끝"
