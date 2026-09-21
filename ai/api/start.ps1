# This Patch AI 서버 기동 (Windows, 개발기). 사용자가 직접 실행한다 - 자동 기동 없음.
#   .\start.ps1            포트 8100
#   .\start.ps1 -Port 8200
# 순서: Ollama 확인(없으면 띄움) → FastAPI 시작(모델 워밍업은 서버가 백그라운드로) → /health 가 ready 될 때까지 표시
param([int]$Port = 8100, [string]$Py = "$env:USERPROFILE\miniforge3\envs\py313\python.exe")
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $env:OLLAMA_URL) { $env:OLLAMA_URL = "http://127.0.0.1:11434" }
# GMS 키: 저장소 밖 api.txt(GMS_API='...') 에서 읽어 환경 변수로만 넘긴다. 없으면 폴백 없이 돈다.
if (-not $env:GMS_API_KEY) {
  $keyFile = Join-Path $here "..\..\..\api.txt"
  if (Test-Path $keyFile) {
    $m = [regex]::Match((Get-Content $keyFile -Raw), "GMS_API='([^']+)'")
    if ($m.Success) { $env:GMS_API_KEY = $m.Groups[1].Value; Write-Host "[ok] GMS 폴백 키 로드" } else { Write-Host "[..] api.txt 에 GMS_API 없음 — 폴백 없음" }
  } else { Write-Host "[..] GMS 키 없음 — /trends/summarize 는 Qwen 실패 시 문장 틀로만" }
}

try { Invoke-RestMethod "$env:OLLAMA_URL/api/version" -TimeoutSec 3 | Out-Null; Write-Host "[ok] Ollama 응답" }
catch {
  Write-Host "[..] Ollama 시작"; Start-Process ollama -ArgumentList "serve" -WindowStyle Hidden
  $t = 0; do { Start-Sleep 2; $t += 2; try { Invoke-RestMethod "$env:OLLAMA_URL/api/version" -TimeoutSec 2 | Out-Null; break } catch {} } while ($t -lt 60)
  if ($t -ge 60) { throw "Ollama 가 60초 안에 뜨지 않음" }
  Write-Host "[ok] Ollama 응답 ($t s)"
}

# 로그는 파일에도 남긴다. 창을 닫으면 기록이 사라져서 "요청이 왔었는지, 몇 초 걸렸는지"를
# 나중에 확인할 수 없다. uvicorn 은 접속 로그를 stderr 로 내보내므로 그쪽이 본 로그다.
$logDir = Join-Path $here "..\logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$log = Join-Path $logDir "api-$stamp.log"
$outLog = Join-Path $logDir "api-$stamp.out.log"

Write-Host "[..] FastAPI 시작 :$Port (로그: $log)"
# -u 는 파이썬 출력 모으기를 끈다. 화면이 아닌 파일로 보내면 기본이 모아 쓰기라
# 로그가 종료 전까지 0바이트로 남는다.
$srv = Start-Process -FilePath $Py -ArgumentList "-u -m uvicorn main:app --host 0.0.0.0 --port $Port" -WorkingDirectory $here -PassThru -NoNewWindow -RedirectStandardError $log -RedirectStandardOutput $outLog
$t = 0
do {
  Start-Sleep 3; $t += 3
  try { $h = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 3 } catch { $h = $null }
  if ($h) { Write-Host ("[{0,3}s] {1}  embedder={2} qwen={3} {4}" -f $t, $h.status, $h.embedder_loaded, $h.qwen_loaded, $h.warmup_error) }
} while (-not ($h -and $h.ready) -and $t -lt 240 -and -not $srv.HasExited)
if ($h -and $h.ready) { Write-Host "[ok] READY  http://<이 PC IP>:$Port  (백엔드에 알리면 됨). 종료: Ctrl+C" }
else { Write-Host "[!!] 240초 안에 ready 안 됨. 위 warmup_error 확인" }

# 로그 파일을 따라가며 창에도 그대로 보여준다(전에 창에 찍히던 것과 같다).
# Ctrl+C 로 따라가기를 멈추면 서버만 남아 떠도므로 finally 에서 같이 내린다.
try { Get-Content $log -Wait -Tail 50 }
finally {
  if (-not $srv.HasExited) { Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue }
  Write-Host "[..] 서버 종료. 로그: $log"
}
