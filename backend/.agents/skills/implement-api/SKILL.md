---
name: implement-api
description: backend의 특정 REST API를 구현하거나 수정할 때 사용한다. docs/api 계약과 기존 코드·DB를 확인하고, 필요한 사용자 결정을 구분한 뒤 최소 변경과 검증을 수행한다. 문서 정리만 요청한 작업에는 적용하지 않는다.
---

# Implement API

backend 루트의 [AGENTS.md](../../../AGENTS.md)를 상위 규칙으로 따른다. 아래 코드 경로와 명령의 기준 디렉터리는 backend다.

## 1. Context 수집

1. 요청한 Method·URL·변경 범위를 확인하고 [API index](../../../docs/api/README.md)에서 담당 도메인을 찾는다. index에서 찾을 수 없으면 임의로 endpoint를 만들지 말고 추가·변경할 계약을 확인한다.
2. [공통 규칙](../../../docs/api/conventions.md)과 `docs/api/{domain}.md`를 읽는다. index의 `statistics` 등 실제 도메인명을 사용한다.
3. `src/main/java/com/ssafy/thispatch/domain/{domain}/`의 Controller·Service·Repository·DTO·Entity와 호출되는 `global/`, `client/`, 필요시 상위 `common/` 코드를 확인한다.
4. `src/test/java/`의 관련 테스트와 `src/test/resources/application-test.yaml`을 확인한다. DB 접근이 필요하면 `src/main/resources/db/migration/`에서 관련 테이블·컬럼·제약과 변경 이력을 확인한다.
5. 파일이 없거나 `.gitkeep`만 있으면 기존 구현·패턴이 있다고 가정하지 않는다. 읽은 근거와 미확인 사항을 구분한다.

오류 처리에는 기존 `src/main/java/com/ssafy/thispatch/global/exception/` 구현과 `src/test/java/com/ssafy/thispatch/global/exception/GlobalExceptionHandlerTest.java`를 확인한다. 응답 형식의 기준은 [공통 오류 계약](../../../docs/api/conventions.md#error-response)이며, 이미 확정된 필드·시간대·오류 상세 노출 정책은 다시 질문하지 않는다.

## 2. 계약 추출

구현 전에 작업할 endpoint마다 다음 항목을 명세에서 추출한다. 별도 산출물 파일은 만들 필요 없다.

- Method, URL, Authorization
- Path Variables, Query Parameters: 이름·타입·필수 여부·기본값·허용값
- Request Body, Response Body: 구조·필드·타입·null 및 조건별 응답
- 성공 Status Code, Error Responses
- Processing Rules / Notes: 계산·처리·DB 매핑·예외 동작

명시된 계약, 관련 코드·schema·테스트에서 확인한 사실, 명세에 없는 사항을 구분한다.

## 3. Decision Gate

구현 전에 다음 항목 중 작업에 해당하는 내용을 검토한다. 각 판단은 확인한 명세·schema·코드·테스트 또는 기존 사용자 결정에 근거한다.

| 검토 영역 | 확인 항목 |
|---|---|
| 입출력 | 요청·응답 필드 의미, null 허용, 기본값, 데이터가 없는 경우 |
| 조회 | 데이터 소스, 집계·정렬 기준, 필터, pagination |
| 상태·권한 | 권한 조건, 사용자 상태, 상태 전이, 중복 처리, 없는 리소스 처리 |
| 저장·정합성 | transaction boundary, 동시성, migration 필요 여부, 저장 데이터 의미 |
| 외부 연동 | 외부 API 실패·복구, 캐시 적용이 결과 의미에 미치는 영향 |
| 충돌 | 명세와 기존 코드·schema·테스트 간 불일치 |

- **바로 구현:** 근거 중 하나 이상에서 행동이 명확하고 다른 근거와 충돌하지 않으면 질문하지 않는다.
- **내부 구현 판단:** helper 분리, DTO mapping 위치, 지역 변수명, 동일 결과의 repository 구현, 기존 패턴과 같은 annotation 등 외부 계약·비즈니스 의미를 바꾸지 않는 선택은 스스로 한다.
- **NEEDS_DECISION:** 복수의 합리적 선택이 API 계약·DB schema·정합성·보안/권한·비즈니스 규칙·저장 데이터 의미·장애 복구·프론트 동작에 영향을 주거나 필요한 규칙을 확인할 수 없으면 해당 구현을 멈춘다. 명세/schema 충돌, 미승인 새 migration, 계약 변경 가능성도 여기에 해당한다.

질문은 불명확한 사실과 근거, 선택지 2~3개, 각 선택의 API·DB·구현 영향, 근거가 있는 추천안으로 구성한다. 단순히 “어떻게 할까요?”라고 묻지 않는다. 답변 전에는 결정이 필요한 동작을 구현하지 않는다.

## 4. 기존 구현 분석 및 최소 구현

1. Controller → Service → Repository/외부 client 경로와 DTO·Entity 변환, 인증·예외 처리, transaction·테스트 패턴을 추적해 수정 지점을 정한다.
2. 기존 package·naming·response wrapper·예외·repository·transaction·테스트 방식을 재사용한다. 공통 구현이 없으면 계약에 필요한 최소 코드만 추가하고, 외부 동작이 불명확하면 Decision Gate로 돌아간다.
3. 요청한 기능과 정상·오류·경계 동작을 검증하는 관련 테스트를 최소 범위로 구현한다. 명세에 없는 기능이나 테스트 통과만을 위한 계약 변경은 하지 않는다.
4. 구현 중 새 충돌이나 schema·권한·정합성 결정이 발견되면 해당 변경 전에 Decision Gate를 다시 적용한다.

오류 처리 시 적용할 사항:

- `ErrorResponse`와 `GlobalExceptionHandler`를 재사용한다. 요청 검증은 기존 핸들러로 연결하고, 컨트롤러별 포괄적 catch나 중복 오류 DTO를 추가하지 않는다.
- 도메인 비즈니스 오류는 `ErrorCode`를 구현한 코드와 `BusinessException`으로 연결한다. 기존 `CommonErrorCode`와 도메인 코드를 먼저 확인하고, 새 코드·메시지는 대상 API 문서에 반영한다. 명세로 확정되지 않은 오류 조건·HTTP 상태·비즈니스 의미는 Decision Gate 대상이다.
- Spring MVC가 정한 상태 코드와 프로토콜 헤더를 유지한다. Security 필터 오류는 공통 advice가 처리하지 않으므로, 해당 인증 작업에서 `ErrorResponse`를 사용하는 별도 핸들러로 연결한다.

## 5. 검증

1. **Compile:** AGENTS.md의 실제 `:backend:compileJava` 명령을 실행하고 결과를 확인한다.
2. **Test:** AGENTS.md의 테스트 준비 조건을 확인하고 `:backend:test`를 실행한다. 새 API 동작에 대한 관련 테스트 결과도 확인한다.
3. **계약 재검증:** 2단계에서 추출한 계약과 구현·테스트를 대조한다. Method·URL·Authorization·Path/Query·Request/Response·타입·상태 코드 및 Processing Rules가 모두 일치해야 한다.
4. **Diff:** AGENTS.md의 변경 범위·Diff 규칙에 따라 실제 수정·추가 파일을 확인한다.

오류 동작을 추가·수정했다면 관련 테스트에서 HTTP 상태와 문자열 코드, `data`·`success` 생략, 필드 오류가 있을 때만 `errors` 포함, 입력값·내부 예외 정보 비노출을 확인한다. 한국 시간 응답 및 MVC 기본 오류의 회귀 검증은 기존 `GlobalExceptionHandlerTest`를 활용한다.

승인된 migration 변경 시 관련 테스트 기대값도 확인한다. 테스트를 통과시키기 위해 기존 검증을 임의로 삭제하지 않는다.

실패 시 원인을 분석하고 범위 내 명확한 기술적 문제는 수정 후 관련 검증을 다시 수행한다. 같은 근본 원인이 수정 후에도 반복되면 같은 시도를 계속하지 말고 원인·시도·남은 장애를 보고한다. 명세 모순, schema 변경, 비즈니스 결정, 큰 범위 확장이나 다른 도메인 설계 변경이 필요하면 구현을 중단하고 결정 여부를 구분한다.

## 6. 결과 판정

| Status | 기준 |
|---|---|
| PASS | compile과 backend test 성공, 관련 API 동작 검증, 전체 계약 일치, 요청과 무관한 본인 변경 없음, 미결정 사항 없음 |
| FAIL | compile/test 실패, 구현 버그로 계약 불일치, 범위 내 기술적 문제 미해결. 환경 문제로 필수 검증을 실행하지 못한 경우도 이유와 함께 표시 |
| NEEDS_DECISION | Decision Gate에서 사용자 결정이 필요하다고 판정한 상태. 진행하지 못한 검증과 이유도 보고 |

```text
Status: PASS / FAIL / NEEDS_DECISION

Task:
- 작업한 endpoint와 변경 내용

Changed:
- 변경 파일

Verification:
- Compile: 실행 명령과 결과 또는 미실행 사유
- Test: 실행 명령과 결과 또는 미실행 사유
- API contract: 확인 결과
- Diff: 확인 결과

Issues:
- 남은 문제 또는 none

Decisions Needed:
- 사용자 판단이 필요한 사항 또는 none
```
