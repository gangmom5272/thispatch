# This Patch AI 서버 기동 (Windows, 개발기). 사용자가 직접 실행한다 - 자동 기동 없음.
#   .\start.ps1            포트 8100
#   .\start.ps1 -Port 8200
# 순서: Ollama 확인(없으면 띄움) → FastAPI 시작(모델 워밍업은 서버가 백그라운드로) → /health 가 ready 될 때까지 표시
param([int]$Port = 8100, [string]$Py = "$env:USERPROFILE\miniforge3\envs\py313\python.exe")
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $env:OLLAMA_URL) { $env:OLLAMA_URL = "http://127.0.0.1:11434" }

try { Invoke-RestMethod "$env:OLLAMA_URL/api/version" -TimeoutSec 3 | Out-Null; Write-Host "[ok] Ollama 응답" }
catch {
  Write-Host "[..] Ollama 시작"; Start-Process ollama -ArgumentList "serve" -WindowStyle Hidden
  $t = 0; do { Start-Sleep 2; $t += 2; try { Invoke-RestMethod "$env:OLLAMA_URL/api/version" -TimeoutSec 2 | Out-Null; break } catch {} } while ($t -lt 60)
  if ($t -ge 60) { throw "Ollama 가 60초 안에 뜨지 않음" }
  Write-Host "[ok] Ollama 응답 ($t s)"
}

Write-Host "[..] FastAPI 시작 :$Port (로그는 이 창)"
$srv = Start-Process -FilePath $Py -ArgumentList "-m uvicorn main:app --host 0.0.0.0 --port $Port" -WorkingDirectory $here -PassThru -NoNewWindow
$t = 0
do {
  Start-Sleep 3; $t += 3
  try { $h = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 3 } catch { $h = $null }
  if ($h) { Write-Host ("[{0,3}s] {1}  embedder={2} qwen={3} {4}" -f $t, $h.status, $h.embedder_loaded, $h.qwen_loaded, $h.warmup_error) }
} while (-not ($h -and $h.ready) -and $t -lt 240 -and -not $srv.HasExited)
if ($h -and $h.ready) { Write-Host "[ok] READY  http://<이 PC IP>:$Port  (백엔드에 알리면 됨). 종료: Ctrl+C 또는 Stop-Process -Id $($srv.Id)" }
else { Write-Host "[!!] 240초 안에 ready 안 됨. 위 warmup_error 확인" }
Wait-Process -Id $srv.Id
