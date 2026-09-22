# AI 서버(8100)를 서버1 안쪽으로 넘기는 SSH 역터널. 백엔드가 호출할 경로를 만든다.
#
# 왜 역터널인가
#   EC2 에서 교육장 노트북 대역(70.12.x)으로 나가는 라우팅이 없다(9/10 인프라 실측:
#   포트를 열어도 ping 조차 안 간다). 반대 방향인 노트북 -> 서버1 22번은 열려 있다.
#   그래서 우리가 서버로 붙어서 포트를 거꾸로 넘긴다.
#
# 왜 172.17.0.1 인가
#   백엔드가 도커 컨테이너 안에서 돈다. 역터널을 기본값(127.0.0.1)에 묶으면 호스트에서만
#   보이고 컨테이너에서는 안 보인다. 도커 브리지 주소에 묶어야 컨테이너가 닿는다.
#   서버1 sshd_config 에 `GatewayPorts clientspecified` 가 있어야 이 바인딩이 허용된다.
#   0.0.0.0 으로 열지 않는 이유는 그러면 AWS 방화벽이 1024~65535 를 이미 허용하고 있어
#   바깥에 그대로 노출되기 때문이다.
#
# 확인   서버1 에서:  curl http://172.17.0.1:8100/health
# 사용   .\tunnel.ps1                       (기본값으로 연결)
#        .\tunnel.ps1 -Key C:\path\to.pem   (키 위치 지정)
param(
    [string]$ServerHost = "j15a202.p.ssafy.io",
    [string]$User = "ubuntu",
    [string]$Key = "$env:USERPROFILE\.ssh\thispatch-ai-tunnel",
    [int]$Port = 8100,
    [string]$Bind = "172.17.0.1",
    [int]$RetrySeconds = 10,
    [int]$PortBusyRetrySeconds = 30,
    [int]$PortBusyMaxMinutes = 10
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Key)) {
    Write-Host "SSH 키가 없습니다: $Key" -ForegroundColor Red
    Write-Host "터널 전용 키를 만들고 공개키를 서버1 에 등록해야 합니다:"
    Write-Host "  ssh-keygen -t ed25519 -f `$env:USERPROFILE\.ssh\thispatch-ai-tunnel -N '' -C thispatch-ai-tunnel@ai-node"
    Write-Host "  그 뒤 .pub 내용을 인프라 담당에게 전달 (docs/backend-connection.md 참고)"
    exit 1
}

# 로컬 AI 서버가 떠 있는지 먼저 본다. 없으면 터널만 뚫려 502 가 난다.
try {
    $health = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 5
    if (-not $health.ready) { Write-Host "AI 서버가 아직 준비 중입니다(ready=false). 계속 진행합니다." -ForegroundColor Yellow }
    else { Write-Host "로컬 AI 서버 ready" -ForegroundColor Green }
} catch {
    Write-Host "로컬 $Port 에 AI 서버가 없습니다. start.ps1 로 먼저 켜세요." -ForegroundColor Red
    exit 1
}

Write-Host "역터널 연결: $User@$ServerHost 안쪽 ${Bind}:$Port -> 이 노트북 127.0.0.1:$Port"
Write-Host "끊기면 $RetrySeconds 초 뒤 다시 붙습니다. 중지하려면 Ctrl+C."

# 로그를 파일에도 남긴다. 터널은 조용히 끊기고 백엔드 쪽에서만 실패로 보이므로,
# 나중에 "언제 왜 끊겼나"를 확인할 수 있어야 한다(9/21 원인 미상으로 끊긴 사례).
$logDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..\logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir ("tunnel-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")
function Say($msg, $color = "Gray") {
    $line = "{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Write-Host $line -ForegroundColor $color
    Add-Content -Path $log -Value $line -Encoding UTF8
}
Say "로그: $log"
Say "역터널 시작 ${Bind}:$Port -> 127.0.0.1:$Port ($User@$ServerHost)"

# 노트북은 절전·무선 전환으로 연결이 자주 끊긴다. 죽으면 다시 붙는다.
#
# 곧바로 끊기는 경우를 둘로 나눈다(9/21).
#   포트 점유  서버1 에 이전 접속이 아직 8100 을 잡고 있는 것. 우리가 고칠 수 없고 몇 분이면 저절로 풀린다.
#             여기서 종료해 버리면 네트워크가 돌아와도 사람이 손대기 전까지 터널이 죽은 채로 남는다.
#   그 외      키·권한·GatewayPorts 같은 설정 문제. 기다려도 낫지 않으므로 바로 멈추고 알린다.
$busySince = $null
while ($true) {
    $started = Get-Date
    $err = Join-Path $env:TEMP ("thispatch-tunnel-{0}.err" -f $PID)
    $p = Start-Process -FilePath "ssh" -NoNewWindow -Wait -PassThru -RedirectStandardError $err -ArgumentList @(
        "-N",
        "-o", "ExitOnForwardFailure=yes",
        "-o", "ServerAliveInterval=30",
        "-o", "ServerAliveCountMax=3",
        "-o", "StrictHostKeyChecking=accept-new",
        "-i", $Key,
        "-R", "${Bind}:${Port}:127.0.0.1:${Port}",
        "$User@$ServerHost")
    $stderr = if (Test-Path $err) { (Get-Content $err -Raw) } else { "" }
    Remove-Item $err -ErrorAction SilentlyContinue
    if ($stderr) { Add-Content -Path $log -Value $stderr.TrimEnd() -Encoding UTF8 }

    $lasted = [int]((Get-Date) - $started).TotalSeconds
    $portBusy = $stderr -match "remote port forwarding failed"

    if ($lasted -lt 5 -and $portBusy) {
        if (-not $busySince) { $busySince = Get-Date }
        $waited = [int]((Get-Date) - $busySince).TotalMinutes
        if ($waited -ge $PortBusyMaxMinutes) {
            Say "서버1 의 $Port 가 $PortBusyMaxMinutes 분째 풀리지 않습니다. 인프라 담당에게 확인을 요청하세요." "Red"
            Say "  서버1 에서: sudo ss -tlnp | grep $Port  (남아 있는 sshd: ubuntu 세션 종료)" "Red"
            exit 1
        }
        Say "서버1 의 $Port 가 아직 이전 접속에 잡혀 있습니다. $PortBusyRetrySeconds 초 뒤 다시 시도합니다(${waited}분째)." "Yellow"
        Start-Sleep -Seconds $PortBusyRetrySeconds
        continue
    }

    if ($lasted -lt 5) {
        # 설정 문제다. 무한 재시도로 감추지 않는다.
        Say "${lasted}초 만에 끊겼습니다. 설정을 확인하세요." "Red"
        Say "  - 서버1 sshd_config 에 GatewayPorts clientspecified 가 있는지 (없으면 $Bind 바인딩이 거부됩니다)" "Red"
        Say "  - 키 권한과 사용자 이름" "Red"
        if ($stderr) { Say ("  ssh: " + ($stderr.Trim() -replace "\s+", " ")) "Red" }
        exit 1
    }

    $busySince = $null
    Say "연결이 끊겼습니다(${lasted}초 유지). $RetrySeconds 초 뒤 재연결합니다." "Yellow"
    Start-Sleep -Seconds $RetrySeconds
}
