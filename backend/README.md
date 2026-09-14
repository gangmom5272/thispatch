# 로컬 PostgreSQL 개발 환경

Docker Desktop의 Linux 컨테이너 엔진과 Docker Compose가 필요합니다.
아래 명령은 모두 `backend` 디렉터리에서 실행합니다.

## 구성

- `compose.yaml`: `pgvector/pgvector:pg17` 기반 PostgreSQL 단일 서비스, 로컬 5432 포트, 상태 검사 및 데이터 볼륨.
- `src/main/resources/db/migration/V1__init.sql`: Flyway 초기 migration. 최상단에서 `vector` 확장을 활성화하고 테이블과 PK/FK를 생성합니다. Docker의 `/docker-entrypoint-initdb.d`는 사용하지 않습니다.
- `src/main/resources/db/migration/V2__add_patch_analysis.sql`: 패치 분석 테이블 5개, PK/FK와 기본 코드 데이터를 생성합니다.
- `.env`: 로컬 DB 접속 정보. Git에 커밋하지 않습니다.
- `.env.example`: 필요한 환경 변수의 예시 파일.
- `src/main/resources/application.yaml`: 공통 JPA·Flyway 설정. Hibernate는 `ddl-auto: validate`로 스키마를 검증만 하고 생성·수정하지 않습니다. `open-in-view`는 비활성화하고 Flyway는 활성화하며 migration 위치는 `classpath:db/migration`입니다.
- `src/main/resources/application-dev.yaml`: `dev` 프로필에서 `.env`를 읽어 Spring Boot 접속 설정에 사용합니다. Spring Batch 스키마 자동 생성은 비활성화합니다.

기존 애플리케이션 이름, Gradle 의존성 및 저장소의 서버용 `infra` 설정은 유지합니다.
V1은 `src/ThisPatch_init_with_keys.sql`의 컬럼 정의와 PK/FK를 유지하며,
`patch_change`, `code` 및 해당 테이블의 제약조건만 제외한 17개 테이블을 생성합니다.
확장 활성화와 스키마 생성은 애플리케이션 시작 시 Flyway가 수행합니다.
Flyway는 자체 이력 관리용 `flyway_schema_history` 테이블도 생성합니다. Entity가 추가되면 그에 맞는 스키마가
migration으로 먼저 준비되어야 하며, 불일치하면 Hibernate 검증 단계에서 기동이 실패합니다.

| 항목 | 값 |
| --- | --- |
| 호스트 / 포트 | `.env`의 `POSTGRES_HOST` / `POSTGRES_PORT` (기본 `localhost:5432`) |
| DB명 | `.env`의 `POSTGRES_DB` |
| 사용자 / 비밀번호 | `.env`의 `POSTGRES_USER` / `POSTGRES_PASSWORD` |
| JDBC URL | `.env` 값으로 구성 |
| Docker volume | `thispatch-local_postgres_data` |

## 실행

Docker Desktop을 실행한 뒤 `.env`를 준비하고 DB를 시작합니다. 최초 실행 시 이미지를 내려받습니다.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --wait
```

이미 `.env`가 있으면 복사하지 않습니다. `.env`의 값을 변경한 뒤 새 데이터 볼륨을 초기화하는 경우에만 PostgreSQL이 새 비밀번호로 초기화됩니다.

이미 5432 포트를 사용하는 로컬 PostgreSQL이나 컨테이너가 있다면 해당 서비스를 중지한 뒤 실행합니다.

Spring Boot는 `dev` 프로필로 실행합니다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=dev"
```

macOS/Linux에서는 `./gradlew bootRun --args='--spring.profiles.active=dev'`를 사용합니다.
IDE에서는 활성 프로필을 `dev`로 지정하거나 환경 변수 `SPRING_PROFILES_ACTIVE=dev`를 설정합니다.
IntelliJ의 Working directory는 `.env`가 있는 `backend`로 지정합니다.

로컬 JDBC URL 기본값은 `jdbc:postgresql://localhost:5432/thispatch`입니다.
`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`를
환경 변수로 지정하면 `.env` 값보다 우선 적용됩니다. 사용자와 비밀번호는 기본값 없이
`.env` 또는 환경 변수에서 읽습니다. 전체 접속 URL과 계정은 Spring Boot 표준 환경 변수
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`로도
덮어쓸 수 있습니다.

## 정상 실행 확인

Spring Boot를 실행하여 Flyway V1, V2 적용이 완료된 뒤 확인합니다.

```powershell
docker compose ps
docker compose exec postgres pg_isready -h 127.0.0.1 -U thispatch -d thispatch
docker compose exec postgres psql -U thispatch -d thispatch -c "SELECT current_database(), version();"
docker compose exec postgres psql -U thispatch -d thispatch -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"
docker compose exec postgres psql -U thispatch -d thispatch -c "SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector AS distance;"
```

각각 `healthy`, `accepting connections`, DB명 `thispatch`와 `PostgreSQL 17.x`,
`vector` 확장 버전, 벡터 거리 `0`이 나오면 정상입니다.

Windows 호스트에서 공개 포트 접근도 확인할 수 있습니다.

```powershell
Test-NetConnection localhost -Port 5432
```

`TcpTestSucceeded: True`가 나와야 합니다. 문제가 있으면 로그를 확인합니다.

```powershell
docker compose logs --tail=100 postgres
```

## PostgreSQL + Flyway 통합 테스트

추가 테스트 라이브러리 없이 기존 JUnit, Spring Boot, JDBC를 사용합니다.
기존 `compose.yaml`의 PostgreSQL 컨테이너를 재사용하고, 같은 서버 안에 테스트 전용
`thispatch_test` DB를 생성합니다. `application-test.yaml`은 `.env`의 호스트·포트·계정을
사용하며 DB명만 `thispatch_test`로 고정합니다. DB 생성 명령은 빈 DB만 준비하고,
확장과 테이블 생성은 Spring Boot 시작 시 Flyway가 수행합니다.

Docker Desktop과 `.env`를 준비한 뒤 다음 명령으로 빈 DB에서 검증합니다.

```powershell
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 시작 실패' }
docker compose exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" thispatch_test'
if ($LASTEXITCODE -ne 0) { throw '테스트 DB 생성 실패: 기존 thispatch_test DB가 있는지 확인하세요.' }
try {
    .\gradlew.bat test --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw '통합 테스트 실패' }
} finally {
    docker compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" thispatch_test'
}
```

macOS/Linux에서는 동일한 DB 생성·삭제 명령과 `./gradlew test --rerun-tasks`를 사용합니다.
위 절차는 이번 실행에서 생성한 테스트 DB만 삭제하며, 기존 컨테이너와 개발 DB는 유지합니다.
이미 `thispatch_test` DB가 있으면 생성 단계에서 중단합니다. 기존 테스트 DB를 재사용하려면
Gradle 명령만 실행할 수 있지만, 빈 DB에서 최초 migration 적용까지 확인하려면 위 절차를 사용합니다.
외부에 설정한 `SPRING_DATASOURCE_*` 값은 테스트 설정보다 우선하므로 해제한 상태로 실행합니다.

`ThispatchApplicationTests`의 한 테스트가 Spring Boot를 두 번 시작합니다.
첫 번째 애플리케이션을 완전히 종료한 뒤 같은 DB에 두 번째 애플리케이션을 연결합니다.

| 확인 항목 | 검증 방식 |
| --- | --- |
| PostgreSQL 연결 | 실제 Spring Boot DataSource로 `SELECT version()` 실행 |
| V1 → V2 적용 및 성공 이력 | `flyway_schema_history`를 `installed_rank` 순으로 조회하여 버전, 파일명, 성공 여부, checksum 확인 |
| 테이블 및 pgvector | PostgreSQL 카탈로그에서 V1 17개, V2 5개 테이블과 `vector` 확장 확인 |
| V2 제약조건 | `pg_constraint`에서 PK 5개·FK 5개의 이름, 소속 테이블, 컬럼과 참조 대상을 포함한 정의 대조. 현재 V2에 없는 UNIQUE/CHECK가 생성되지 않았는지도 확인 |
| 기본 코드 데이터 | 3개 코드 테이블의 19건에 대해 코드, 한글명, definition 확인 |
| embedding 타입 | `format_type`으로 `patch_chunk.embedding = vector(512)` 확인 |
| 재기동 시 중복 적용 방지 | 재기동 전후 migration 이력 전체 비교, Flyway validate 및 미적용 migration 0개, 코드 데이터 재확인 |

테스트는 애플리케이션 기동으로 migration을 적용한 뒤 조회로 검증합니다. Entity, Controller, Service는 필요하지 않습니다.
결과는 `build/reports/tests/test/index.html`에서 확인할 수 있습니다.
이 테스트의 기대값은 현재 V1/V2 설계를 기준으로 하며, 새 migration을 추가할 때 함께 갱신합니다.

## 애플리케이션 URL과 CORS 설정

`app` 설정은 `global.config.AppProperties`로 읽으며, 필수값이 없거나 형식이 잘못되면 시작을 중단한다.
`dev`는 아래 로컬 기본값을 사용하고, `prod`는 기본값 없이 환경변수로 주입한다.

| 환경변수 | 설정 경로 | dev 기본값 |
| --- | --- | --- |
| `FRONTEND_BASE_URL` | `app.frontend-base-url` | `http://localhost:5173` |
| `BACKEND_PUBLIC_URL` | `app.backend-public-url` | `http://localhost:8080` |
| `CORS_ALLOWED_ORIGINS` | `app.cors.allowed-origins` | `http://localhost:5173` |

- 로컬에서는 `.env.example`을 참고해 기존 `.env`에 필요한 값만 추가한다.
- URL은 절대 HTTP(S) 주소여야 하며 사용자 정보, query, fragment를 포함할 수 없다.
- `BACKEND_PUBLIC_URL`은 브라우저에서 접근하는 주소다. 운영의 `/api` 같은 경로 접두사를 포함할 수 있다.
- CORS 허용 주소는 `https://thispatch.com,http://localhost:5173`처럼 쉼표로 구분한다. 각 주소에는 경로·끝 슬래시를 넣지 않으며 `*` 패턴도 허용하지 않는다.
- CORS 허용 메서드는 현재 API 계약의 `GET`, `POST`, `DELETE`와 사전 요청용 `OPTIONS`다. 허용 요청 헤더는 `Authorization`, `Content-Type`, `Accept`다.
- 서비스 인증은 Bearer Token 방식이므로 CORS의 `allowCredentials`는 `false`다.
- `CorsFilter`는 서블릿 필터로 한 번 등록하며 Security 필터보다 먼저 실행된다. `SecurityFilterChain`에는 CORS 필터를 중복 등록하지 않는다.
- CORS 설정은 공개·보호 API의 인증 규칙을 바꾸지 않는다. 접근 규칙은 `SecurityConfig`에서 관리하고, 실제 JWT 검증 필터는 후속 작업에서 연결한다.
- 운영 배포 전 `FRONTEND_BASE_URL`, `BACKEND_PUBLIC_URL`, `CORS_ALLOWED_ORIGINS`를 서버 환경에 추가해야 한다. 현재 서버 Compose의 `env_file`로 주입할 수 있다.
- `test` 프로필은 URL·CORS 값을 테스트 설정에서 지정하므로 로컬 `.env`의 해당 값에 의존하지 않는다. Spring의 `APP_*` 환경변수·시스템 속성 직접 지정은 테스트 설정도 덮어쓸 수 있다.

## Security 접근 규칙

- `SecurityConfig`는 API 명세의 공개 경로를 HTTP Method까지 일치시켜 허용한다. `GET /session`은 비로그인 접근을 허용하며 나머지 요청은 인증을 요구한다.
- HTTP 세션의 인증 상태를 읽거나 저장하지 않는 `STATELESS` 방식이다. form login, HTTP Basic, Security 기본 로그아웃, 요청 저장은 비활성화한다.
- 브라우저 자동 전송 인증을 사용하지 않는 Bearer 방식에 맞춰 CSRF 필터를 비활성화한다. CORS는 기존 서블릿 필터가 먼저 처리하며 Security 체인에는 중복 등록하지 않는다.
- Security의 인증 실패는 `401 UNAUTHORIZED`, 권한 거부는 `403 FORBIDDEN`이며 기존 `ErrorResponse`를 사용한다. 상세 계약은 [공통 오류 규칙](docs/api/conventions.md#security-인증권한-오류)을 따른다.
- 내부 `ERROR` dispatch는 원래 오류 응답을 유지하기 위해 허용한다. 직접 요청한 `/error`는 인증을 요구한다.
- 현재는 JWT 검증 필터·회원 API가 없으므로 실제 Bearer Token으로 로그인할 수 없다. 테스트용 인증으로 접근 규칙을 검증하며, `/session` 응답과 토큰 만료 처리도 후속 작업이다.
- 역할별 권한 규칙은 정의하지 않는다. `403` 핸들러는 Security에서 권한 거부가 발생할 때 사용하도록 준비한다.

## JWT 설정

`global.config.JwtProperties`에 JWT 발급·검증에 사용할 설정을 등록한다.
현재 단계에서는 설정과 시작 시 검증만 제공하며, 실제 토큰 발급·검증 및 Security 필터 연결은 후속 작업이다.

| 환경변수 | 설정 경로 | 기본값 |
| --- | --- | --- |
| `JWT_SECRET` | `app.jwt.secret` | 없음. 필수 |
| `JWT_ACCESS_TOKEN_TTL` | `app.jwt.access-token-ttl` | `15m` (15분) |
| `JWT_REFRESH_TOKEN_TTL` | `app.jwt.refresh-token-ttl` | `7d` (7일) |

- 서명 방식은 `app.jwt.algorithm: HS256`으로 설정하며 다른 알고리즘은 시작 시 거부한다.
- `JWT_SECRET`은 32바이트 이상의 안전한 난수를 표준 Base64로 인코딩한 값이다. 빈 값, 잘못된 Base64, 디코딩 후 32바이트 미만이면 시작을 중단한다.
- TTL은 `15m`, `7d`, `900s`처럼 단위를 포함해 지정한다. 0·음수·초 미만 단위의 값은 거부한다.
- 개발용 키는 기존 `.env`에 추가하고, 운영용 키는 별도로 생성하여 서버 환경에 주입한다. `.env.example`에는 실제 키를 넣지 않는다.
- 설정 객체의 `toString()`은 키를 가린다. 호출 코드에서도 `secret()` 반환값을 로그·응답에 출력하지 않는다.
- `test` 프로필의 키는 공개된 테스트 전용 값이며 개발·운영에 사용하지 않는다. 테스트 TTL도 별도로 고정한다.
- 운영 배포 전에 `JWT_SECRET`을 서버 환경에 추가해야 한다. 토큰 발급 구현 후에는 키를 변경하면 기존 키로 발급된 토큰 검증에 영향을 준다.

PowerShell에서 로컬 키 생성 예:

```powershell
$jwtKeyBytes = New-Object byte[] 32
$jwtRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $jwtRandom.GetBytes($jwtKeyBytes) } finally { $jwtRandom.Dispose() }
[Convert]::ToBase64String($jwtKeyBytes)
```

생성 결과를 `.env`의 `JWT_SECRET=` 뒤에 넣는다. 기존 키가 있으면 재생성하거나 덮어쓰지 않는다.

## 종료 및 데이터 유지

```powershell
docker compose down
```

컨테이너를 종료·제거해도 named volume의 DB 데이터는 유지됩니다.
다시 `docker compose up -d --wait`를 실행하면 기존 데이터를 사용합니다.
`docker compose down -v`는 DB 데이터 볼륨까지 삭제하므로 데이터를 유지하려면 사용하지 마세요.

Flyway는 DB 이력에 기록되지 않은 migration을 적용합니다. 기존 볼륨에 `vector` 확장이
이미 있어도 V1의 `CREATE EXTENSION IF NOT EXISTS vector`는 그대로 실행할 수 있습니다.
애플리케이션 테이블이 이미 있는 DB는 초기 migration 대상인 빈 스키마와 다르므로,
기존 스키마와 migration 이력을 확인해야 합니다. 데이터 볼륨을 삭제하거나 자동 baseline을 설정하지 않습니다.

참고: [pgvector Docker 이미지](https://github.com/pgvector/pgvector#docker),
[PostgreSQL 공식 이미지의 볼륨 및 초기화 동작](https://hub.docker.com/_/postgres).

## API 구현과 하네스 지침

- API를 구현하는 팀원은 [API index](docs/api/README.md)와 [공통 오류 응답 계약](docs/api/conventions.md#error-response)을 기준으로 작업합니다. 오류 응답 형식과 검증 예시는 공통 계약 문서에서 관리합니다.
- 공통 오류 처리는 `src/main/java/com/ssafy/thispatch/global/exception/`에 구현되어 있습니다. 도메인 오류는 `ErrorCode`와 `BusinessException`으로 연결하고, 오류 DTO나 전역 핸들러를 중복 구현하지 않습니다.
- AI 코딩 하네스의 작업 규칙은 [AGENTS.md](AGENTS.md), API 구현 절차는 [implement-api 스킬](.agents/skills/implement-api/SKILL.md)에 있습니다. 두 파일은 Git으로 관리하므로 커밋을 받아 팀원과 하네스가 같은 지침을 확인할 수 있습니다.
- 공통 계약·구현 방식이 바뀌면 관련 API 문서와 하네스 지침도 같은 변경에서 맞춥니다. 이미 실행 중인 하네스 작업에는 변경된 지침을 다시 읽도록 전달합니다.
