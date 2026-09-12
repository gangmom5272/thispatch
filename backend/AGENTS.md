# Backend 작업 규칙

적용 범위는 `backend/`이며, 아래 경로와 명령의 기준 디렉터리도 backend다.
REST API 구현·수정 요청이면 [implement-api](.agents/skills/implement-api/SKILL.md)를 읽고 해당 절차를 따른다.

## 작업 시작 전

- `git status --short`, 실제 디렉터리·패키지 구조, 관련 기존 코드와 테스트를 확인한다.
- API 작업은 [API index](docs/api/README.md), [공통 규칙](docs/api/conventions.md), 대상 도메인 문서를 읽는다.
- 관련 Flyway migration을 확인한다. DB와 무관한 변경에는 해당 없음을 구분한다.

## 프로젝트 기준

- 상위 Git/Gradle 루트의 `settings.gradle`, `build.gradle`과 이 디렉터리의 `build.gradle`을 기준으로 한다. backend는 Java 17·Spring Boot의 `:backend` 모듈이며 `:common`에 의존한다.
- Java 기준 경로는 `src/main/java/com/ssafy/thispatch/`다. API 담당 도메인은 API index로 찾는다.
- 테스트는 `src/test/java/`, 테스트 설정은 `src/test/resources/application-test.yaml`에 있다.
- 스키마는 `src/main/resources/db/migration/`의 Flyway migration이 관리한다. 기존 적용 migration을 고치거나 `ddl-auto: validate`를 자동 스키마 변경 설정으로 바꾸지 않는다.

## API 계약

- `docs/api/`를 프론트-백엔드 API 계약이자 구현·검증 기준으로 취급한다.
- HTTP Method, URL, Path Variable, Query Parameter, Request/Response 필드, 타입, 상태 코드, Authorization 요구사항을 임의로 바꾸지 않는다.
- 설계·REST 스타일·네이밍 정리를 이유로 계약을 수정하지 않는다. 명세와 DB·기존 코드의 충돌로 구현할 수 없으면 충돌 근거와 영향을 사용자에게 보고한다.

## 사용자 결정

- 명세·DB schema·기존 코드·테스트로 확정되지 않고 계약·비즈니스 의미·권한·정합성에 영향을 주는 사항은 구현 전에 사용자에게 확인한다. 새로운 schema/migration도 사전 확인 대상이다.
- 이미 결정·승인된 사항은 재질문하지 않는다. 명확한 사항과 외부 동작을 바꾸지 않는 내부 구현은 스스로 결정한다.

## 변경 범위

- 요청에 필요한 코드만 수정하고 기존 구조를 재사용한다.
- 다른 도메인, API 문서, migration, build·Security 설정, 무관한 리팩터링은 필요 없이 변경하지 않는다. 필수 추가 변경은 이유와 범위를 판단하고, 사용자 결정이 필요한 사항은 먼저 확인한다.
- 작업 전부터 존재한 사용자 변경은 보존한다. 이번 작업에서 만든 무관한 변경만 제거하며, 분리할 수 없으면 이유를 보고한다.

## 검증 및 Diff

코드 수정 후 현재 Gradle 구성을 다시 확인하고, 최소한 다음 compile과 test를 순서대로 실행한다.

Windows PowerShell:
```powershell
..\gradlew.bat :backend:compileJava
..\gradlew.bat :backend:test
```

macOS/Linux:
```sh
../gradlew :backend:compileJava
../gradlew :backend:test
```

- 통합 테스트에는 PostgreSQL·pgvector와 `thispatch_test` DB가 필요하다. [로컬 DB 준비 절차](README.md)를 참고하되 wrapper는 위 멀티모듈 명령을 사용한다.
- DB 테스트에는 Docker 서비스, 테스트 DB, `.env` 또는 환경 변수 설정이 필요하다. 외부 `SPRING_DATASOURCE_*` 설정이 테스트 DB 연결을 바꾸지 않는지 확인한다. 개발 DB·기존 데이터를 삭제해서 테스트를 통과시키지 않는다.
- 명령 실패·테스트 실패·미실행을 성공으로 보고하지 않는다. 문서만 변경한 경우에는 문서 내용·경로·변경 범위를 검증하고 compile/test 미실행 이유를 밝힌다.
- 완료 전 `git diff`, `git diff --cached`, `git status --short`로 변경 범위를 확인한다. 새 파일은 diff에 빠질 수 있으므로 내용도 직접 확인한다.

## 완료 보고

변경 내용·파일, 실행한 검증 명령, compile/test 및 API 계약·diff 검증 결과, 남은 문제와 필요한 사용자 결정을 보고한다. 없는 항목은 `none`, 실행하지 않은 검증은 사유와 함께 표시한다.
