#!/usr/bin/env bash
# 서버1 (j15a202.p.ssafy.io) — Jenkins 컨테이너
#
#   scp -i ~/.ssh/J15A202T.pem -r infra ubuntu@j15a202.p.ssafy.io:~/
#   ssh -i ~/.ssh/J15A202T.pem ubuntu@j15a202.p.ssafy.io
#   bash infra/scripts/16-server-jenkins.sh
#
# 왜 서버1 에 두는가
#   서버2A 가 놀고 있어서 빌드를 분리하는 안을 검토했으나, 서버2A 는 HDFS
#   백업 보관 전용으로 쓰기로 했다. 백업 서버에 빌드 부하와 CI 권한을 섞지
#   않는다는 판단이다.
#
#   대신 같은 서버를 쓰는 대가를 compose 에서 막는다 — Jenkins 컨테이너에
#   2.5 vCPU · 6Gi 상한을 걸어 빌드가 서비스를 굶기지 못하게 한다.
#   (서버1 은 4 vCPU · 15Gi)
#
# 접속 경로
#   밖에서는 https://j15a202.p.ssafy.io/jenkins/ 하나뿐이다.
#   18080 은 127.0.0.1 바인딩이고 UFW 에도 열지 않았다.
#
#   nginx 를 붙이기 전이나 문제가 생겼을 때는 SSH 터널로 직접 본다.
#     ssh -i ~/.ssh/J15A202T.pem -L 18080:localhost:18080 ubuntu@j15a202.p.ssafy.io
#     → 브라우저에서 http://localhost:18080/jenkins/
#
# 여러 번 돌려도 된다.
set -euo pipefail

COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$COMPOSE_DIR/compose.server.yaml"

[ -f "$COMPOSE_FILE" ] || { echo "compose 파일 없음: $COMPOSE_FILE" >&2; exit 1; }

echo "── [1/5] 선행 확인 ────────────────────────────────────"
command -v docker >/dev/null || { echo "docker 가 없습니다. 13-server-docker.sh 먼저." >&2; exit 1; }
docker compose version >/dev/null || { echo "compose 플러그인이 없습니다." >&2; exit 1; }

# 18080 을 다른 것이 쥐고 있으면 멈춘다 (jenkins 자신은 제외)
INTRUDER="$(sudo ss -tlnpH 2>/dev/null | grep -E ':18080[[:space:]]' \
            | grep -v 'docker-proxy' || true)"
[ -z "$INTRUDER" ] || { echo "18080 점유: $INTRUDER" >&2; exit 1; }

# 소켓 GID 를 호스트에서 읽어 build arg 로 넘긴다.
# 하드코딩하면 다른 서버에서 소켓 권한이 안 맞는다.
DOCKER_GID="$(getent group docker | cut -d: -f3)"
[ -n "$DOCKER_GID" ] || { echo "docker 그룹을 못 찾았습니다." >&2; exit 1; }
export DOCKER_GID
echo "    docker 그룹 GID = $DOCKER_GID"

echo
echo "── [2/5] jenkins_home 준비 ────────────────────────────"
# 컨테이너의 jenkins 사용자가 uid 1000 이다. 소유자를 맞추지 않으면
# Jenkins 가 기동하자마자 권한 오류로 죽는다.
sudo install -d -o 1000 -g 1000 -m 755 /var/jenkins_home
echo "    $(stat -c '%n  %U:%G (%u:%g)  %a' /var/jenkins_home)"

echo
echo "── [3/5] 이미지 빌드 (docker CLI 포함) ────────────────"
# 공식 이미지에는 docker 명령이 없다. infra/jenkins/Dockerfile 이 CLI 만 넣는다.
docker compose -f "$COMPOSE_FILE" build jenkins

echo
echo "── [4/5] 기동 ─────────────────────────────────────────"
docker compose -f "$COMPOSE_FILE" up -d jenkins

echo "    healthcheck 대기 (최대 3분)..."
for i in $(seq 1 36); do
  st="$(docker inspect -f '{{.State.Health.Status}}' jenkins 2>/dev/null || echo starting)"
  [ "$st" = "healthy" ] && { echo "    healthy ($((i*5))초)"; break; }
  [ "$i" -eq 36 ] && { echo "    ⚠ 아직 $st — 로그를 확인하세요" >&2; }
  sleep 5
done

echo
echo "── [5/5] 검증 ─────────────────────────────────────────"
docker compose -f "$COMPOSE_FILE" ps
echo
echo "    컨테이너 안에서 docker 가 되는가 (소켓 마운트 확인)"
docker exec jenkins docker ps --format '      {{.Names}}\t{{.Status}}' \
  && echo "      → 소켓 OK" \
  || echo "      ⚠ 소켓 실패 — DOCKER_GID 가 안 맞을 수 있습니다" >&2
echo
echo "    18080 바인딩 (127.0.0.1 이어야 함)"
ss -tln | grep 18080 | awk '{print "      "$4}'
echo
echo "    nginx 경유"
curl -sS -m 10 -o /dev/null -w "      /jenkins/ → %{http_code}\n" \
  https://j15a202.p.ssafy.io/jenkins/ || true

echo
echo "초기 관리자 비밀번호"
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword 2>/dev/null \
  | sed 's/^/    /' || echo "    (이미 설정을 마쳤거나 아직 생성 전입니다)"
echo
echo "다음 — https://j15a202.p.ssafy.io/jenkins/ 에서 초기 설정"
