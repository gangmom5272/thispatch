# ai/ — AI 노드 (임베딩 · 리뷰 토픽 · Qwen 사전 분석 · 요청 API)

GPU 노트북 1대(RTX 4070 8GB)에서 돈다. 클러스터 노드가 아니고 HDFS 클라이언트로만 붙는다.
파일 계약은 [CONTRACT.md](CONTRACT.md), 경로 규약은 `common/HdfsPaths.java`.

## 구성

| 경로 | 역할 | 실행 시점 |
|---|---|---|
| `batch/embed_chunks.py` | 공지 → 청크 분리 → 규칙 슬롯 → EmbeddingGemma 512 → `patch_chunk`·`patch_change` Parquet | 매일 |
| `batch/classify_reviews.py` | 리뷰 → 임베딩 → 로지스틱 회귀 5개 → `review_topic` Parquet (다중 라벨). 영어·한국어 | 매일 (초기 1회 `--base`) |
| `batch/qwen_backfill.py` | 변경 문장 청크 → Qwen3.5-9B → 대상·속성·조건으로 `patch_change` 덮어쓰기. 최근 공지 우선, 중복 문장 1회, 시간 예산 | 매일 남는 시간 + 백필 |
| `batch/run_daily.sh` | WSL: `hdfs dfs -get` → 위 셋 → `hdfs dfs -put` | 매일 (Spark 뒤) |
| `api/main.py`, `api/compare.py`, `api/summarize.py` | FastAPI. `POST /plan/structure`(기획안 구조화, Qwen 1회) · `POST /plan/restate`(수정 슬롯 → 재진술, 문장 틀) · `POST /embed/query`(질의 벡터) · `POST /cases/cards`(화면 04 카드 1줄 + 결과군 패턴, 문장 틀) · `POST /cases/compare`(화면 05 공통점·차이점, 문장 틀 + Qwen 옵션) · `POST /reviews/summarize`(화면 02 AI 대표 반응 요약: 제목·요약·반복 표현·근거 2건, Qwen 1회) · `POST /trends/summarize`(화면 01 반응 추세 요약: 일별 집계·패치 시점 → 수치 계산 + 문장, Qwen 옵션). 오류 응답은 백엔드 공통 형식. AI 서버는 DB 를 보지 않고 백엔드가 사례 데이터를 본문에 담아 보낸다 | 상시, 백엔드가 호출 |
| `batch/chunking.py`, `rules.py`, `qwen_prompt.py` | 청크 분리(동료 steam_pipeline 이식), 규칙, 프롬프트·스키마 | 라이브러리 |
| `batch/dev_make_*_input.py` | 개발용 입력 생성(PoC JSON → Parquet). HDFS 준비 후 불필요 | 개발 |
| `batch/train_topic_clf.py` | 사람 라벨(0908_return) + LLM 라벨 → 토픽 분류기 학습·문턱값 선정 | 라벨 갱신 시 |
| `models/` | 분류기 `logreg-gemma512-v2.joblib` (git 밖, `train_topic_clf.py` 산출) | |

## 패치 판정 입력

판정은 Spark `PatchClassifier` 하나로 통일하며 `NewsToParquet`가 공통 함수를 호출해
`/news_raw`에 `is_patch`, `patch_reason`을 저장한다. 판정·패치 리뷰 집계에 AI는 관여하지 않는다.
`0:unjudged`는 판정 전 전용 값이다. 기존 Python 개발 도구의 PoC 판정은 운영 패치 집계에 사용하지 않는다.
상세 계약은 [CONTRACT.md 부록 A](CONTRACT.md#부록-a-패치-판정-정본과-전달-상태)를 따른다.
아래 임베딩 개발 실행 예시는 별도의 판정 필드가 있는 호환 입력을 전제로 한다.

## 실행 (개발기, Windows py313)

```bash
export AI_WORK_DIR=/c/Users/<me>/Desktop/dev/special/ai_work   # HDFS 와 같은 하위 구조의 로컬 폴더
PY=/c/Users/<me>/miniforge3/envs/py313/python.exe
$PY batch/dev_make_news_input.py   --src ../0904/poc --dt 2026-09-11
$PY batch/dev_make_review_input.py --src ../0904/poc --dt 2026-09-11
$PY batch/embed_chunks.py     --dt 2026-09-11
$PY batch/classify_reviews.py --dt 2026-09-11
$PY batch/qwen_backfill.py    --dt 2026-09-11 --limit 60          # 테스트. 전체는 --max-seconds 로 예산
cd api && $PY -m uvicorn main:app --port 8100     # 또는 .\start.ps1 (Ollama 확인 → 서버 → 워밍업 → ready 표시)
```

AI 서버는 **사용자가 직접 켠다**(자동 기동 없음). `api/start.ps1`(Windows) 또는 `bash api/start.sh`(WSL) 를 실행하면 Ollama 를 확인하고 서버를 띄운 뒤 `/health` 가 `ready:true` 가 될 때까지 진행을 보여 준다. 임베딩 모델(약 35초)·Qwen(약 10초)은 기동 시 백그라운드로 미리 올린다. 백엔드는 `GET /health` 의 `ready` 가 true 일 때부터 호출한다.

### 백엔드가 호출하는 경로 — SSH 역터널

EC2 에서 교육장 노트북 대역으로 나가는 라우팅이 없어 포트를 열어도 닿지 않는다(9/10 인프라 실측).
반대 방향은 열려 있으므로 **노트북이 서버1 로 붙어 8100 을 거꾸로 넘긴다**.

```bash
.pi	unnel.ps1          # Windows. 또는 bash api/tunnel.sh (WSL)
```

백엔드는 `http://172.17.0.1:8100` 으로 부른다. 노트북 IP 가 바뀌어도 설정을 고칠 필요가 없다.
도커 브리지 주소에 묶는 이유는 백엔드가 컨테이너 안에서 돌기 때문이고,
그러려면 서버1 `sshd_config` 에 `GatewayPorts clientspecified` 가 있어야 한다(인프라 협의 중).
확인은 서버1 에서 `curl http://172.17.0.1:8100/health`. 연동 절차는 [docs/backend-connection.md](docs/backend-connection.md).

노트북이 꺼지거나 절전으로 들어가면 AI 기능이 멈춘다. 서버에 GPU 가 없어 생기는 구조적 제약이다.

운영(WSL)은 `pip install -r requirements.txt`(torch 는 CUDA 빌드 별도) 후 `DT=… bash batch/run_daily.sh`.
Ollama 는 Windows 에 그대로 두고 `OLLAMA_URL` 로 붙는다(미러링 네트워크).

## 실측 (2026-09-11, 이 노트북)

| 작업 | 처리량 | 비고 |
|---|---|---|
| 청크 임베딩 (HF bf16, batch 64) | 220~270청크/초 | 0904 공지 443건 → 청크 10,428, 임베딩 7,074(변경점 있는 것만) 32초 |
| 리뷰 토픽 분류 | 198리뷰/초 | 8,351건 42초. 토픽 붙은 리뷰 38% |
| Qwen 사전 분석 (Ollama Q4_K_M, 요청당 2,500자 예산) | 1.2~3.6초/청크 | 시간은 문장 길이·뽑히는 변경점 수에 비례. Top50 실측 target 100%·attribute 94%, needs_review 4%(근거 검사 완화 후) |
| 기획안 구조화 API | 3문장 4.9초 | |
| 사례 비교 API (`/cases/compare`) | 문장 틀 0ms, Qwen 해석 13초 | 사례 변경점 40개 입력 기준 |
| 카드·재진술 API | 밀리초 | LLM 없음 |
| 리뷰 요약 API (`/reviews/summarize`) | 8건(평균 7,200자) 10초 | 짧은 리뷰면 더 빠름. 검증 루프 1회 통과 |
| 반응 추세 요약 (`/trends/summarize`) | 문장 틀 4ms(300일) | 수치 계산은 전부 서버에서. `use_llm=true` 면 Qwen 1회가 더 붙는다 |
| 질의 임베딩 API | 밀리초 | |

## 아직 안 정해진 것

CONTRACT.md 4절 참고: `/news_raw` 형식, 청크 분리 위치(AI 노드 제안), `target`·`attribute` 컬럼 채택, 초기 리뷰 분류 범위.
