#!/usr/bin/env bash
# 서버1 (j15a202.p.ssafy.io) — UFW 포트 개방
#
#   ssh -i ~/.ssh/J15A202T.pem ubuntu@j15a202.p.ssafy.io
#   bash 14-server-ufw.sh
#
# 여는 것은 80 · 443 두 개뿐이다
#   Nginx 가 앞단에서 받는다. 프론트 정적은 /, 백엔드는 /api/.
#   443 은 Let's Encrypt 로 j15a202.p.ssafy.io 인증서를 붙인다 (S15P21A202-19).
#
# ⚠ 젠킨스 포트는 열지 않는다
#   젠킨스는 18080 에 뜨지만 localhost 바인딩으로 두고 Nginx 뒤에 둔다.
#   젠킨스는 사실상 배포 권한을 쥔 물건이라 로그인 화면을 인터넷에 평문으로
#   띄울 이유가 없다. 초기 설정은 SSH 터널로 한다.
#     ssh -i ~/.ssh/J15A202T.pem -L 18080:localhost:18080 ubuntu@j15a202.p.ssafy.io
#
# ⚠ 5432 는 절대 열지 않는다
#   SSAFY 공지 — AWS 클라우드 방화벽이 tcp/udp 1024~65535 를 이미 허용한다.
#   즉 AWS 쪽은 5432 가 이미 통과 상태이고, 막고 있는 것은 UFW 하나뿐이다.
#   여기서 여는 순간 전 세계에서 우리 DB 에 붙을 수 있다.
#   노트북 클러스터가 서버1 DB 에 붙어야 할 일이 생기면 SSH 터널을 쓴다.
#
# ⚠ 22 를 잃으면 서버를 못 살린다
#   SSAFY 공지 — "SSH 포트 차단, 공개키 삭제, 퍼미션 임의 변경 등으로 접속
#   불가 시 복구 불가(초기화 요청만 가능)".
#
#   그래서 이 스크립트는 allow 만 쓴다.
#   deny · delete · reset · disable · default 는 한 줄도 쓰지 않는다.
#   규칙을 더하기만 하므로 원리적으로 잠길 수 없다.
#   그래도 실행 전후로 22 가 살아 있는지 확인하고, 아니면 즉시 멈춘다.
#
# 몇 번 돌려도 된다 (ufw allow 는 중복 시 "Skipping adding existing rule")
set -euo pipefail

need_22() {
  sudo ufw status | grep -qE '^22[[:space:]]+ALLOW'
}

echo "── [1/4] 사전 확인 ────────────────────────────────────"
sudo ufw status verbose | sed 's/^/    /'

if ! sudo ufw status | grep -q '^Status: active'; then
  echo "UFW 가 비활성입니다. SSAFY 규칙상 활성이어야 합니다." >&2
  echo "이 스크립트는 enable 을 하지 않습니다 — 22 확보를 확인한 뒤 직접 켜세요." >&2
  exit 1
fi

if ! need_22; then
  echo "22 번이 허용 목록에 없습니다. 지금 상태로는 손대면 위험합니다." >&2
  echo "먼저 'sudo ufw allow 22/tcp' 를 직접 넣고 다시 실행하세요." >&2
  exit 1
fi
echo "    22 확보 확인 · 기본 정책 유지 · allow 만 추가합니다."

echo
echo "── [2/4] 22 재확인 (멱등 · 안전핀) ────────────────────"
# 이미 있으므로 아무 일도 안 일어난다. 순서상 22 를 먼저 박아두는 것이 목적이다.
sudo ufw allow 22/tcp

echo
echo "── [3/4] 80 · 443 개방 ────────────────────────────────"
# /tcp 를 명시한다. 번호만 쓰면 udp 까지 같이 열린다.
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

echo
echo "── [4/4] 사후 검증 ────────────────────────────────────"
sudo ufw status verbose | sed 's/^/    /'

fail=0
need_22 || { echo "22 가 사라졌습니다. 지금 세션을 끊지 마세요." >&2; fail=1; }
sudo ufw status | grep -qE '^80/tcp[[:space:]]+ALLOW'  || { echo "80 개방 실패" >&2; fail=1; }
sudo ufw status | grep -qE '^443/tcp[[:space:]]+ALLOW' || { echo "443 개방 실패" >&2; fail=1; }

# 열려 있으면 안 되는 것
if sudo ufw status | grep -qE '^5432'; then
  echo "5432 가 열려 있습니다. 즉시 'sudo ufw delete allow 5432' 로 닫으세요." >&2
  fail=1
fi
if sudo ufw status | grep -qE '^18080'; then
  echo "18080(젠킨스)이 열려 있습니다. Nginx 뒤에 두기로 했으므로 닫으세요." >&2
  fail=1
fi

[ "$fail" -eq 0 ] || exit 1

echo
echo "    22 · 80 · 443 만 열려 있습니다. 5432 · 18080 노출 없음."
echo
echo "다음 — 밖에서 80 이 실제로 닿는지 확인하세요 (로컬에서)."
echo "    curl -sS -m 10 -o /dev/null -w '%{http_code}' http://j15a202.p.ssafy.io"
echo
echo "  Connection refused  → 방화벽 통과. 아직 Nginx 가 없어서 그렇다 (정상)"
echo "  타임아웃            → AWS 보안그룹이 80 을 막고 있다. SSAFY 에 문의"
echo
echo "  SSAFY 공지에 80 허용과 '22·8989·443만 접속 가능' 이 함께 적혀 있어"
echo "  모순이다. Let's Encrypt HTTP-01 이 80 으로 들어오므로 미리 확인해야 한다."
