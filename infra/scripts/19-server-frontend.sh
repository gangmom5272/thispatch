#!/usr/bin/env bash
# 서버1 — 프론트 정적 파일을 놓을 자리 준비
#
#   bash 19-server-frontend.sh
#
# 왜 필요한가
#   nginx 가 /var/www/dispatch/current 를 서빙한다. 그런데 그 폴더를 채우는
#   것은 Jenkins(uid 1000)다. 기본 소유자가 www-data 라 그대로는 쓸 수 없다.
#
# 왜 심링크 구조인가
#   /var/www/dispatch/
#     releases/12/   빌드 12 의 산출물
#     releases/13/   빌드 13 의 산출물
#     current -> releases/13
#
#   배포는 current 심링크를 바꾸는 것 하나뿐이다. rename 한 번이라 중간
#   상태가 없다. 폴더를 비우고 채우는 방식이면 그 사이에 들어온 요청이
#   404 를 받는다.
#   되돌릴 때도 심링크만 옮기면 된다.
#     ln -sfn /var/www/dispatch/releases/12 /var/www/dispatch/current.tmp
#     mv -Tf /var/www/dispatch/current.tmp /var/www/dispatch/current
#
# ⚠ 폴더를 mv 로 통째로 바꾸면 안 된다
#   /var/www/dispatch 는 compose 가 Jenkins 컨테이너에 마운트한 지점이라,
#   컨테이너 안에서 옮기려 하면 Device or resource busy 가 난다. (실측)
#
# 몇 번 돌려도 된다.
set -euo pipefail

WEB_ROOT=${WEB_ROOT:-/var/www/dispatch}
OWNER=${OWNER:-ubuntu}

echo "── [1/3] 폴더와 소유자 ────────────────────────────────"
sudo mkdir -p "$WEB_ROOT/releases"
echo "    전: $(stat -c '%U:%G %a' "$WEB_ROOT")"
# nginx 는 www-data 로 '읽기만' 한다. 755 면 충분하고 소유자는 Jenkins 쪽에 준다.
sudo chown -R "$OWNER:$OWNER" "$WEB_ROOT"
sudo chmod 755 "$WEB_ROOT"
echo "    후: $(stat -c '%U:%G %a' "$WEB_ROOT")"

echo
echo "── [2/3] current 심링크 ───────────────────────────────"
if [ -L "$WEB_ROOT/current" ]; then
  echo "    이미 있음 -> $(readlink -f "$WEB_ROOT/current")"
else
  # 아직 배포 전이면 자리표시자를 릴리스 0 으로 만들어 둔다.
  # 이게 없으면 nginx 가 없는 경로를 root 로 잡아 403 을 돌려준다.
  mkdir -p "$WEB_ROOT/releases/0"
  if [ -f "$WEB_ROOT/index.html" ]; then
    mv "$WEB_ROOT/index.html" "$WEB_ROOT/releases/0/"
    echo "    기존 index.html 을 releases/0 으로 옮김"
  elif [ ! -f "$WEB_ROOT/releases/0/index.html" ]; then
    printf '%s\n' '<!doctype html><meta charset="utf-8"><title>Dispatch</title><h1>Dispatch</h1><p>배포 준비 중입니다.</p>' \
      > "$WEB_ROOT/releases/0/index.html"
    echo "    자리표시자 생성"
  fi
  ln -sfn "$WEB_ROOT/releases/0" "$WEB_ROOT/current"
  echo "    만듦 -> $(readlink -f "$WEB_ROOT/current")"
fi
chmod -R a+rX "$WEB_ROOT"

echo
echo "── [3/3] 검증 ─────────────────────────────────────────"
sudo nginx -t 2>&1 | tail -1 | sed 's/^/    /'
sudo systemctl reload nginx
sleep 1
echo "    구조:"
ls -la "$WEB_ROOT" | sed 's/^/      /'
echo
curl -sS -m 10 -o /dev/null -w "    / → %{http_code}\n" https://j15a202.p.ssafy.io/ || true
curl -sS -m 10 -o /dev/null -w "    /deep/route → %{http_code}  (SPA 라우팅. 200 이어야 한다)\n" https://j15a202.p.ssafy.io/deep/route || true
echo
echo "  이제 Jenkins 의 dispatch-frontend 잡이 여기를 채웁니다."
echo "  정의는 frontend/Jenkinsfile 입니다."
