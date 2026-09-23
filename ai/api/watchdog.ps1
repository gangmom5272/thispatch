# AI 서버 지킴이 — 죽으면 알아서 다시 띄운다.
#
# 왜 필요한가 (9/23 실측)
#   같은 날 두 번 내려갔고 두 번 다 사람이 알아챌 때까지 비어 있었다.
#     1) 9/22 18:24 절전 -> 9/23 08:59 복귀. 그 사이 서버 없음
#     2) 9/23 12:10 Claude 앱 업데이트로 재시작. 11:19 이후 요청 끊김
#   두 경우 다 "health 에 대답이 없다"로 똑같이 보이므로 한 가지 방법으로 막을 수 있다.
#
# 주의  이 창이 Claude 앱과 같이 죽으면 소용이 없다. 직접 연 PowerShell 창에서 돌려야 한다.
#       바탕화면의 "AI 서버 지킴이.bat" 을 두 번 누르면 그렇게 뜬다.
#
# 사용  .\watchdog.ps1                 기본값(60초마다 확인)
#       .\watchdog.ps1 -Every 30       확인 간격 바꾸기
#       .\watchdog.ps1 -NoTunnel       역터널은 손대지 않기
param(
    [int]$Port = 8100,
    [int]$Every = 60,
    [switch]$NoTunnel,
    [int]$ReadyTimeout = 300
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $here "..\logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir ("watchdog-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")

function Say([string]$text, [string]$color = "Gray") {
    $line = (Get-Date -Format "HH:mm:ss") + " " + $text
    Write-Host $line -ForegroundColor $color
    Add-Content -Path $log -Value $line -Encoding UTF8
}

# 서버가 대답하는지 본다. ready 가 아니면(예열 중) 아직 살아 있는 것이므로 건드리지 않는다.
function Get-Health {
    try { return Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 5 } catch { return $null }
}

function Start-Server {
    Say "서버가 대답하지 않습니다 — 다시 띄웁니다" "Yellow"
    Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $here "start.ps1")
    $waited = 0
    while ($waited -lt $ReadyTimeout) {
        Start-Sleep 5; $waited += 5
        $h = Get-Health
        if ($h -and $h.ready) { Say "복구 완료 ($waited 초)" "Green"; return $true }
    }
    Say "$ReadyTimeout 초 안에 준비되지 않았습니다 — 다음 차례에 다시 봅니다" "Red"
    return $false
}

function Test-Tunnel { return (Get-Process ssh -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0 }

function Start-Tunnel {
    Say "역터널이 없습니다 — 다시 연결합니다" "Yellow"
    Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $here "tunnel.ps1")
    Start-Sleep 10
    if (Test-Tunnel) { Say "역터널 복구" "Green" } else { Say "역터널이 뜨지 않았습니다 — 키를 확인하세요" "Red" }
}

Say "지킴이 시작 — $Every 초마다 확인합니다 (기록: $log)" "Cyan"
Say "멈추려면 Ctrl+C. 이 창을 닫으면 감시도 끝납니다." "Cyan"
$lastOk = $null
while ($true) {
    $h = Get-Health
    if ($null -eq $h) {
        Start-Server | Out-Null
        $lastOk = $null
    }
    elseif (-not $h.ready) {
        Say "예열 중 (embedder=$($h.embedder_loaded) qwen=$($h.qwen_loaded)) — 기다립니다"
    }
    else {
        # 정상일 때는 조용히 있는다. 10분에 한 번만 살아 있다고 적는다.
        if ($null -eq $lastOk -or ((Get-Date) - $lastOk).TotalMinutes -ge 10) {
            Say "정상 (model=$($h.llm_model))" "DarkGray"
            $lastOk = Get-Date
        }
    }
    if (-not $NoTunnel -and -not (Test-Tunnel)) { Start-Tunnel }
    Start-Sleep $Every
}
