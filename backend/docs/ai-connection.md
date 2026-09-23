# AI 서버 연결

리뷰 분석 브랜치의 연결 대상은 `GET /health`, `POST /reviews/summarize`, `POST /trends/summarize`다.
통계 요약은 AI 담당이 전달한 계약으로 구현했으며, 2026-09-17 최신 `develop`의 실제 AI 라우트와 대조했다.
AI 서버는 DB를 조회하지 않는다. 백엔드가 조회한 리뷰 또는 일별 통계를 JSON으로 보내고 결과를 화면 계약으로 변환한다.

## 접속 설정

AI 노트북이 서버1로 SSH 역터널을 열고, 서버1의 백엔드 컨테이너가 이를 호출한다.
확인된 접속 주소는 `AI_BASE_URL=http://172.17.0.1:8100`이며 Spring 설정 키는 `app.ai.base-url`이다.
2026-09-17 서버1에 `GatewayPorts clientspecified`와 AI 전용 공개키를 적용했다.
전용 키의 원격 수신 주소는 `permitlisten="172.17.0.1:8100"`으로 제한했다.
2026-09-17 역터널 수신과 호스트의 `/health` 응답을 확인했다. 컨테이너에서는 UFW 차단 로그가 확인되어,
`br-086eb7e9afae`의 백엔드 IP `172.18.0.3`에서 `172.17.0.1:8100/tcp`로만 접근하도록 허용했다.
이후 백엔드 컨테이너의 `/health`가 `ready: true`를 반환했다. 컨테이너 재생성으로 IP나 브리지가 바뀌면 이 규칙도 확인해야 한다.
`infra/compose.server.yaml`에서 기본 주소를 전달하고 서버의 `infra/.env`로 바꿀 수 있다.
동료 노트북의 Wi-Fi/유선 IP를 백엔드에 저장할 필요가 없다.

- `AI_BASE_URL`: 서버 Compose는 위 역터널 주소를 기본으로 전달한다. 로컬 실행에는 기본값이 없다.
- `AI_CONNECT_TIMEOUT`: 기본 3초.
- `AI_READ_TIMEOUT`: 기본 30초. AI 내부 재시도는 더 오래 걸릴 수 있지만 화면 요청은 끝까지 기다리지 않는다.
  HTTP 응답 대기 제한이며, 사전 health 조회(최대 5초)와 연결 시간은 별도다.
  HTTP 연결 종료가 AI 서버의 진행 중 모델 계산까지 취소한다는 보장은 없다.
- `/health` 조회는 별도로 5초의 응답 제한을 사용한다.
- 리뷰·추세 요약 모두 HTTP/1.1로 전송한다. 현재 AI 서버는 Java 클라이언트의 HTTP/2 전환 요청에서
  스트리밍 JSON 본문을 읽지 못해 `400 INVALID_REQUEST`를 반환할 수 있다.
  `RestClient.post().body(request)`가 JSON을 직렬화하며, 실제 HTTP 서버를 사용하는
  `AiSummaryTransportTest`에서 POST 본문·한글·snake_case 필드와 HTTP/2 전환 헤더 부재를 검증한다.
- 요청 전에 `/health`를 확인한다. 리뷰 요약은 `ready: true`일 때만 호출한다.
  통계 요약은 문장 틀 대체 결과가 있으므로 `ready: false`여도 호출한다.
  모델을 직접 시작하거나 자동 재시도하지 않는다.
- 현재 AI 코드에는 API 인증키 계약이 없다. 사용자 JWT를 AI 서버로 전달하지 않는다.

동료는 `ai/api/start.ps1` 또는 `ai/api/start.sh`로 서버를 실행한다.
인프라 담당은 `GatewayPorts clientspecified`와 원격 포트 포워딩 허용 여부를 확인하고,
AI 담당은 `-R 172.17.0.1:8100:127.0.0.1:8100` 형태로 서버1에 연결한다.
외부 인터페이스 대신 지정한 Docker 호스트 주소에 바인딩한다.
이 설정 자체가 방화벽을 대신하는 것은 아니며, 실제 주소·접근 범위는 인프라에서 확인한다.
백엔드 컨테이너 안에서 `/health`를 확인해야 전체 통신 경로를 검증할 수 있다.

```bash
docker exec backend curl --max-time 5 --silent --show-error http://172.17.0.1:8100/health
```

개발자 노트북에서 실행하는 백엔드는 서버1의 `172.17.0.1`에 직접 접속할 수 없다.
개발용 로컬 SSH 포워딩을 별도로 열었다면 `AI_BASE_URL`에 그 로컬 주소를 설정한다.
기존 PostgreSQL SSH 터널은 AI 포트를 전달하지 않는다.

## 리뷰 요약 연결

| 화면 API | AI 범위 | 선택 리뷰 |
|---|---|---|
| `GET /games/{gameId}/summaries/playtime-topics` | `ALL` 또는 `BAND` | 선택 구간의 도움됨 상위 최대 40건 |
| `GET /games/{gameId}/language-analysis/{languageCode}` | `LANGUAGE` | 선택 언어의 도움됨 상위 최대 20건 |

리뷰 최신 버전 선택 후 KST 수정일 기간과 구간/언어를 적용한다. 도움됨 동률은 리뷰 ID 내림차순이다.
플레이타임 경계는 기존 전체 기간 `band_stat`을 사용한다. 언어 `koreana`는 `korean`으로 묶는다.
플레이타임 요약은 최신 버전·기간·구간 적용 후 본문이 `null`, 빈 문자열 또는 Java `String.isBlank()`
기준 공백뿐인 리뷰를 DB에서 제외한 다음 도움됨 상위 최대 40건을 선택한다. 최신 본문이 공백이면 과거 버전으로 대체하지 않는다.
플레이타임의 `targetReviewCount`와 최소 표본 판정은 40건 제한 전의 공백 제외 유효 리뷰 수를 사용한다.
유효 리뷰가 30~39건이면 전부, 40건 이상이면 상위 40건을 전달한다. 별도 통계 건수와 언어별 선정 기준은 유지한다.
선택 대상이 30건 미만이면 AI 호출 없이 `SKIPPED`를 반환한다. 언어별 대표 리뷰는 도움됨 상위 최대 4건이다.
대상 전체 건수와 AI에 전달한 건수를 구분하며, AI와 동일하게 각 본문 앞 600 유니코드 문자만 보낸다.
DB 조회 트랜잭션은 AI 호출 전에 끝난다. DB 적재·스키마 수정·AI 서버 자동 기동은 하지 않는다.

요약 서버 오류, 시간 초과, 준비 미완료, 형식 오류, `clean: false`, 다른 게임/범위의 응답은
요약만 `UNAVAILABLE` / `AI_UNAVAILABLE`로 처리하고 HTTP 200으로 기존 조회 데이터를 반환한다.
요약 안내는 `AI 요약을 일시적으로 이용할 수 없습니다.`이며, 언어별 대표 리뷰와 조회 건수는 유지한다.
실패를 `COMPLETED`나 표본 부족(`SKIPPED`)으로 바꾸지 않는다. DB 장애와 코드 오류는 HTTP 500으로 유지한다.
AI 응답의 오류 본문·접속 주소는 사용자에게 노출하지 않는다. 검증된 요약에는 아래 Redis 캐시를 적용한다.

## 요약 캐시와 동일 요청 공유

반응 추세·플레이타임·언어별 요약에 적용한다. 프론트 캐시와 별개로 여러 사용자가 결과를 공유한다.
기획안 구조화·검색·비교에는 적용하지 않는다. API 경로·필드·상태 코드는 바꾸지 않는다.

- DB 조회와 표본 검사는 매번 수행한다. 캐시에는 AI 결과만 저장하므로 기간·대상 건수·대표 리뷰는 최신 조회 값이다.
- 키는 `thispatch:ai:summary:{version}:{reviews|trends}:{입력 SHA-256}`이다. 실제 해시는 Redis Cluster 슬롯용
  중괄호로 감싼다. 입력은 AI 접속 주소, 조회 시작·종료일, AI에 전달하는 전체 요청이다.
  리뷰 ID·본문·도움됨 수·구간·언어 또는 일별 통계·패치가 달라지면 다른 키가 되어 재생성한다.
  별도 적재 버전 컬럼이나 DB migration은 추가하지 않는다. 입력 해시가 요약에 필요한 데이터 버전을 구분한다.
  사용하지 않은 리뷰만 변경된 경우 같은 요약을 재사용하며, 이전 키는 TTL로 정리한다.
- 기본 TTL은 30분이고 조회 시 연장하지 않는다. AI 모델·프롬프트·규칙을 바꾸면 `AI_SUMMARY_CACHE_VERSION`을 올린다.
- 캐시 조회에서도 기존 AI 응답 검증을 적용한다. 실패·시간 초과·`clean: false`는 저장하지 않는다.
  반응 추세의 `used_llm: false` 문장 틀 결과는 기존처럼 반환하되 저장하지 않아 AI 복구 후 재생성한다.
- 캐시 적중 시 AI health와 생성 호출을 생략한다. 캐시가 손상됐거나 만료되면 재생성한다.
- 한 JVM 안에서는 같은 키의 `CompletableFuture`를 공유한다. 여러 JVM 사이에서는 Redis의
  `SET NX`와 유효기간 있는 잠금으로 동시에 생성하지 않도록 한다. 잠금 획득 직후 캐시를 다시 확인한다.
- 잠금 만료는 AI 읽기 제한 + 연결 제한 2회 + 20초다(기본 56초). 완료·실패 시 소유자 토큰을 확인해 해제한다.
  결과 저장도 소유자 확인 후 수행하므로 만료된 이전 작업이 새 작업의 결과를 덮어쓰지 않는다.
  프로세스 장애·긴 정지·Redis 장애까지 포함한 추론의 정확히 한 번 실행을 보장하는 방식은 아니다.
- 진행 중 작업 대기는 기본 40초로 제한하고 초과 시 기존 `AI_UNAVAILABLE`로 처리한다. 대기 중인 요청이
  취소돼도 공유 작업은 취소하지 않는다. HTTP 연결이 끝나도 AI 내부 추론이 계속될 수 있다는 기존 제한은 같다.
- Redis 읽기·잠금 장애 시 AI를 직접 호출하고 같은 JVM의 중복 방지는 유지한다. 쓰기 장애는 성공 응답을 막지 않는다.
  Redis 장애 중에는 서버 간 중복 방지가 보장되지 않는다. 캐시 오류 로그에 본문·키·접속 비밀값은 기록하지 않는다.

설정: `AI_SUMMARY_CACHE_ENABLED`(기본 `true`), `AI_SUMMARY_CACHE_TTL`(`30m`),
`AI_SUMMARY_CACHE_VERSION`(`v1`), `AI_SUMMARY_WAIT_TIMEOUT`(`40s`).
캐시를 끄면 Redis 저장·분산 잠금은 사용하지 않으며 JVM 내 진행 중 요청 공유는 유지한다.

### Redis 배포와 검증

`infra/compose.server.yaml`은 Redis를 백엔드 전용 내부 네트워크에 띄우고 호스트 포트를 공개하지 않는다.
백엔드는 `redis:6379`, DB 0을 사용한다. Jenkins와 수동 배포 스크립트는 Redis health 확인 후 백엔드를 기동한다.
Redis는 최대 데이터 메모리 128MB, 컨테이너 256MB이며 로그인 코드를 임의로 제거하지 않도록 `noeviction`이다.
메모리가 차면 캐시 저장이 실패할 수 있다. 영속 저장은 사용하지 않아 Redis 재시작 시 요약은 다시 생성되고,
진행 중 Steam 로그인 코드는 사라지므로 해당 사용자는 로그인을 다시 시작해야 한다.

`AiSummaryCacheTest`는 Redis 장애·JVM 동시 요청·실패 후 복구·대기 시간 초과를 검증한다.
`AiSummaryCacheIntegrationTest`는 실제 Redis에서 다중 인스턴스 공유, TTL, 입력 변경, 손상된 값,
실패 비저장, 이전 소유자의 저장·잠금 해제 방지를 검증한다.
통합 검증은 `REDIS_INTEGRATION_TEST=true`, `REDIS_TEST_HOST`, `REDIS_TEST_PORT`로 실행하고 DB 15를 사용한다.
Jenkins는 별도 테스트 Redis 컨테이너를 만들고 검증 후 제거한다. 운영 Redis 데이터는 테스트하지 않는다.

## 반응 추세 요약 연결

반응 추세 요약은 화면 명세상 일별 통계·작성/수정 채널·패치 정보를 요약한다.
화면 API `GET /games/{gameId}/summaries/reaction-trends`에서 `POST /trends/summarize`를 호출한다.
요청한 시작일·종료일을 모두 포함하며, 허용 기간은 1~400일이다. 401일 이상은 조회 전에 `400 INVALID_REQUEST`다.
기간 내 리뷰 합계가 30건 미만이면 기존 화면 계약대로 AI 호출 없이 `SKIPPED`다.
AI 요청은 패치 최대 50개까지 지원한다. 선택 기간의 패치가 51개 이상이면 요약만 `UNAVAILABLE`로 반환하며,
패치를 일부 버려 요약 범위를 바꾸거나 AI에 규격을 넘는 요청을 보내지 않는다.

전달 필드:

| AI 필드 | 백엔드 조회 값 |
|---|---|
| `appid` | 요청한 게임 ID |
| `daily[].date` | KST 일별 통계 날짜 (`YYYY-MM-DD`) |
| `daily[].reviews`, `positive` | 리뷰 수, 리뷰 수 - 부정 수 (부정 수가 null이면 채널 긍정 수 합계) |
| `first_reviews`, `first_positive` | 신규 리뷰 수·긍정 수 |
| `edited_reviews`, `edited_positive` | 수정 리뷰 수·긍정 수 |
| `patches[].date`, `title`, `gid` | 선택 기간 패치 공지의 KST 게시일·제목·문자열 ID |
| `window_days`, `use_llm` | `7`, `true` |

없는 날짜는 0건으로 포함한다. 선택 필드인 `game`, 패치 `version`은 보내지 않는다.
선택 기간 바깥 날짜를 덧붙이지 않으므로 패치 전후 비교도 전달된 날짜 안에서만 계산된다.
기간 합계·이동·패치 효과의 문장 생성은 AI 서버에서 수행하며, DB 조회 트랜잭션은 AI 호출 전에 끝난다.

정상 응답의 `summary`와 `caveats`를 줄바꿈으로 합쳐 기존 화면의 `summary.text`에 반환한다.
화면 필드는 추가하지 않으며 `facts`, `patch_effects`를 별도 차트로 노출하지 않는다.
`used_llm: false, clean: false`는 AI 담당이 명시한 문장 틀 대체 결과이므로 `COMPLETED`로 사용한다.
`used_llm: true, clean: false`는 검사를 통과하지 못한 모델 문장으로 보고 이용 불가로 처리한다.
연결 실패·시간 초과·형식 오류는 `UNAVAILABLE` / `AI_UNAVAILABLE`다.
AI가 통계 입력을 거절한 `400`/`422`는 백엔드 입력 생성 오류이므로 공통 `500`이며,
잘못된 통계를 0건이나 접속 장애로 바꾸지 않는다. AI 오류 본문은 화면에 노출하지 않는다.

실제 추론·문장 품질 검증에는 새 라우트가 배포된 AI 서버와 확정된 접속 경로가 필요하다.

### 실제 연결 검증 (2026-09-17)

백엔드 컨테이너에서 합성 데이터로 두 AI 라우트를 직접 호출했다. DB 변경은 없었다.

- `/trends/summarize`: 일별 통계 1건, `use_llm: true`로 호출. `used_llm: true`, `clean: true`, `elapsed_ms: 5694`와 요약·각주가 반환됐다.
- `/reviews/summarize`: 합성 리뷰 3건으로 호출. `clean: true`, `review_count: 3`, `elapsed_ms: 3998`과 입력에 포함된 근거 ID가 반환됐다.
- 두 응답 모두 백엔드의 30초 제한 안에 도착했다. 이 결과는 연결·최소 요청 검증이며 대량 입력 성능이나 문장 품질 전반을 보장하지 않는다.
- 최초 연결 확인에서는 실행 중인 컨테이너의 `AI_BASE_URL`이 비어 있었다. 이후 아래 수동 배포에서 주소를 적용했다.

### 리뷰 API 배포 확인 (2026-09-17)

- 브랜치: `feat/#S15P21A202-167/review-analysis`. 당시 커밋 전인 작업 파일을 빌드해 수동 배포했다. 이 수동 배포 시점에는 Git 병합·푸시 및 Jenkins 배포를 실행하지 않았다.
- 이미지: `thispatch/backend:review167-20260917-01`. 이전 실행 이미지에 새 실행 JAR만 올렸고 이전 이미지는 `thispatch/backend:before-review167-20260917-01`로 보존했다.
- 프론트: `/var/www/thispatch/releases/review167-20260917-01`, 기존 `current` 링크를 전환했다. 이전 릴리스는 `/var/www/thispatch/releases/79`다.
- 배포 기록·복구 정보: 서버 `/home/ubuntu/thispatch-review-releases/review167-20260917-01/`. 이 폴더의 설정 백업에는 비밀값이 있으므로 외부에 공유하지 않는다.
- 서버 `infra/.env`에 `AI_BASE_URL=http://172.17.0.1:8100`, `SPRING_FLYWAY_ENABLED=false`를 적용했다. 배포용 Compose override에서도 Flyway와 SQL 초기화를 끄고 Hibernate는 `validate`로 유지했다.
- 후보 백엔드는 PostgreSQL 연결 옵션으로 읽기 전용을 강제해 먼저 검증했다. 실제 전환 후 `backend`가 `healthy`이고 컨테이너에서 AI `/health`의 `ready: true`가 확인됐다.
- 공개 주소에서 인증된 GET으로 리뷰·통계 관련 API를 검증했다. 서비스 DB의 `recent_review`, `daily_stat`, `language`가 모두 비어 있으므로 목록은 0건, 요약은 `SKIPPED`, 미등록 한국어 상세는 계약대로 `400`이었다. 데이터 적재는 수행하지 않았다.
- 실제 배포된 리뷰·반응 추세·플레이타임·언어별 화면 4개가 정상 조회와 빈 상태를 표시했다. 브라우저 검증에서는 GET만 허용했다.
- DB의 Flyway 이력 최대 순번은 배포 전후 모두 8이었다. 이번 배포에서는 DB 데이터·스키마를 변경하지 않았다.
- 다음 Jenkins 배포는 `develop`을 기준으로 하므로, 리뷰 브랜치 변경을 반영하기 전에는 이번 수동 배포 코드가 교체될 수 있다. 실제 적재 데이터 기반 AI 요약 검증도 남아 있다.

## 프론트 연결 확인

- 대표 리뷰 API의 `data`는 배열이 아니라 `{ meta, items }`다. 프론트 도메인 함수가 `items`를 꺼내 화면에 전달한다.
- 표본 충족 필드명은 `isSufficientSample`로 통일한다. 언어별 대표 리뷰는 최대 4건을 표시한다.
- AI의 `UNAVAILABLE`는 표본 부족인 `SKIPPED`와 구분해 안내하고, 통계·리뷰는 유지한다.
- `availablePeriod`, 리뷰 플레이타임, 요약 `selection`의 null을 처리한다. 번역이 없는 리뷰는 원문을 표시한다.
- AI를 포함하는 화면 요청은 45초를 기다린다. 일반 조회의 기본 제한 시간은 10초를 유지한다.
- `frontend/e2e/review-api.spec.ts`는 API 응답을 가정한 브라우저 통합 테스트다. 실제 DB·AI까지 연결하는 종단 간 검증과 구분한다. 실행 시 `VITE_ENABLE_MOCKS=false`, `VITE_API_BASE_URL=/api`로 프론트를 시작한다.
