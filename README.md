# ThisPatch

스팀 게임의 리뷰와 패치노트를 분산 처리해, 게임사가 다음 패치를 기획할 때
참고할 지표를 제공하는 서비스입니다.

- 개발 기간: 2026.08.24 ~ 2026.09.29
- 팀 구성: 6명 (Backend 2 · Frontend 2 · AI 1 · Infra 1)

## 해결하려는 문제

패치에 대한 유저 반응은 스토어 긍정률에 잡히지 않습니다. 긍정률이 누적값이라
개별 패치의 영향이 묻히기 때문입니다. 그래서 패치 전후로 창을 잘라 반응을
분해하고, 플레이타임 구간·언어·토픽별로 나눠 봅니다.

예측이나 처방은 하지 않습니다. 진단·비교·근거 제시까지만 하고 결정은
개발사가 합니다.

배포 서버는 프로젝트 종료와 함께 반납되어 현재는 접속할 수 없습니다.

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

수집과 집계는 노트북 5대 하둡 클러스터에서, 서빙은 EC2에서 합니다. 원본
전량은 HDFS에 두고 화면이 쓰는 집계만 PostgreSQL로 내립니다.

```text
스팀 API
   │   마스터가 게임 목록을 조각으로 나눠 RabbitMQ 에 넣고
   │   워커 4대가 조각을 꺼내 각자 자기 IP 로 호출
   ▼
HDFS /review_landing      받은 JSON.gz 원본. 지우지 않는 안전망
   │   Spark 변환 (타입 고정 · null 계수)
   ▼
HDFS /review_raw          base(통합본) + delta/dt=날짜. 주 1회 compaction
   │   Spark 집계 (YARN · 1.7억 행)
   ▼
드라이버 한 곳이 SSH 터널로 한 트랜잭션 TRUNCATE + INSERT
   ▼
EC2 서버1   PostgreSQL 17 + pgvector · 백엔드 · nginx · Jenkins
EC2 서버2   HDFS · DB · NameNode 백업 전용
```

평일 09:05 systemd 타이머가 `infra/scripts/24-pipeline.sh` 하나를 깨웁니다.
카탈로그 → 수집 → 재시도 → 변환 → 공지 → compaction(월) → 적재 → 백업
순서가 그 파일 한 곳에 있습니다.

**분산한 이유는 대역폭이 아니라 IP 입니다.** 한 IP로 스팀에 몰아치면 429가
아니라 403으로 차단됩니다. 그래서 호출이 반드시 워커 각자의 IP에서 나가야
하고, 마스터가 대신 받아 나눠주는 구조면 나눈 의미가 없습니다.

**증분 수집에 워터마크 테이블을 두지 않았습니다.** `filter=updated`로 받다가
페이지의 가장 오래된 리뷰가 「지난 성공 수집의 시작 시각 − 1시간」보다 이르면
그 게임을 멈춥니다. 멈추는 조건이 게임마다 다를 이유가 없어 시각 하나면 되고,
그 값은 Spring Batch가 배치 DB에 이미 들고 있습니다. 하루 수집량이 전량의
약 0.03%로 줄었습니다.

**중복 판단 키는 `(recommendationid, updated_ts)` 입니다.** 「리뷰당 최신만
남기기」를 쓰면 9/1 작성 · 9/5 수정 · 9/9 재수정된 리뷰에서 9/5를 영영
잃습니다. 그러면 daily_stat의 「그날 수정된 리뷰 수」를 다시 만들 수 없습니다.
리뷰의 13.7%가 수정되므로 무시할 수 없고, 판본 보존 비용은 연 8.7GB입니다.

**패치 판정은 LLM이 아니라 규칙입니다.** 공지 197만 건을 전부 LLM에 넣으면
비용과 시간이 감당이 안 되고, 규칙의 재현율이 이미 98%를 넘습니다. 규칙이
걸러낸 패치만 AI가 정규화합니다.

**리뷰 임베딩 벡터는 저장하지 않습니다.** 1.7억 건을 전부 남기면 수백 GB라,
임베딩해서 토픽만 뽑고 벡터는 버립니다. 패치노트 쪽 벡터만 pgvector에 남겨
유사 사례 검색에 씁니다.

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
AWS EC2 · WSL2 · systemd · Docker Compose · Nginx · Jenkins (Configuration as Code) · Let's Encrypt · PostgreSQL 17 + pgvector · Mattermost 웹훅

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
| 클러스터 | 노트북 5대 (마스터 1 · 워커 4) · HDFS 4.92TB · 복제 3 |
| 스팀 카탈로그 | 게임 약 18.5만 개 (리뷰가 있는 수집 대상 약 11.7만 개) |
| 리뷰 원본 | 약 1억 7,100만 건 (2010.10 ~ 2026.09) |
| 최초 수집 파일 | 약 215만 개 (JSON.gz) |
| 공지 | 197만 건 → 패치로 판정된 것 약 73.6만 건 |
| 수집 속도 | 425 → 2,540 리뷰/초 (병목 제거로 약 6배) |
| 1.7억 행 집계 | 5시간 40분 실패 반복 → 52분 완주 |
| 매일 증분 | 전량의 약 0.03% |
| 서비스 DB | daily_stat 약 1,833만 행 외 표 10개 |
| 수정 리뷰 비율 | 13.7% |
| 패치 판정 재현율 | 98% 이상 |
