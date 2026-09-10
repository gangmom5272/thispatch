# 로컬 PostgreSQL 개발 환경

Docker Desktop의 Linux 컨테이너 엔진과 Docker Compose가 필요합니다.
아래 명령은 모두 `backend` 디렉터리에서 실행합니다.

## 구성

- `compose.yaml`: `pgvector/pgvector:pg17` 기반 PostgreSQL 단일 서비스, 로컬 5432 포트, 상태 검사 및 데이터 볼륨.
- `docker/postgres/init/01-pgvector.sql`: 최초 DB 초기화 시 `vector` 확장 활성화. Flyway migration이 아니며 테이블을 생성하지 않습니다.
- `.env`: 로컬 DB 접속 정보. Git에 커밋하지 않습니다.
- `.env.example`: 필요한 환경 변수의 예시 파일.
- `src/main/resources/application.yaml`: 공통 JPA·Flyway 설정. Hibernate는 `ddl-auto: validate`로 스키마를 검증만 하고 생성·수정하지 않습니다. `open-in-view`는 비활성화하고 Flyway는 활성화하며 migration 위치는 `classpath:db/migration`입니다.
- `src/main/resources/application-dev.yaml`: `dev` 프로필에서 `.env`를 읽어 Spring Boot 접속 설정에 사용합니다. Spring Batch 스키마 자동 생성은 비활성화합니다.

기존 애플리케이션 이름, Gradle 의존성 및 저장소의 서버용 `infra` 설정은 유지합니다.
JPA Entity와 Flyway migration은 추가하지 않습니다.
현재는 migration SQL이 없으므로 적용할 migration은 0개입니다. Flyway는 자체 이력 관리용
`flyway_schema_history` 테이블을 생성할 수 있습니다. Entity가 추가되면 그에 맞는 스키마가
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

## 종료 및 데이터 유지

```powershell
docker compose down
```

컨테이너를 종료·제거해도 named volume의 DB 데이터는 유지됩니다.
다시 `docker compose up -d --wait`를 실행하면 기존 데이터를 사용합니다.
`docker compose down -v`는 DB 데이터 볼륨까지 삭제하므로 데이터를 유지하려면 사용하지 마세요.

초기화 SQL은 데이터 볼륨이 비어 있을 때만 실행됩니다. 기존 볼륨에 `vector` 확장이 없는 경우
데이터를 삭제하지 않고 다음 명령으로 활성화할 수 있습니다.

```powershell
docker compose exec postgres psql -U thispatch -d thispatch -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

참고: [pgvector Docker 이미지](https://github.com/pgvector/pgvector#docker),
[PostgreSQL 공식 이미지의 볼륨 및 초기화 동작](https://hub.docker.com/_/postgres).
