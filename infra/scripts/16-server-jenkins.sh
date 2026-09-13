#!/usr/bin/env bash
# 서버1 (j15a202.p.ssafy.io) — Jenkins 컨테이너
#
#   scp -i ~/.ssh/J15A202T.pem -r infra ubuntu@j15a202.p.ssafy.io:~/
#   ssh -i ~/.ssh/J15A202T.pem ubuntu@j15a202.p.ssafy.io
#   cp infra/.env.example infra/.env && vi infra/.env    # 비밀번호를 직접 넣는다
#   bash infra/scripts/16-server-jenkins.sh
#
# 설정 마법사를 쓰지 않는다
#   JCasC(infra/jenkins/jenkins.yaml)가 관리자 계정 · 보안 · URL · GitLab
#   연결을 코드로 넣는다. 브라우저 클릭이 필요 없고, 서버가 날아가도
#   이 스크립트 한 번으로 같은 상태가 된다.
#
#   이미 마법사 모드로 뜬 적이 있으면 jenkins_home 을 비워야 한다.
#     RESET=1 bash infra/scripts/16-server-jenkins.sh
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

echo "── [1/6] 선행 확인 ────────────────────────────────────"
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
echo "── [1.5/6] .env 확인 ──────────────────────────────────"
ENV_FILE="$COMPOSE_DIR/.env"
[ -f "$ENV_FILE" ] || {
  echo "infra/.env 가 없습니다. 템플릿을 복사해 비밀번호를 넣으세요:" >&2
  echo "  cp $COMPOSE_DIR/.env.example $ENV_FILE && vi $ENV_FILE" >&2
  exit 1; }
grep -q '^JENKINS_ADMIN_PASSWORD=.\+' "$ENV_FILE" || {
  echo "infra/.env 에 JENKINS_ADMIN_PASSWORD 가 비어 있습니다." >&2; exit 1; }
grep -q '^JENKINS_ADMIN_PASSWORD=change-me$' "$ENV_FILE" && {
  echo "JENKINS_ADMIN_PASSWORD 가 템플릿 기본값(change-me)입니다. 바꾸세요." >&2; exit 1; }
chmod 600 "$ENV_FILE"

# ⚠ 값을 이 쉘로 읽어 온다.
#   compose 는 같은 폴더의 .env 를 알아서 읽지만, 이 스크립트 자신은
#   읽지 않는다. [5/6] 에서 Jenkins 에 로그인해서 웹훅 시크릿을
#   심으려면 JENKINS_ADMIN_* 와 GITLAB_WEBHOOK_SECRET 이 필요하다.
set -a; . "$ENV_FILE"; set +a
echo "    .env 확인 · 권한 600 · 값 읽음"

echo
echo "── [2/6] jenkins_home 준비 ────────────────────────────"
# 마법사 모드로 뜬 적이 있으면 JCasC 가 안 먹는다. 비우고 다시 만든다.
if [ "${RESET:-0}" = "1" ]; then
  echo "    RESET=1 — 기존 jenkins_home 을 비웁니다"
  docker rm -f jenkins >/dev/null 2>&1 || true
  sudo rm -rf /var/jenkins_home
elif sudo test -f /var/jenkins_home/secrets/initialAdminPassword \
     && ! sudo test -f /var/jenkins_home/jenkins.install.InstallUtil.lastExecVersion; then
  echo "    마법사 모드로 뜬 흔적이 있습니다. JCasC 를 적용하려면 비워야 합니다." >&2
  echo "    설정한 내용이 없다면:  RESET=1 bash $0" >&2
  exit 1
fi
# 컨테이너의 jenkins 사용자가 uid 1000 이다. 소유자를 맞추지 않으면
# Jenkins 가 기동하자마자 권한 오류로 죽는다.
sudo install -d -o 1000 -g 1000 -m 755 /var/jenkins_home
echo "    $(stat -c '%n  %U:%G (%u:%g)  %a' /var/jenkins_home)"

echo
echo "── [3/6] 이미지 빌드 (docker CLI 포함) ────────────────"
# 공식 이미지에는 docker 명령이 없다. infra/jenkins/Dockerfile 이 CLI 만 넣는다.
docker compose -f "$COMPOSE_FILE" build jenkins

echo
echo "── [4/6] 기동 ─────────────────────────────────────────"
docker compose -f "$COMPOSE_FILE" up -d jenkins

echo "    healthcheck 대기 (최대 3분)..."
for i in $(seq 1 36); do
  st="$(docker inspect -f '{{.State.Health.Status}}' jenkins 2>/dev/null || echo starting)"
  [ "$st" = "healthy" ] && { echo "    healthy ($((i*5))초)"; break; }
  [ "$i" -eq 36 ] && { echo "    ⚠ 아직 $st — 로그를 확인하세요" >&2; }
  sleep 5
done

echo
echo "── [5/6] 웹훅 시크릿 심기 ────────────────────"
# ⚠ 왜 여기서 따로 하는가
#   웹훅 시크릿은 Jenkins 가 암호화해서 보관하는 값이라, jenkins.yaml 의
#   잡 정의에서 XML 로 직접 넣으면 Jenkins 가 읽으면서 버린다. (실측)
#   그래서 기동이 끝난 뒤에 Jenkins 에게 직접 시켜 넣는다.
#
# ⚠ 재기동할 때마다 다시 해야 한다.
#   JCasC 가 부팅할 때마다 잡을 정의대로 다시 만들어서, 손으로 넣은 값은
#   날아간다. 이 스크립트를 거쳐서 올리면 항상 맞춰진다.
if [ -z "${GITLAB_WEBHOOK_SECRET:-}" ] || [ "${GITLAB_WEBHOOK_SECRET}" = "change-me" ]; then
  echo "    ⚠ .env 의 GITLAB_WEBHOOK_SECRET 이 비어 있습니다. 웹훅이 모두 거부됩니다."
  echo "      openssl rand -hex 24 로 만들어 .env 에 넣고 다시 실행하세요."
else
  JB=http://127.0.0.1:18080/jenkins
  CJ="$(mktemp)"
  CRUMB="$(curl -s -u "$JENKINS_ADMIN_ID:$JENKINS_ADMIN_PASSWORD" -c "$CJ"             "$JB/crumbIssuer/api/json" | sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p')"
  if [ -z "$CRUMB" ]; then
    echo "    ⚠ Jenkins 로그인 실패 — JENKINS_ADMIN_ID/PASSWORD 를 확인하세요." >&2
  else
    GROOVY="import com.dabsquared.gitlabjenkins.GitLabPushTrigger
def job = jenkins.model.Jenkins.get().getItemByFullName('dispatch-deploy')
if (job == null) { println '    잡을 아직 못 찾았다'; return }
def t = job.getTriggers().values().find { it instanceof GitLabPushTrigger }
if (t == null) { println '    GitLabPushTrigger 가 없다'; return }
t.setSecretToken('$GITLAB_WEBHOOK_SECRET')
job.save()
println '    시크릿 심음: ' + (t.getSecretToken() == '$GITLAB_WEBHOOK_SECRET')"
    curl -s -u "$JENKINS_ADMIN_ID:$JENKINS_ADMIN_PASSWORD" -b "$CJ" -c "$CJ"       -H "Jenkins-Crumb: $CRUMB" --data-urlencode "script=$GROOVY" "$JB/scriptText"
  fi
  rm -f "$CJ"
fi

echo
echo "── [6/6] 검증 ─────────────────────────────────────────"
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
echo "    JCasC 적용 여부"
docker logs jenkins 2>&1 | grep -iE 'configuration-as-code|casc' | tail -3 | sed 's/^/      /' \
  || echo "      (로그에 CasC 흔적 없음 — 확인 필요)"
echo
echo "    설치된 플러그인 수: $(docker exec jenkins sh -c 'ls /var/jenkins_home/plugins/*.jpi 2>/dev/null | wc -l')"

echo
echo "설정 마법사는 꺼져 있습니다. initialAdminPassword 를 쓰지 않습니다."
echo "로그인 — https://j15a202.p.ssafy.io/jenkins/login"
echo "  아이디   infra/.env 의 JENKINS_ADMIN_ID"
echo "  비밀번호 infra/.env 의 JENKINS_ADMIN_PASSWORD"
