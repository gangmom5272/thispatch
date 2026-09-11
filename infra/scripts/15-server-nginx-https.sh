#!/usr/bin/env bash
# 서버1 (j15a202.p.ssafy.io) — Nginx + Let's Encrypt HTTPS
#
#   저장소를 서버에 올려두고 실행한다 (설정 파일을 링크해야 해서).
#     scp -i ~/.ssh/J15A202T.pem -r infra ubuntu@j15a202.p.ssafy.io:~/
#     ssh -i ~/.ssh/J15A202T.pem ubuntu@j15a202.p.ssafy.io
#     CERTBOT_EMAIL=you@example.com bash infra/scripts/15-server-nginx-https.sh
#
#   발급 전에 반드시 한 번 리허설한다 (Let's Encrypt 는 실패도 횟수를 센다).
#     DRY_RUN=1 CERTBOT_EMAIL=... bash infra/scripts/15-server-nginx-https.sh
#
# 왜 도커가 아니라 네이티브인가
#   Nginx 는 '배포가 실패했을 때 살아 있어야 하는' 물건이다. 백엔드와 같은
#   compose 에 두면 push 마다 같이 재시작되고, 배포가 깨지면 사이트 전체가
#   같이 죽는다. PostgreSQL 을 네이티브로 둔 것과 같은 이유다(02-postgres.sh).
#
#   인증서도 네이티브가 훨씬 싸다. certbot --nginx 하나로 발급 · 설정 주입 ·
#   자동갱신 타이머까지 끝난다. 컨테이너면 webroot 볼륨 + 갱신 크론 +
#   reload 훅을 따로 붙여야 한다.
#
#   나중에 컨테이너로 옮겨도 인증서는 /etc/letsencrypt 에 그대로 남는다.
#   바뀌는 것은 갱신 훅 한 줄뿐이다. 단, 옮길 때 systemctl disable --now nginx
#   를 먼저 해야 한다 — 80·443 을 둘이 못 나눠 쓴다.
#
# ⚠ Let's Encrypt 한도
#   같은 도메인에 주 50건, 실패는 시간당 5건까지다. DRY_RUN 으로 먼저 통과를
#   확인하고 본 발급을 한 번만 돌린다.
#
# 여러 번 돌려도 된다. 인증서가 이미 있으면 발급을 건너뛴다.
set -euo pipefail

DOMAIN="${DOMAIN:-j15a202.p.ssafy.io}"
EMAIL="${CERTBOT_EMAIL:-}"
DRY_RUN="${DRY_RUN:-0}"
REPO_CONF="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/nginx/dispatch.conf"

if [ -z "$EMAIL" ]; then
  echo "CERTBOT_EMAIL 환경변수가 필요합니다 (인증서 만료 알림 주소)." >&2
  exit 1
fi
if [ ! -f "$REPO_CONF" ]; then
  echo "설정 파일을 못 찾았습니다: $REPO_CONF" >&2
  echo "저장소를 통째로 서버에 올린 뒤 실행하세요." >&2
  exit 1
fi

echo "── [1/6] 선행 확인 ────────────────────────────────────"
# 80 이 밖에서 닿아야 HTTP-01 인증이 성립한다. UFW 는 14 번 스크립트가 열었다.
sudo ufw status | grep -qE '^80/tcp[[:space:]]+ALLOW' \
  || { echo "UFW 에 80 이 없습니다. 14-server-ufw.sh 를 먼저 도세요." >&2; exit 1; }
# 80·443 을 nginx 아닌 것이 쥐고 있으면 멈춘다.
# nginx 자신은 제외한다 — 재실행하면 이미 떠 있는 게 정상이다.
# (도커로 올린 nginx 나 다른 웹서버가 있으면 여기서 걸린다)
INTRUDER="$(sudo ss -tlnpH 2>/dev/null \
            | grep -E ':(80|443)[[:space:]]' \
            | grep -v 'users:(("nginx"' || true)"
if [ -n "$INTRUDER" ]; then
  echo "80/443 을 nginx 아닌 프로세스가 쓰고 있습니다:" >&2
  echo "$INTRUDER" >&2
  echo "네이티브 nginx 와 충돌합니다. 정리 후 다시 실행하세요." >&2
  exit 1
fi
echo "    80 개방 확인 · 80/443 점유는 nginx 뿐(또는 없음)"

echo
echo "── [2/6] Nginx · certbot 설치 ─────────────────────────"
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l          # PostgreSQL 이 돌고 있다. 자동 재시작 금지
sudo apt-get update -qq
sudo apt-get install -y -qq nginx certbot python3-certbot-nginx >/dev/null
nginx -v
certbot --version

echo
echo "── [3/6] 자리표시자 문서 루트 ─────────────────────────"
# FE 배포 전까지 / 가 403 나지 않게 한다. FE 가 붙으면 이 디렉터리를 덮어쓴다.
sudo install -d -o www-data -g www-data /var/www/dispatch
if [ ! -f /var/www/dispatch/index.html ]; then
  echo '<!doctype html><meta charset="utf-8"><title>Dispatch</title><h1>Dispatch</h1><p>배포 준비 중입니다.</p>' \
    | sudo tee /var/www/dispatch/index.html >/dev/null
  sudo chown www-data:www-data /var/www/dispatch/index.html
fi

echo
echo "── [4/6] 사이트 설정 링크 ─────────────────────────────"
# 저장소 파일을 심볼릭 링크한다. 서버에서 직접 고치면 저장소와 어긋나므로
# 고칠 일이 있으면 저장소를 고치고 다시 올린다.
sudo ln -sfn "$REPO_CONF" /etc/nginx/sites-available/dispatch
sudo ln -sfn /etc/nginx/sites-available/dispatch /etc/nginx/sites-enabled/dispatch
# 기본 사이트를 치운다. 남겨두면 default_server 가 우리 도메인을 가로챌 수 있다.
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
echo "    링크: $(readlink -f /etc/nginx/sites-enabled/dispatch)"

echo
echo "── [5/6] Let's Encrypt 인증서 ─────────────────────────"
if sudo test -d "/etc/letsencrypt/live/$DOMAIN" && [ "$DRY_RUN" != "1" ]; then
  echo "    이미 발급돼 있습니다. 건너뜁니다."
  sudo certbot certificates 2>/dev/null | sed -n "/$DOMAIN/,/Expiry/p" | sed 's/^/    /'
else
  ARGS=(--nginx -d "$DOMAIN" --email "$EMAIL" --agree-tos --no-eff-email
        --redirect --non-interactive)
  if [ "$DRY_RUN" = "1" ]; then
    echo "    [리허설] 실제 발급하지 않습니다."
    sudo certbot certonly --nginx -d "$DOMAIN" --email "$EMAIL" \
         --agree-tos --no-eff-email --non-interactive --dry-run
    echo "    리허설 통과. DRY_RUN 없이 다시 실행하면 본 발급합니다."
    exit 0
  fi
  # --redirect 가 80 → 443 리다이렉트를 dispatch.conf 에 써 넣는다.
  sudo certbot "${ARGS[@]}"
fi

echo
echo "── [6/6] 검증 ─────────────────────────────────────────"
sudo nginx -t
echo "    nginx   : $(systemctl is-active nginx) / $(systemctl is-enabled nginx)"
echo "    자동갱신: $(systemctl is-enabled certbot.timer 2>/dev/null || echo '없음')"
sudo certbot renew --dry-run 2>&1 | tail -3 | sed 's/^/    /'
echo
echo "    리슨 포트"
ss -tln | grep -E ':(80|443)\b' | awk '{print "      "$4}'

echo
echo "⚠ certbot 이 dispatch.conf 를 직접 수정했습니다."
echo "  서버의 파일을 저장소로 되가져와 커밋하세요."
echo "    scp -i ~/.ssh/J15A202T.pem ubuntu@$DOMAIN:$REPO_CONF infra/nginx/dispatch.conf"
echo
echo "다음 — 밖에서 확인"
echo "  curl -I https://$DOMAIN"
echo "  curl -I http://$DOMAIN     (301 로 https 리다이렉트되어야 정상)"
