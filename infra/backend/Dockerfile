# 서비스 API 이미지 — :backend 모듈
#
# 빌드 컨텍스트는 '저장소 루트' 다. backend/ 가 아니다.
#   docker compose -f infra/compose.server.yaml build backend
#
# 이유: 멀티모듈이라 settings.gradle 과 common/ 이 있어야 :backend 가 빌드된다.
# backend/ 만 컨텍스트로 주면 common 을 못 찾아 실패한다.

# ── 1단계: 빌드 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 의존성만 먼저 받아 레이어로 굳힌다. 소스만 바뀌면 이 단계를 건너뛴다.
# 서버1 이 4 vCPU 라 매번 다 받으면 배포가 느려진다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY common/build.gradle common/
COPY backend/build.gradle backend/
COPY collector/build.gradle collector/
COPY spark/build.gradle spark/
COPY catalog/build.gradle catalog/
RUN chmod +x gradlew \
 && ./gradlew :backend:dependencies --no-daemon -q || true

# 소스는 :backend 와 :common 만 넣는다.
# collector·spark·catalog 는 노트북 클러스터에서 도는 것이라
# 이미지에 들어갈 이유가 없다. build.gradle 만 넣는 것은 Gradle 이
# settings.gradle 에 적힌 모든 모듈의 디렉터리가 있어야 설정을 끝내기 때문이다.
COPY common/src common/src
COPY backend/src backend/src

# 테스트는 여기서 돌리지 않는다.
#   통합 테스트가 진짜 PostgreSQL 을 요구하는데(Flyway 검증) 빌드 컨테이너
#   안에는 DB 가 없다. 테스트는 Jenkins 파이프라인에서 DB 를 띄우고 돌린다.
RUN ./gradlew :backend:bootJar --no-daemon -x test

# ── 2단계: 실행 ──────────────────────────────────────────────
# JDK 가 아니라 JRE 로 줄인다. 이미지가 절반 이하가 된다.
FROM eclipse-temurin:17-jre
WORKDIR /app

# curl 은 compose 의 healthcheck 가 쓴다. JRE 이미지에는 들어 있지 않아서
# 없으면 컨테이너가 영원히 unhealthy 로 남고, 배포 스크립트가 성공을 판정하지
# 못한다. 약 10MB 늘어난다.
RUN apt-get update  && apt-get install -y --no-install-recommends curl  && rm -rf /var/lib/apt/lists/*

# root 로 돌리지 않는다.
RUN useradd -r -u 10001 -m -s /usr/sbin/nologin app
USER app

COPY --from=build --chown=app:app /src/backend/build/libs/backend-*.jar app.jar

# 컨테이너 안에서는 기본 8080. 밖으로는 compose 가 127.0.0.1:18081 로 발행한다.
EXPOSE 8080

ENV TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Seoul"

# 컨테이너가 쓸 수 있는 메모리를 스스로 보고 힙을 잡게 한다.
# -Xmx 를 박으면 compose 의 mem_limit 를 바꿀 때마다 같이 고쳐야 한다.

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
