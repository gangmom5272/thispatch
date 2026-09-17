# 패치 AI 서버 호출 연결

에픽 168의 `AiPatchClient`는 `ai/api/main.py`의 HTTP 계약을 사용한다.
기획안 구조화·유사 사례 검색 서비스가 이 클라이언트로 AI를 호출한다. 외부 API 계약과 검색 정책은 [patch API](api/patch.md)를 따른다.

## 설정

- `AI_BASE_URL`: 서버1 역터널 주소 `http://172.17.0.1:8100`. 서버 Compose 기본값이며 환경 변수로 변경한다. Spring 키는 `app.ai.base-url`이다.
- `AI_CONNECT_TIMEOUT`: 기본 3초.
- `AI_READ_TIMEOUT`: 기본 30초. 모델 내부 재시도를 끝까지 기다리지 않는다. 사전 health 조회·연결 시간은 별도다.
- 준비 상태는 별도 5초 제한의 `GET /health`로 확인한다. `ready: true`일 때만 처리 API를 호출한다.
- 주소 미설정 상태에서는 AI 호출만 실패한다. localhost로 대신 접속하거나 AI 서버를 자동 실행하지 않는다.
- HTTP/1.1로 전송한다. 현재 AI 서버는 Java 클라이언트의 HTTP/2 전환 요청에서 스트리밍 본문을 읽지 못해 400 INVALID_REQUEST를 반환한다.
- AI API에 사용자 JWT를 전달하지 않는다. 현재 AI 코드에는 API 키 인증 계약이 없다.
- 통신·응답 검증 실패는 `503 AI_UNAVAILABLE`로 처리하며, `AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.`를 반환한다. 오류 본문은 화면에 노출하지 않는다.
- 모델 호출을 자동 재시도하지 않는다. `/cases/compare`의 AI 서버 자체 문장 틀 fallback은 유지한다.

## 연결된 내부 호출

| Java 메서드 | AI API |
|---|---|
| `structure` | `POST /plan/structure` |
| `restate` | `POST /plan/restate` |
| `embed` | `POST /embed/query` |
| `cards` | `POST /cases/cards` |
| `compare` | `POST /cases/compare` |

AI의 snake_case와 백엔드 Java의 camelCase 사이 변환은 DTO에서 처리한다.
공지 ID는 unsigned long 범위를 넘을 수 있으므로 문자열을 유지한다.
질의 벡터는 문장별 512차원·유한수인지 검증한다. 카드·비교 결과가 요청한 공지 ID인지 확인한다.
DB 조회·적재 및 검색 SQL은 이 HTTP client의 책임이 아니다.

## 화면 API 연결

- `PlanStructureService`는 게임 장르를 DB에서 읽고 AI 변경점을 entities/slots/restatement로 변환한다.
- `CaseSearchService`는 슬롯 질의 임베딩을 받아 pgvector 후보를 검색하고 ±3%p 결과군, 평균 패치 주기, 카드·문장 틀 비교를 응답한다.
- DB 작업은 기존 테이블의 조회만 수행한다. 저장·적재·migration은 추가하지 않는다.
- 실제 추론 검증에는 실행 중인 AI 서버와 역터널이 필요하다. 백엔드 컨테이너에서 설정한 주소의 `/health`를 확인한다.

역터널의 서버 바인딩은 172.17.0.1:8100이다. 인프라 담당이 GatewayPorts clientspecified와 실제 접근 범위를 확인하고 AI 담당이 터널을 연다. 개발자 노트북의 백엔드는 별도 로컬 포워딩 주소를 사용한다. 기존 PostgreSQL 터널은 AI 포트를 전달하지 않는다.

## 실제 AI·DB 통합 검증

`PatchLiveAiIntegrationTest`는 MockMvc 인증 요청부터 실제 `AiPatchClient`, PostgreSQL 검색, AI 카드·비교, 원문 조회까지 연결한다. 외부 AI를 호출하므로 기본 테스트에서는 실행하지 않는다.

- 준비: 로컬 PostgreSQL의 `thispatch_test` DB와 `/health`가 `ready: true`인 AI API.
- 개발자 PC는 서버1로 로컬 포워딩을 연 후 그 주소를 사용한다. 서버1의 `172.17.0.1:8100`을 개발자 PC에서 직접 호출하지 않는다.
- 테스트 데이터와 실제 AI로 만든 벡터는 테스트 DB에만 넣고 종료 시 롤백한다. 운영 데이터 적재나 모델 검색 품질 평가는 하지 않는다.
- `AI_TEST_BASE_URL`은 검증할 AI 주소를 명시하는 별도 환경 변수다. 실제 사용자 입력 대신 고정된 테스트 문장만 전송한다.

`backend/`에서 실행:

```powershell
$env:AI_INTEGRATION_TEST = "true"
$env:AI_TEST_BASE_URL = "http://127.0.0.1:18100"
..\gradlew.bat :backend:test --tests "com.ssafy.thispatch.domain.patch.service.PatchLiveAiIntegrationTest" --no-daemon
```

```bash
AI_INTEGRATION_TEST=true AI_TEST_BASE_URL=http://127.0.0.1:18100 \
  bash ../gradlew :backend:test --tests 'com.ssafy.thispatch.domain.patch.service.PatchLiveAiIntegrationTest' --no-daemon
```
