# 로컬 PostgreSQL 개발 환경

Docker Desktop의 Linux 컨테이너 엔진과 Docker Compose가 필요합니다.
아래 명령은 모두 `backend` 디렉터리에서 실행합니다.

## 구성

- `compose.yaml`: `pgvector/pgvector:pg17` 기반 PostgreSQL 단일 서비스, 로컬 5432 포트, 상태 검사 및 데이터 볼륨.
- `docker/postgres/init/01-pgvector.sql`: 최초 DB 초기화 시 `vector` 확장 활성화. Flyway migration이 아니며 테이블을 생성하지 않습니다.
- `src/main/resources/application-dev.yaml`: 로컬 Spring Boot 접속 설정. `dev` 프로필에서만 적용하며 JPA DDL, Flyway 실행, Spring Batch 스키마 자동 생성을 비활성화합니다.

기존 `application.yaml`, Gradle 의존성 및 저장소의 서버용 `infra` 설정은 유지합니다.
JPA Entity와 Flyway migration은 추가하지 않습니다.

| 항목 | 값 |
| --- | --- |
| 호스트 / 포트 | `localhost:5432` (호스트의 `127.0.0.1`에만 포트 공개) |
| DB명 | `thispatch` |
| 사용자 / 비밀번호 | `thispatch` / `thispatch` (로컬 개발 전용) |
| JDBC URL | `jdbc:postgresql://localhost:5432/thispatch` |
| Docker volume | `thispatch-local_postgres_data` |

## 실행

Docker Desktop을 실행한 뒤 DB를 시작합니다. 최초 실행 시 이미지를 내려받습니다.

```powershell
docker compose up -d --wait
```

이미 5432 포트를 사용하는 로컬 PostgreSQL이나 컨테이너가 있다면 해당 서비스를 중지한 뒤 실행합니다.

Spring Boot는 `dev` 프로필로 실행합니다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=dev"
```

macOS/Linux에서는 `./gradlew bootRun --args='--spring.profiles.active=dev'`를 사용합니다.
IDE에서는 활성 프로필을 `dev`로 지정하거나 환경 변수 `SPRING_PROFILES_ACTIVE=dev`를 설정합니다.

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
