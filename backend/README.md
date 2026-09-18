# 로컬 PostgreSQL · Redis 개발 환경

## DeepL 리뷰·패치노트 번역 설정

기존 백엔드 실행 환경에서 `backend/.env`에 `DEEPL_API_KEY=발급받은_API_키`를 추가하고
`dev` 프로필로 다시 실행하면 `GET /reviews/{reviewId}/translation`과
`GET /patches/{patchId}/translation`을 사용할 수 있습니다. 두 API가 같은 키와 클라이언트를 사용합니다.
요청에는 기존 Access Token을 `Authorization: Bearer ...`로 전달합니다.
실제 키는 커밋하지 않으며 `.env.example`에는 빈 값만 둡니다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `DEEPL_API_KEY` | 빈 값 | DeepL API 전용 인증 키 |
| `DEEPL_CONNECT_TIMEOUT` | `3s` | 연결 제한 시간 |
| `DEEPL_READ_TIMEOUT` | `10s` | 응답 제한 시간 |

- [DeepL 공식 인증 규칙](https://developers.deepl.com/docs/getting-started/auth)에 따라 `:fx`로 끝나는 키는 `https://api-free.deepl.com`, 나머지는 `https://api.deepl.com`을 자동 선택합니다.
- 운영에서는 백엔드 컨테이너 환경에 `DEEPL_API_KEY`를 주입하고 재시작합니다. 기존 서버 Compose의 `env_file`을 사용할 수 있습니다.
- 키가 없어도 서버는 기동합니다. 번역이 필요한 요청은 `502 TRANSLATION_UNAVAILABLE`을 반환합니다. 리뷰의 한국어·빈 원문은 키 없이도 원문을 반환합니다. 패치노트는 원문 언어 정보가 없어 한국어도 키가 필요하며, 빈 제목·본문만 외부 호출을 생략합니다.
- 오류·입력 제한·캐시 정책은 [리뷰 번역 계약](docs/api/review.md#리뷰-번역)과 [패치노트 번역 계약](docs/api/patch.md#패치노트-번역)을 따릅니다. 번역을 기다리는 동안 DB 트랜잭션을 유지하지 않습니다.
- 자동화 테스트는 DeepL HTTP 호출을 대체하므로 실제 키와 외부 번역 서비스를 사용하지 않습니다.

Docker Desktop의 Linux 컨테이너 엔진과 Docker Compose가 필요합니다.
아래 명령은 모두 `backend` 디렉터리에서 실행합니다.

## 구성

- `compose.yaml`: PostgreSQL(`pgvector/pgvector:pg17`, 로컬 5432 포트, 상태 검사 및 데이터 볼륨)과 Redis(`redis:8.2-alpine`, 로컬 6379 포트, 상태 검사) 서비스.
- `src/main/resources/db/migration/V1__init.sql`: Flyway 초기 migration. 최상단에서 `vector` 확장을 활성화하고 테이블과 PK/FK를 생성합니다. Docker의 `/docker-entrypoint-initdb.d`는 사용하지 않습니다.
- `src/main/resources/db/migration/V2__add_patch_analysis.sql`: 패치 분석 테이블 5개, PK/FK와 기본 코드 데이터를 생성합니다.
- `src/main/resources/db/migration/V3__add_member_refresh_token.sql`: 기존 `member`에 nullable Refresh Token 해시·만료 시각 컬럼을 추가합니다. 별도 토큰 테이블이나 폐기 이력은 만들지 않습니다.
- `.env`: 로컬 DB 접속 정보. Git에 커밋하지 않습니다.
- `.env.example`: 필요한 환경 변수의 예시 파일.
- `src/main/resources/application.yaml`: 공통 JPA·Flyway 설정. Hibernate는 `ddl-auto: validate`로 스키마를 검증만 하고 생성·수정하지 않습니다. `open-in-view`는 비활성화하고 Flyway는 활성화하며 migration 위치는 `classpath:db/migration`입니다.
- `src/main/resources/application-dev.yaml`: `dev` 프로필에서 `.env`를 읽어 Spring Boot 접속 설정에 사용합니다. Spring Batch 스키마 자동 생성은 비활성화합니다.

서버용 실행 구성은 저장소의 `infra`에서 관리합니다.
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

Docker Desktop을 실행한 뒤 `.env`를 준비하고 PostgreSQL과 Redis를 시작합니다. 최초 실행 시 이미지를 내려받습니다.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --wait
```

이미 `.env`가 있으면 복사하지 않습니다. `.env`의 값을 변경한 뒤 새 데이터 볼륨을 초기화하는 경우에만 PostgreSQL이 새 비밀번호로 초기화됩니다.

이미 5432 또는 6379 포트를 사용하는 로컬 서비스나 컨테이너가 있다면 포트 충돌을 해소한 뒤 실행합니다.

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

## 로컬 Redis

[Redis 공식 이미지](https://hub.docker.com/_/redis)의 `8.2-alpine` 태그를 사용합니다.
호스트에서는 `127.0.0.1:6379`, 같은 Compose 네트워크의 컨테이너에서는 `redis:6379`로 접속합니다.
비밀번호 없이 사용하는 로컬 개발용 설정이며, 호스트 포트는 루프백 주소에만 바인딩합니다.
RDB 스냅샷과 AOF 저장을 비활성화하므로 Redis를 재시작하면 메모리의 데이터는 사라집니다.
이 설정은 로컬 실행용이며 운영 데이터 보존 정책은 별도로 정합니다.

Redis만 시작하고 상태를 확인하려면 다음 명령을 사용합니다.

```powershell
docker compose up -d --wait redis
docker compose ps redis
docker compose exec -T redis redis-cli ping
Test-NetConnection 127.0.0.1 -Port 6379
```

상태가 `healthy`, PING 응답이 `PONG`, Windows 포트 검사 결과가 `TcpTestSucceeded: True`이면 정상입니다.
macOS/Linux에서는 `Test-NetConnection`을 제외한 Docker 명령을 동일하게 사용합니다.

```powershell
docker compose logs --tail=100 redis
docker compose stop redis
```

### 백엔드 연결 설정

Spring Data Redis starter와 기본 Lettuce 클라이언트를 사용합니다. Spring Boot가
`RedisConnectionFactory`와 `StringRedisTemplate`을 자동 등록하므로 필요한 서비스에서 주입받아 사용합니다.
Redis Repository 자동 탐색은 비활성화하며, 기존 JPA Repository를 계속 사용합니다.
설정 경로는 [Spring Boot Redis 설정](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/#application-properties.data.spring.data.redis.host)의 `spring.data.redis.*`를 따릅니다.

| 환경변수 | 설정 경로 (`spring.data.redis.` 뒤) | dev 기본값 | prod 기본값 |
| --- | --- | --- | --- |
| `REDIS_HOST` | `host` | `localhost` | 없음. 필수 주입 |
| `REDIS_PORT` | `port` | `6379` | `6379` |
| `REDIS_PASSWORD` | `password` | 빈 값 (인증 없음) | 빈 값 (인증 없음) |
| `REDIS_DATABASE` | `database` | `0` | `0` |
| `REDIS_CONNECT_TIMEOUT` | `connect-timeout` | `2s` | `2s` |
| `REDIS_TIMEOUT` | `timeout` | `2s` | `2s` |

- 개발 환경은 `.env.example`의 Redis 항목을 참고합니다. 기본 로컬 Compose를 사용하면 기존 `.env`에 값을 추가하지 않아도 연결됩니다.
- `REDIS_PORT`는 백엔드의 접속 포트입니다. 값을 바꿔도 Compose의 공개 포트가 바뀌지는 않습니다.
- 운영 배포 전에 백엔드 컨테이너에서 접근 가능한 Redis를 준비하고 서버 환경에 `REDIS_HOST`를 설정해야 합니다. 인증을 사용하는 서버라면 `REDIS_PASSWORD`도 주입합니다. 서버 Compose의 기존 `env_file`로 전달할 수 있습니다.
- 연결 팩토리 생성이나 애플리케이션 기동 성공만으로 실제 Redis 연결이 검증되지는 않습니다. 아래 연결 테스트에서 PING 응답을 확인합니다.
- `test` 프로필은 로컬 `localhost:6379`, DB `15`, 비밀번호 없음으로 고정합니다. `.env`의 `REDIS_*` 값을 사용하지 않지만, `SPRING_DATA_REDIS_*` 환경변수·시스템 속성은 테스트 설정을 덮어쓸 수 있으므로 실행 전에 확인합니다. `SPRING_DATA_REDIS_URL`은 호스트·포트·인증 설정에 우선할 수 있습니다.

### 백엔드 Redis 연결 검증

실제 연결 테스트는 `REDIS_INTEGRATION_TEST=true`일 때 실행합니다. 일반 테스트 실행에서는 건너뛰므로 Redis가 없는 환경에서도 기존 테스트를 실행할 수 있습니다.
이 테스트는 `test` 프로필 설정과 자동 등록된 연결 팩토리로 PING만 전송하며 데이터를 변경하지 않습니다.

```powershell
docker compose up -d --wait redis
if ($LASTEXITCODE -ne 0) { throw 'Redis 시작 실패' }
$previousRedisIntegrationTest = $env:REDIS_INTEGRATION_TEST
try {
    $env:REDIS_INTEGRATION_TEST = 'true'
    ..\gradlew.bat :backend:test --tests '*RedisConnectionIntegrationTest' --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw 'Redis 연결 테스트 실패' }
} finally {
    $env:REDIS_INTEGRATION_TEST = $previousRedisIntegrationTest
}
```

macOS/Linux에서는 `REDIS_INTEGRATION_TEST=true ../gradlew :backend:test --tests '*RedisConnectionIntegrationTest' --rerun-tasks`로 실행합니다.

### Steam 로그인 코드 관리 기반

`domain.member.service.SteamLoginCodeService`는 Redis에 로그인 코드 해시와 회원 ID를 저장합니다. 기본 TTL은 5분이며 `STEAM_LOGIN_CODE_TTL`로 조정합니다. 0·음수·초 미만의 TTL은 시작 시 거부합니다. PostgreSQL migration은 추가하지 않습니다.

| 메서드 | 후속 API 사용 방법 |
| --- | --- |
| `issue(memberId)` | Steam 인증과 회원 저장 커밋 후 호출해 32바이트 난수 기반 코드 원문을 받습니다. 반환 코드를 프론트 콜백에 전달하고 로깅하지 않습니다. |
| `validate(loginCode)` | 소비 없이 유효한 코드의 회원 ID를 조회합니다. TTL을 늘리지 않으며 이후 소비 성공을 보장하지 않습니다. |
| `consume(loginCode)` | `GETDEL`로 조회·삭제를 원자적으로 수행하고 회원 ID를 받습니다. 토큰 교환에는 이 메서드의 반환값을 사용합니다. |

- 코드는 JWT와 별개이며 일반 API 인증에 사용할 수 없습니다. 회원 존재·상태 확인, 회원 생성, JWT 발급·저장, Controller는 후속 이슈의 범위입니다.
- Redis 6.2 이상의 `GETDEL`이 필요합니다. 로컬 Compose의 Redis 8.2에서 사용할 수 있습니다.
- 무효·만료·이미 소비된 코드는 `BusinessException(STEAM_LOGIN_CODE_INVALID)`로 공통 오류 처리에 연결합니다. Redis 연결·저장 실패는 무효 코드로 처리하지 않고 공통 서버 오류로 전달합니다.
- 소비 후 실패해도 코드는 복구하지 않습니다. Redis 소비는 DB 트랜잭션 롤백과 독립적입니다. 사용자는 Steam 로그인을 다시 시작해야 합니다.
- 확정 정책은 [회원 API 문서](docs/api/member.md#steam-로그인-코드-관리-정책)를 따릅니다.
- 실제 Redis 발급·만료·동시 소비 테스트도 `REDIS_INTEGRATION_TEST=true`일 때 실행합니다. 테스트 프로필의 DB 15를 확인한 뒤 테스트가 발급한 키만 정리하며 `FLUSHDB`는 사용하지 않습니다.

PowerShell에서 실제 Redis 테스트를 포함한 전체 검증:

```powershell
..\gradlew.bat :backend:compileJava
$previousRedisIntegrationTest = $env:REDIS_INTEGRATION_TEST
try {
    $env:REDIS_INTEGRATION_TEST = 'true'
    ..\gradlew.bat :backend:test
    if ($LASTEXITCODE -ne 0) { throw '백엔드 테스트 실패' }
} finally {
    $env:REDIS_INTEGRATION_TEST = $previousRedisIntegrationTest
}
```

테스트에는 아래 절차의 PostgreSQL 테스트 DB와 접속 환경변수도 필요합니다.

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
이 테스트의 기대값은 현재 V1/V2/V3 설계를 기준으로 하며, 새 migration을 추가할 때 함께 갱신합니다.

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
- CORS 설정은 공개·보호 API의 인증 규칙을 바꾸지 않는다. 접근 규칙은 `SecurityConfig`, Access Token 검증은 `JwtAuthenticationFilter`가 처리한다.
- 운영 배포 전 `FRONTEND_BASE_URL`, `BACKEND_PUBLIC_URL`, `CORS_ALLOWED_ORIGINS`를 서버 환경에 추가해야 한다. 현재 서버 Compose의 `env_file`로 주입할 수 있다.
- `test` 프로필은 URL·CORS 값을 테스트 설정에서 지정하므로 로컬 `.env`의 해당 값에 의존하지 않는다. Spring의 `APP_*` 환경변수·시스템 속성 직접 지정은 테스트 설정도 덮어쓸 수 있다.

## Security 접근 규칙

- `SecurityConfig`는 API 명세의 공개 경로를 HTTP Method까지 일치시켜 허용한다. `GET /session`은 비로그인 접근을 허용하며 나머지 요청은 인증을 요구한다.
- HTTP 세션의 인증 상태를 읽거나 저장하지 않는 `STATELESS` 방식이다. form login, HTTP Basic, Security 기본 로그아웃, 요청 저장은 비활성화한다.
- 브라우저 자동 전송 인증을 사용하지 않는 Bearer 방식에 맞춰 CSRF 필터를 비활성화한다. CORS는 기존 서블릿 필터가 먼저 처리하며 Security 체인에는 중복 등록하지 않는다.
- Security의 인증 실패는 `401 UNAUTHORIZED`, 권한 거부는 `403 FORBIDDEN`이며 기존 `ErrorResponse`를 사용한다. 상세 계약은 [공통 오류 규칙](docs/api/conventions.md#security-인증권한-오류)을 따른다.
- 내부 `ERROR` dispatch는 원래 오류 응답을 유지하기 위해 허용한다. 직접 요청한 `/error`는 인증을 요구한다.
- `JwtAuthenticationFilter`는 검증된 Access Token의 회원 ID를 `MemberPrincipal`로 등록한다. 컨트롤러에서는 `@AuthenticationPrincipal MemberPrincipal`로 받을 수 있다. 토큰 원문과 임의의 역할은 인증 객체에 저장하지 않는다.
- 공개 인증 API는 Access Token 검사를 생략한다. `GET /session`은 토큰 없음·Access Token 만료만 비로그인으로 통과시키며, 그 외 잘못된 토큰은 `401`로 처리한다. 인증 필수 경로는 누락·만료·무효 모두 `401`이다.
- 회원 API는 아직 구현하지 않았다. 회원 조회는 `CurrentMemberService`, Refresh Token 저장·검증·폐기는 `RefreshTokenService`가 제공하며 실제 endpoint와 회원 상태별 인증 허용 정책은 후속 작업이다.
- 역할별 권한 규칙은 정의하지 않는다. `403` 핸들러는 Security에서 권한 거부가 발생할 때 사용하도록 준비한다.

## JWT 설정

`global.config.JwtProperties`에 JWT 발급·검증에 사용할 설정을 등록한다.
`JwtTokenProvider`가 HS256 Access/Refresh Token 발급·검증을 제공하며, Security 필터는 Access Token만 인증에 사용한다. 실제 로그인·갱신 API는 후속 작업이다.

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

## Refresh Token 공통 기능

`domain.member.service.RefreshTokenService`는 기존 `JwtTokenProvider`와 `MemberRepository`를 사용합니다.
로그인·회원가입·재발급·로그아웃 API와 Controller/DTO는 포함하지 않습니다.

| 메서드 | 후속 API 사용 방법 |
| --- | --- |
| `store(memberId, refreshToken)` | `JwtTokenProvider.issueRefreshToken(memberId)`로 발급한 토큰을 검증한 뒤 현재 회원의 토큰을 대체합니다. 회원 생성과 같은 트랜잭션에서 호출할 수 있습니다. |
| `validate(refreshToken)` | JWT 검증과 현재 저장된 해시·만료 시각 대조를 통과한 `Member`를 반환합니다. `getMemberId()`, `getStatus()`로 소유 회원과 상태를 확인합니다. Rotation은 수행하지 않습니다. |
| `revoke(memberId, refreshToken)` | 인증된 현재 회원 ID를 전달합니다. JWT 소유자가 같은지 확인하고 현재 저장된 해당 토큰만 제거합니다. 정상 JWT의 동일 소유자가 반복 호출하거나 과거 토큰을 제출하면 저장값을 바꾸지 않고 종료합니다. |

- 회원당 현재 Refresh Token 하나를 저장하고 폐기 이력은 남기지 않습니다. 확정 정책은 [회원 API 문서](docs/api/member.md#refresh-token-공통-저장-정책)를 따릅니다.
- `store`와 `revoke`는 토큰 컬럼만 갱신하는 UPDATE를 사용합니다. `revoke`는 회원 ID와 해시를 함께 조건으로 사용하여 교체된 새 토큰을 지우지 않습니다.
- 갱신 전에 영속성 컨텍스트의 변경을 flush하고 갱신 후 clear합니다. 호출 전에 조회한 엔티티는 분리되므로, 이후 추가 변경이 필요하면 다시 조회해야 합니다. 회원 생성·토큰 저장은 호출 서비스의 트랜잭션에 함께 참여합니다.
- `Member`의 토큰 컬럼은 읽기 전용으로 매핑하여 일반 엔티티 저장이 이전 토큰을 복구하지 않게 합니다. 토큰 변경은 위 서비스로 수행합니다.
- `TokenValidationException`의 `INVALID`는 JWT 무효·소유자 불일치·회원 또는 현재 저장 토큰 부재·저장값 불일치, `EXPIRED`는 JWT 만료를 뜻합니다. 폐기 이력이 없으므로 미저장·교체·폐기를 구분하지 않습니다.
- 회원 상태별 허용 여부와 HTTP 오류 매핑은 후속 인증 API에서 결정합니다. JWT 예외를 그대로 컨트롤러 밖으로 전달하면 공통 핸들러에서 예상하지 못한 오류로 처리되므로, 각 API는 확정된 계약의 `ErrorCode`와 `BusinessException`으로 연결해야 합니다.
- 기존 Access Token은 만료까지 유효합니다. 토큰 갱신 권한을 하나로 제한하며 다른 기기의 Access Token을 즉시 차단하지 않습니다.

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
