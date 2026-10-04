# ThisPatch

스팀 게임의 리뷰와 패치노트를 분산 처리해, 게임사가 다음 패치를 기획할 때
참고할 지표를 제공하는 서비스입니다. SSAFY 빅데이터 분산처리 관통 프로젝트로
진행했습니다.

- 개발 기간: 2026.08.24 ~ 2026.09.29
- 팀명: A202

## 해결하려는 문제

패치에 대한 유저 반응은 스토어 긍정률에 잡히지 않습니다. 긍정률이 누적값이라
개별 패치의 영향이 묻히기 때문입니다. 그래서 패치 전후로 창을 잘라 반응을
분해하고, 플레이타임 구간·언어·토픽별로 나눠 봅니다.

예측이나 처방은 하지 않습니다. 진단·비교·근거 제시까지만 하고 결정은
개발사가 합니다.

## 배포

- https://j15a202.p.ssafy.io

## 팀원

| 이름 | 역할 |
| --- | --- |
| 김경민 | Infra, CI/CD, 수집 파이프라인 |
| 김택림 | Backend |
| 유성현 | Backend, Spark |
| 강진우 | AI |
| 황용진 | Frontend |
| 권희철 | Frontend |

## 주요 기능

- 일별 리뷰 수·긍정률 추세와 패치 시점 네비게이션
- 패치 전후 7일 반응 비교
- 플레이타임 4구간별 긍정률과 불만 토픽 구성
- 언어별 반응 분포와 대표 리뷰
- 기획안 입력 시 유사 패치 사례 검색 (벡터 유사도)
- 대표 리뷰 AI 요약

## 서비스 아키텍처

수집은 노트북 5대 하둡 클러스터에서, 서빙은 EC2에서 합니다. 원본 전량은
HDFS에 두고 화면이 쓰는 집계만 PostgreSQL로 내립니다.

```text
Steam API (리뷰 · 공지 · 카탈로그)
      │
      ▼
collector  (Spring Batch 원격 파티셔닝 · 워커 5대)
      │  RabbitMQ 로 파티션 분배
      ▼
HDFS  /review_raw  /news_raw        원본 1.67억 건 · Parquet 약 26GB
      │
      ├──▶ spark   일별·패치·구간·언어 집계 → PostgreSQL 적재
      │
      └──▶ ai      EmbeddingGemma 512 임베딩 · 리뷰 토픽 분류
                   Qwen3.5-9B 패치 변경점 추출
      │
      ▼
PostgreSQL 17 + pgvector        집계 · 화면용 리뷰 · 패치 벡터
      │
      ▼
backend (Spring Boot)  ──▶  nginx  ──▶  frontend (React)
```

- 리뷰는 매일 전량이 아니라 증분으로 받습니다. `filter=updated`로 수정일
  내림차순 순회하며 게임별 워터마크를 둡니다. 하루 증분이 전체의 0.03%라
  수집이 1~2분에 끝납니다.
- 집계는 매일 전량 재계산합니다. 15년 전 리뷰가 오늘 수정될 수 있어
  증분만으로는 과거 숫자를 고칠 수 없습니다.
- 패치 판정은 LLM이 아니라 규칙입니다. 공지 160만 건을 전부 LLM에 넣으면
  비용과 시간이 감당이 안 되고, 재현율이 이미 98%를 넘습니다. 규칙이
  걸러낸 패치만 AI가 정규화합니다.
- 리뷰 임베딩 벡터는 저장하지 않습니다. 768차원 × 1.67억 건이면 600GB라,
  임베딩해서 토픽만 뽑고 벡터는 버립니다. 패치노트 75만 건의 벡터만
  pgvector에 남겨 유사 사례 검색에 씁니다.

## 기술 스택

**Frontend** (`frontend`)
React 19 · TypeScript · Vite · Zustand · TanStack Query · Tailwind CSS 4 · Recharts · Playwright

**Backend** (`backend`, `common`)
Java 17 · Spring Boot 3.5 · Spring Batch · JPA · Flyway · Redis

**분산처리** (`collector`, `spark`, `catalog`)
Hadoop 3.5 · Spark 4.2 · RabbitMQ · Spring Batch 원격 파티셔닝

**AI** (`ai`)
FastAPI · EmbeddingGemma 512 · Qwen3.5-9B · scikit-learn · sentence-transformers

**Infra** (`infra`)
Docker Compose · Nginx · Jenkins (Configuration as Code) · Let's Encrypt · PostgreSQL 17 + pgvector · UFW

## 인프라 구성

| 역할 | 구성 |
| --- | --- |
| 하둡 클러스터 | 노트북 5대 (마스터 1 · 워커 4). WSL2 Ubuntu |
| AI 노드 | 노트북 1대 (RTX 4070 8GB). 클러스터 노드가 아닌 HDFS 클라이언트 |
| 서비스 서버 | EC2. nginx · backend · Jenkins · PostgreSQL |
| 백업 서버 | EC2. HDFS 데이터와 NameNode 메타데이터 보관 |

Jenkins는 설정 마법사를 쓰지 않고 JCasC(`infra/jenkins/jenkins.yaml`)로
관리자·보안·잡 정의를 코드로 넣습니다. `develop`에 push하면 테스트 →
이미지 빌드 → 배포 → 검증까지 자동으로 돕니다.

## 프로젝트 구조

```text
frontend/     React/Vite 앱
backend/      Spring Boot API
common/       모듈 공통 규약 (HDFS 경로 · 시각 · 스키마)
collector/    Steam API 수집 배치 (노트북 클러스터)
spark/        집계 · compaction (노트북 클러스터)
catalog/      게임 카탈로그 수집
ai/           임베딩 · 토픽 분류 · 패치 변경점 추출 (GPU 노트북)
infra/        클러스터 구축 스크립트 · 서버 배포 · Nginx · Jenkins
Jenkinsfile   배포 파이프라인
```

## 실행 방법

루트에서 Gradle 멀티모듈로 빌드합니다. `backend/` 안에서 `./gradlew`를
치면 안 됩니다.

| 대상 | 실행 절차 |
| --- | --- |
| backend | `cd backend && cp .env.example .env && docker compose up -d --wait` 후 `./gradlew bootRun --args='--spring.profiles.active=dev'` ([상세](backend/README.md)) |
| frontend | `cd frontend && npm ci && npm run dev` ([상세](frontend/README.md)) |
| collector | `./gradlew :collector:bootJar` ([상세](collector/README.md)) |
| spark | `./gradlew :spark:jar` 후 `spark-submit`으로 `spark/build/libs/thispatch-spark.jar` 실행 ([상세](spark/README.md)) |
| ai | `cd ai && pip install -r requirements.txt && cd api && uvicorn main:app --port 8100` ([상세](ai/README.md)) |

백엔드 테스트는 실제 PostgreSQL에 붙어 Flyway 마이그레이션을 검증하므로,
`./gradlew build` 전에 `backend/compose.yaml`의 DB를 먼저 띄워야 합니다.

서버 배포는 `infra/scripts/`의 번호 순서대로 진행합니다. 클러스터 구축이
01~12, 서버 구축과 배포가 13~25입니다.

```bash
cd infra
docker compose -f compose.server.yaml up -d
```

## 측정 기록

설계 판단의 근거가 된 실측값입니다.

| 항목 | 값 |
| --- | --- |
| 수집 대상 | 게임 8,409개 (리뷰 1,000건 이상) |
| 전체 리뷰 | 1억 6,715만 건 |
| 수집 처리율 | 약 3,000 리뷰/초에서 포화 (5시간 연속, 429 발생 0회) |
| 최초 전량 수집 | 약 15.6시간 |
| 매일 증분 | 1~2분 (하루 약 4.8만 건 = 전체의 0.03%) |
| HDFS 저장 | Parquet Snappy 약 26GB |
| Spark 집계 | 8코어에서 5분 (16코어는 오히려 퇴보 — P/E 코어 혼합) |
| 수정 리뷰 비율 | 13.68% |
| 수정 리뷰 긍정률 | 첫 작성 대비 −20.3%p (첫작성 91.1% vs 수정 70.8%) |
| 패치 판정 재현율 | 98% 이상 (공지 2,157건 기준) |
