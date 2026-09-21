// 디스패치 배포 파이프라인 — S15P21A202-20
//
// GitLab 의 develop 에 push 되면 Jenkins 가 이 파일을 읽어 아래를 돌린다.
//   체크아웃 → 테스트 → 배포본 갱신 → 이미지 빌드 → 기동 → 검증
//
// 어디서 도는가
//   Jenkins 는 컨테이너 안에서 돌지만, docker 명령은 '호스트' 데몬에게 간다
//   (소켓 마운트). 그래서 여기서 만든 컨테이너는 Jenkins 의 자식이 아니라
//   호스트에 뜨는 '형제' 다.
//
// ⚠ 그래서 경로가 중요하다
//   docker 에게 넘기는 경로는 전부 '호스트 기준' 으로 해석된다.
//   compose.server.yaml 이 /var/jenkins_home 과 /home/ubuntu/thispatch 를
//   컨테이너 안에도 똑같은 경로로 마운트해 둔 이유가 이것이다.
//   경로를 다르게 잡으면 빌드 컨텍스트가 '없는 디렉터리' 를 가리킨다.

pipeline {
    agent any

    // ⚠ 트리거는 여기 없다.
    //   infra/jenkins/jenkins.yaml 의 잡 정의에 들어 있다.
    //   이유 두 가지
    //     1. 웹훅 시크릿을 써야 하는데, 그걸 이 파일에 적으면 저장소에 남는다.
    //     2. 여기 적으면 '빌드를 한 번 돌려야' 트리거가 등록된다.
    //        웹훅을 먼저 걸어도 첫 번째 push 는 무시된다.

    options {
        timestamps()
        // ⚠ 동시 실행을 막는다.
        //   두 빌드가 같은 배포 폴더와 같은 이름의 테스트 DB 를 건드리면
        //   서로를 지운다.
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 40, unit: 'MINUTES')
    }

    environment {
        // 서버1 의 배포 정본. 여기 있는 것이 지금 돌고 있는 것이다.
        DEPLOY_DIR   = '/home/ubuntu/thispatch'
        COMPOSE_FILE = '/home/ubuntu/thispatch/infra/compose.server.yaml'

        // 테스트용 임시 DB. 서비스 DB 와 완전히 다른 컨테이너다.
        // ⚠ 서비스 DB(thispatch) 에 테스트를 돌리면 안 된다.
        //   ThispatchApplicationTests 가 테이블 목록과 시드 데이터를 '정확히'
        //   비교해서, 실제 데이터가 있으면 통과할 수 없다.
        TEST_DB_NAME = 'thispatch_test'
        TEST_DB_CONT = 'thispatch-test-db'
        TEST_REDIS_CONT = 'thispatch-test-redis'
        NETWORK      = 'thispatch_default'
    }

    stages {

        stage('준비') {
            steps {
                sh '''
                    echo "커밋   $(git rev-parse --short HEAD)"
                    echo "메시지 $(git log -1 --pretty=%s)"
                    echo
                    echo "도커   $(docker version --format '{{.Server.Version}}')"
                    echo "자바   $(java -version 2>&1 | head -1)"
                    test -d "$DEPLOY_DIR" || { echo "$DEPLOY_DIR 가 없습니다. compose 의 마운트를 확인하세요."; exit 1; }
                    test -f "$DEPLOY_DIR/infra/.env" || { echo "$DEPLOY_DIR/infra/.env 가 없습니다."; exit 1; }
                '''
            }
        }

        stage('변경 범위 확인') {
            steps {
                // 프론트만 바뀐 push 에서는 백엔드를 다시 빌드·배포하지 않는다.
                //
                // 기준점은 Jenkins 가 넣어 주는 GIT_PREVIOUS_SUCCESSFUL_COMMIT 이다.
                // '마지막으로 성공한 빌드의 커밋' 이라, 지난 빌드가 실패했으면
                // 그때 못 본 변경까지 함께 비교된다. 실패를 건너뛰고 넘어가지 않는다.
                //
                // ⚠ 판단을 셸에서 하고 결과만 파일로 넘긴다.
                //   Groovy 로 하면 Jenkins 스크립트 보안 승인이 걸릴 수 있다.
                sh '''
                    BASE="${GIT_PREVIOUS_SUCCESSFUL_COMMIT:-}"
                    RUN=yes
                    REASON="처음이거나 직전 빌드가 실패했다 — 전부 돌린다"
                    if [ -n "$BASE" ] && git cat-file -e "$BASE^{commit}" 2>/dev/null; then
                      CHANGED=$(git diff --name-only "$BASE" HEAD)
                      OUTSIDE=$(echo "$CHANGED" | grep -vE "^(frontend|ai)/" || true)
                      if [ -z "$CHANGED" ]; then
                        RUN=no;  REASON="바뀐 파일이 없다"
                      elif [ -z "$OUTSIDE" ]; then
                        RUN=no;  REASON="frontend/ 또는 ai/ 만 바뀌었다 (백엔드 이미지에 포함되지 않음)"
                      else
                        RUN=yes; REASON="백엔드 쪽 변경이 있다"
                      fi
                      echo "기준 커밋 $(git rev-parse --short "$BASE")"
                      echo "바뀐 파일 $(echo "$CHANGED" | grep -c . || true) 개"
                      echo "$CHANGED" | head -10 | sed "s/^/  /"
                    fi
                    echo
                    echo "판단: $RUN  ($REASON)"
                    printf %s "$RUN" > .run_flag
                '''
                script { env.RUN_BUILD = readFile('.run_flag').trim() }
            }
        }

        stage('테스트') {
            when { expression { env.RUN_BUILD == 'yes' } }
            steps {
                // ⚠ 비밀번호를 소스에 적어도 되는 유일한 자리다.
                //   이 DB 는 빌드가 끝나면 지워지고, 도커 내부 네트워크에만 있으며,
                //   바깥으로 포트를 열지 않는다. 서비스 비밀번호와 같게 쓰지 말 것.
                sh '''
                    set -e
                    TEST_PW=ci-throwaway-$(date +%s)

                    echo "── 임시 DB 를 띄운다 (pgvector 포함) ──"
                    docker rm -f "$TEST_DB_CONT" >/dev/null 2>&1 || true
                    docker run -d --name "$TEST_DB_CONT" --network "$NETWORK" \
                      -e POSTGRES_DB="$TEST_DB_NAME" \
                      -e POSTGRES_USER=postgres \
                      -e POSTGRES_PASSWORD="$TEST_PW" \
                      pgvector/pgvector:pg17 >/dev/null

                    echo "── 준비될 때까지 기다린다 ──"
                    ok=0
                    for i in $(seq 1 40); do
                      if docker exec "$TEST_DB_CONT" pg_isready -U postgres -d "$TEST_DB_NAME" >/dev/null 2>&1; then
                        ok=1; break
                      fi
                      sleep 2
                    done
                    [ "$ok" = 1 ] || { docker logs "$TEST_DB_CONT" | tail -20; echo "테스트 DB 가 뜨지 않았습니다."; exit 1; }

                    echo
                    echo "── 테스트 실행 ──"
                    # ⚠ 컨테이너 이름이 곧 호스트명이다. 같은 도커 네트워크라 DNS 로 찾는다.
                    #   Jenkins 컨테이너도 thispatch_default 에 붙어 있어서 가능하다.
                    export POSTGRES_HOST="$TEST_DB_CONT"
                    export POSTGRES_PORT=5432
                    export POSTGRES_USER=postgres
                    export POSTGRES_PASSWORD="$TEST_PW"
                    export POSTGRES_DB="$TEST_DB_NAME"

                    # ⚠ 반드시 지운다.
                    #   이 값이 남아 있으면 테스트가 dev 프로파일로 떠서
                    #   테스트 DB 가 아니라 '서비스 DB' 를 보려 한다. (실측)
                    #   compose 에서 env_file 을 없앤 지금은 비어 있지만,
                    #   누군가 다시 넣어도 여기서 막힌다.
                    unset SPRING_PROFILES_ACTIVE

                    echo "── 테스트 전용 Redis ──"
                    docker rm -fv "$TEST_REDIS_CONT" >/dev/null 2>&1 || true
                    docker run -d --name "$TEST_REDIS_CONT" --network "$NETWORK" \
                      redis:8.2-alpine redis-server --save '' --appendonly no >/dev/null
                    ok=0
                    for i in $(seq 1 20); do
                      if docker exec "$TEST_REDIS_CONT" redis-cli ping | grep -q PONG; then
                        ok=1; break
                      fi
                      sleep 1
                    done
                    [ "$ok" = 1 ] || { echo "테스트 Redis 가 뜨지 않았습니다."; exit 1; }
                    export REDIS_TEST_HOST="$TEST_REDIS_CONT"
                    export REDIS_TEST_PORT=6379
                    export REDIS_INTEGRATION_TEST=true

                    chmod +x gradlew
                    ./gradlew :common:test :backend:test --no-daemon
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml'
                    sh 'docker rm -fv "$TEST_DB_CONT" "$TEST_REDIS_CONT" >/dev/null 2>&1 || true'
                }
            }
        }

        stage('배포본 갱신') {
            steps {
                // 워크스페이스의 내용을 배포 정본으로 옮긴다.
                //
                // ⚠ .env 는 저장소에 없으므로 덮이지 않는다. 그대로 남는다.
                // ⚠ 저장소에서 지운 파일이 서버에서 따라 지워지지는 않는다 (덮어쓰기만 한다).
                sh '''
                    set -e
                    tar -C "$WORKSPACE" -cf - --exclude=.git --exclude=build --exclude=.gradle . \
                      | tar -C "$DEPLOY_DIR" -xf -
                    echo "갱신 완료"
                    echo "  compose  $(stat -c %y "$COMPOSE_FILE" | cut -c1-19)"
                    echo "  .env     $(stat -c %y "$DEPLOY_DIR/infra/.env" | cut -c1-19)  (그대로)"
                '''
            }
        }

        stage('이미지 빌드') {
            when { expression { env.RUN_BUILD == 'yes' } }
            steps {
                sh '''
                    set -e
                    cd "$DEPLOY_DIR/infra"
                    docker compose -f "$COMPOSE_FILE" build backend
                    docker images thispatch/backend --format '  {{.Repository}}:{{.Tag}}  {{.Size}}  {{.CreatedSince}}'
                '''
            }
        }

        stage('배포') {
            when { expression { env.RUN_BUILD == 'yes' } }
            steps {
                // ⚠ --no-deps 를 반드시 붙인다.
                //   빼면 jenkins 서비스까지 다시 만들려 들고, 그건 지금 이 빌드를
                //   돌리고 있는 컨테이너 자신이다.
                sh '''
                    set -e
                    cd "$DEPLOY_DIR/infra"
                    docker compose -f "$COMPOSE_FILE" up -d --no-deps --wait --wait-timeout 60 redis
                    docker compose -f "$COMPOSE_FILE" up -d --no-deps backend

                    echo "── healthy 가 될 때까지 기다린다 ──"
                    ok=0
                    for i in $(seq 1 36); do
                      st=$(docker inspect backend --format '{{.State.Health.Status}}' 2>/dev/null || echo none)
                      echo "  $i/36  $st"
                      [ "$st" = healthy ] && { ok=1; break; }
                      sleep 5
                    done
                    [ "$ok" = 1 ] || { echo "기동 실패"; docker logs --tail 60 backend; exit 1; }
                '''
            }
        }

        stage('검증') {
            when { expression { env.RUN_BUILD == 'yes' } }
            steps {
                sh '''
                    set -e
                    echo "── 컨테이너 직접 ──"
                    code=$(curl -sS -o /dev/null -w '%{http_code}' -m 10 http://backend:8080/ || echo 000)
                    echo "  http://backend:8080/ → $code"
                    # 401 이면 정상이다. Spring Security 가 막은 것이고 앱은 살아 있다.
                    # 000 은 응답 자체가 없다는 뜻이라 실패로 친다.
                    if [ "$code" = 000 ]; then echo "응답이 없습니다."; exit 1; fi

                    echo "── nginx 경유 ──"
                    curl -sS -o /dev/null -w '  /api/ → %{http_code}\n' -m 15 https://j15a202.p.ssafy.io/api/ \
                      || echo "  (바깥 주소 확인 실패 — 컨테이너에서 공인 주소로 나가는 경로 문제일 수 있다)"
                '''
            }
        }
    }

    post {
        always {
            sh '''
                docker rm -fv "$TEST_DB_CONT" "$TEST_REDIS_CONT" >/dev/null 2>&1 || true
                echo "── 지금 도는 것 ──"
                docker ps --format '  {{.Names}}  {{.Status}}'
            '''
        }
        failure {
            echo '실패했다. 서비스는 직전 버전으로 계속 돌고 있다.'
        }
    }
}
