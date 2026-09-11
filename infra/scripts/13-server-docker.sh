#!/usr/bin/env bash
# 서버1 (j15a202.p.ssafy.io) — Docker Engine + Compose 플러그인
#
#   ssh -i ~/.ssh/J15A202T.pem ubuntu@j15a202.p.ssafy.io
#   bash 13-server-docker.sh
#
# 왜 필요한가
#   CI/CD 가 붙으면 develop push 마다 젠킨스가 이미지를 만들고
#   `docker compose up -d` 로 갈아끼운다. 그 실행 기반이다.
#
# ⚠ PostgreSQL 은 컨테이너로 올리지 않는다
#   서버1 에는 PostgreSQL 17.11 이 네이티브로 이미 돌고 있다
#   (02-postgres.sh 로 설치. pgvector 0.8.6 · pg_trgm 포함, 0.0.0.0:5432 점유).
#
#   backend/compose.yaml 에도 PostgreSQL 컨테이너가 있는데 그건 로컬 개발용이다.
#   서버에서 그대로 `docker compose up` 하면 5432 충돌로 안 뜬다.
#
#   서버에서는 네이티브를 그대로 쓴다. 이유 셋.
#     1. 배포 사고 반경. push 마다 compose 가 도는데 DB 가 같은 파일에 있으면
#        앱을 배포할 때마다 DB 가 사정권에 들어온다.
#     2. 이미 pgvector·튜닝이 다 들어가 있다. 옮길 이유가 없다.
#     3. 옮길 데이터도 없다 (설치 시점 테이블 0개).
#
#   그래서 서버용 compose 는 따로 만든다 (infra/compose.server.yaml).
#   백엔드 컨테이너 → 호스트 PostgreSQL 은 이렇게 붙는다.
#     extra_hosts:  ["host.docker.internal:host-gateway"]
#     POSTGRES_HOST: host.docker.internal
#
# ⚠ UFW 는 건드리지 않는다
#   포트 개방은 S15P21A202-21 의 일이다. 이 스크립트는 방화벽을 만지지 않는다.
#   SSAFY 공지 — "SSH 포트 차단 시 복구 불가, 초기화 요청만 가능".
#
# 몇 번 돌려도 된다 (멱등)
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

# needrestart 가 기본값(i)이면 TTY 를 잡으려다 멈추거나, a 로 두면 서비스를
# 멋대로 재시작한다. 여기서는 PostgreSQL 이 돌고 있으므로 목록만 찍게 한다(l).
# 실측: 이 설정으로 dbus·logind 등이 "deferred" 로 미뤄졌고 PostgreSQL 은
# 재시작 목록에 오르지 않았다.
export NEEDRESTART_MODE=l

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "이미 설치돼 있습니다 — $(docker --version) / $(docker compose version)"
  echo "그룹만 확인하고 끝냅니다."
else
  echo "── [1/5] apt 갱신 · 선행 패키지 ───────────────────────"
  sudo apt-get update -qq
  sudo apt-get install -y -qq ca-certificates curl >/dev/null

  echo "── [2/5] Docker GPG 키 ────────────────────────────────"
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
       -o /etc/apt/keyrings/docker.asc
  sudo chmod a+r /etc/apt/keyrings/docker.asc

  echo "── [3/5] 저장소 등록 ──────────────────────────────────"
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -qq

  echo "── [4/5] Docker 설치 (수 분) ──────────────────────────"
  sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io \
       docker-buildx-plugin docker-compose-plugin >/dev/null
fi

echo "── [5/5] ubuntu 를 docker 그룹에 ──────────────────────"
# 재로그인해야 적용된다. 지금 세션에서는 sudo docker 로 확인한다.
sudo usermod -aG docker "${SUDO_USER:-$USER}"

echo
echo "═══ 확인 ═══"
sudo docker --version
sudo docker compose version
echo "서비스     : $(systemctl is-active docker) / $(systemctl is-enabled docker)"
echo "docker 그룹: $(getent group docker)"

echo
echo "═══ PostgreSQL 무사한가 ═══"
echo "상태  : $(systemctl is-active postgresql)"
echo "5432  : $(ss -tln | grep -c ':5432') 개 리슨  (0 이면 사고다)"

echo
echo "다음 — 재로그인해야 sudo 없이 docker 를 씁니다."
echo "       exit 후 다시 ssh 로 붙어서 'docker ps' 로 확인하세요."
