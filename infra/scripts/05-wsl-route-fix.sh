#!/usr/bin/env bash
# WSL2 미러링 모드 기본경로 교정 — 클러스터 노트북 전 대수에 필요
#
# 문제
#   SSAFY 랜의 노트북 Wi-Fi 어댑터에 기본 게이트웨이가 두 개 붙어 있다.
#     70.12.240.1   ValidLifetime 무한      정상
#     192.168.0.1   ValidLifetime 01:00:00  죽은 주소 (ARP 응답 없음)
#   Windows 는 둘 다 시도해서 넘어가지만, WSL 미러링 모드는 둘 중 하나만
#   가져오고 하필 죽은 쪽을 고르는 경우가 있다. 그러면 WSL 이 밖으로 못 나간다.
#   WSL 안에서 default 를 바꿔도 미러링 동기화가 1시간 안에 되돌린다.
#
# 해법
#   기본경로(/0)를 건드리지 않고 0.0.0.0/1 + 128.0.0.0/1 두 개를 살아있는
#   게이트웨이로 깐다. /1 이 /0 보다 구체적이라 우선하고, 미러링 동기화는
#   default 만 관리하므로 되돌아오지 않는다.
#   직접 연결된 서브넷(/21)은 더 구체적이므로 랜 통신은 영향이 없다.
#
#   설치:  sudo bash 05-wsl-route-fix.sh
set -euo pipefail

FIXER=/usr/local/sbin/thispatch-route-fix
[ "$(id -u)" = 0 ] || { echo "sudo 로 실행하세요." >&2; exit 1; }

echo "── 교정 스크립트 설치 ─────────────────────────────"
cat > "$FIXER" <<"FIXEOF"
#!/usr/bin/env bash
# 살아있는 게이트웨이를 찾아 /1 경로 두 개로 고정한다. 멱등.
set -uo pipefail

IFACE=$(ip -4 -o addr show scope global | grep -v " lo " | head -1 | tr -s " " | cut -d" " -f2)
[ -n "${IFACE:-}" ] || { echo "글로벌 IPv4 인터페이스 없음" >&2; exit 1; }

# scope link 로 붙은 게이트웨이 후보를 모아 실제 응답하는 것을 고른다.
CANDS=$(ip route show dev "$IFACE" | grep "scope link" | tr -s " " | cut -d" " -f1 | grep -E "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$")
# 자기 서브넷의 .1 도 후보에 넣는다
SELF=$(ip -4 -o addr show dev "$IFACE" | tr -s " " | cut -d" " -f4)
BASE=$(echo "$SELF" | cut -d/ -f1)
CANDS="$CANDS $(echo "$BASE" | cut -d. -f1-3).1"

GOOD=""
for c in $CANDS; do
  if ping -c1 -W1 -n "$c" >/dev/null 2>&1; then GOOD=$c; break; fi
done

if [ -z "$GOOD" ]; then
  echo "응답하는 게이트웨이를 못 찾았습니다. 후보: $CANDS" >&2
  exit 1
fi

ip route replace 0.0.0.0/1   via "$GOOD" dev "$IFACE"
ip route replace 128.0.0.0/1 via "$GOOD" dev "$IFACE"
echo "게이트웨이 $GOOD ($IFACE) 로 /1 경로 고정"
FIXEOF
chmod +x "$FIXER"

echo "── systemd 등록 ───────────────────────────────────"
cat > /etc/systemd/system/thispatch-route.service <<"SVCEOF"
[Unit]
Description=WSL 미러링 기본경로 교정 (디스패치 클러스터)
After=network.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/thispatch-route-fix
RemainAfterExit=no
SVCEOF

# 미러링 동기화가 언제 덮을지 모르므로 1분마다 다시 확인한다.
# ip route replace 는 멱등이라 부담이 없다.
cat > /etc/systemd/system/thispatch-route.timer <<"TMREOF"
[Unit]
Description=WSL 미러링 기본경로 교정 주기 실행

[Timer]
OnBootSec=5s
OnUnitActiveSec=60s
AccuracySec=5s

[Install]
WantedBy=timers.target
TMREOF

systemctl daemon-reload
systemctl enable --now thispatch-route.timer >/dev/null
systemctl start thispatch-route.service

echo
echo "── 확인 ──────────────────────────────────────────"
systemctl is-enabled thispatch-route.timer
systemctl list-timers thispatch-route.timer --no-pager | head -3
echo
ip route | grep -E "^0.0.0.0/1|^128.0.0.0/1|^default"
echo
printf "인터넷  %s\n" "$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 https://dlcdn.apache.org/)"
echo "✔ 경로 교정 설치 완료"
