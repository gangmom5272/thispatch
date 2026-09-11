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
| embedding 타입 | `format_type`으로 `patch_chunk.embedding = vector(768)` 확인 |
| 재기동 시 중복 적용 방지 | 재기동 전후 migration 이력 전체 비교, Flyway validate 및 미적용 migration 0개, 코드 데이터 재확인 |

테스트는 애플리케이션 기동으로 migration을 적용한 뒤 조회로 검증합니다. Entity, Controller, Service는 필요하지 않습니다.
결과는 `build/reports/tests/test/index.html`에서 확인할 수 있습니다.
이 테스트의 기대값은 현재 V1/V2 설계를 기준으로 하며, 새 migration을 추가할 때 함께 갱신합니다.

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
