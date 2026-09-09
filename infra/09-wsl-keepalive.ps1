# WSL 이 저절로 꺼지지 않게 만든다 — 클러스터 노드 전 대수에 필요
#
# 왜 필요한가
#   WSL2 는 마지막 세션이 닫히면 유휴 타이머(기본 60초)가 돌아 VM 을 통째로
#   내린다. 그러면 systemd 도 하둡 데몬도 같이 죽는다.
#   실측: 터미널을 닫고 조회하니 `wsl -l -v` 가 Stopped 였고, HDFS 가
#   Connection refused 였다.
#
#   매일 오전 9시 배치가 돌아야 하는데, 그 시간에 아무도 터미널을 열어두지
#   않으면 클러스터가 통째로 내려가 있다.
#
# 무엇을 하는가
#   1. %UserProfile%\.wslconfig 에 vmIdleTimeout=-1 (유휴 종료 끄기)
#   2. 시작프로그램에 조용히 도는 세션 하나를 등록 (로그인하면 WSL 자동 기동)
#
#   관리자 권한이 필요 없다.
#
# 실행
#     Set-ExecutionPolicy -Scope Process Bypass -Force
#     .\09-wsl-keepalive.ps1
#
#   되돌리기:
#     .\09-wsl-keepalive.ps1 -Remove

param(
    [switch]$Remove,
    [string]$Distro = 'Ubuntu-22.04'
)

$ErrorActionPreference = 'Stop'
$cfg     = "$env:USERPROFILE\.wslconfig"
$startup = [Environment]::GetFolderPath('Startup')
$vbs     = Join-Path $startup 'dispatch-wsl-keepalive.vbs'

if ($Remove) {
    Write-Host "── 되돌리기 ──────────────────────────────────"
    if (Test-Path $vbs) { Remove-Item $vbs -Force; Write-Host "  시작프로그램 항목 삭제" }
    else { Write-Host "  시작프로그램 항목 없음" }
    if (Test-Path $cfg) {
        $t = Get-Content $cfg -Raw
        $t = $t -replace '(?ms)\r?\n?\[experimental\]\r?\n(vmIdleTimeout\s*=\s*-1\r?\n?)', ''
        Set-Content -Path $cfg -Value $t.TrimEnd() -Encoding utf8
        Write-Host "  .wslconfig 에서 vmIdleTimeout 제거"
    }
    Write-Host "  적용하려면:  wsl --shutdown"
    exit 0
}

Write-Host "── [1/3] .wslconfig ──────────────────────────"

if (-not (Test-Path $cfg)) {
    Set-Content -Path $cfg -Value "[wsl2]`nnetworkingMode=mirrored" -Encoding utf8
    Write-Host "  새로 생성"
}

$text = Get-Content $cfg -Raw

# 미러링 모드가 없으면 넣는다 (클러스터의 전제)
if ($text -notmatch 'networkingMode\s*=\s*mirrored') {
    if ($text -match '(?m)^\[wsl2\]') {
        $text = $text -replace '(?m)^\[wsl2\]', "[wsl2]`r`nnetworkingMode=mirrored"
    } else {
        $text = "[wsl2]`r`nnetworkingMode=mirrored`r`n" + $text
    }
    Write-Host "  networkingMode=mirrored 추가"
}

# 유휴 종료 끄기. vmIdleTimeout 은 [experimental] 아래에 둔다.
if ($text -notmatch 'vmIdleTimeout') {
    if ($text -match '(?m)^\[experimental\]') {
        $text = $text -replace '(?m)^\[experimental\]', "[experimental]`r`nvmIdleTimeout=-1"
    } else {
        $text = $text.TrimEnd() + "`r`n`r`n[experimental]`r`nvmIdleTimeout=-1`r`n"
    }
    Write-Host "  vmIdleTimeout=-1 추가 (유휴 자동 종료 끔)"
} else {
    Write-Host "  vmIdleTimeout 이미 설정됨"
}

Set-Content -Path $cfg -Value $text.TrimEnd() -Encoding utf8
Write-Host ""
Get-Content $cfg | ForEach-Object { Write-Host "    $_" }

Write-Host ""
Write-Host "── [2/3] 시작프로그램 등록 ────────────────────"

# vmIdleTimeout 만으로는 부팅 후 WSL 이 스스로 뜨지 않는다.
# 로그인할 때 조용한 세션을 하나 띄워서 배포판을 기동시킨다.
# 창이 뜨지 않도록 VBScript 로 감싼다 (Run 의 세 번째 인자 0 = 숨김).
$line = 'CreateObject("WScript.Shell").Run "wsl.exe -d ' + $Distro + ' -- sleep infinity", 0, False'
Set-Content -Path $vbs -Value $line -Encoding ASCII
Write-Host "  $vbs"
Write-Host "  내용: $line"

Write-Host ""
Write-Host "── [3/3] 지금 적용 ───────────────────────────"
Write-Host "  wsl --shutdown 후 재기동합니다 (진행 중인 작업이 있으면 중단됩니다)"
wsl --shutdown
Start-Sleep -Seconds 3
Start-Process -FilePath 'wscript.exe' -ArgumentList "`"$vbs`"" -WindowStyle Hidden
Start-Sleep -Seconds 20

Write-Host ""
Write-Host "── 확인 ─────────────────────────────────────"
wsl -l -v
Write-Host ""
Write-Host "  Running 이면 성공입니다."
Write-Host "  90초쯤 뒤에 다시 'wsl -l -v' 를 쳐서 Running 이 유지되는지 보세요."
Write-Host ""
Write-Host "  되돌리려면:  .\09-wsl-keepalive.ps1 -Remove" -ForegroundColor DarkGray
