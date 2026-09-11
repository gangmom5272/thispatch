# 디스패치 클러스터 — WSL 인바운드 방화벽 규칙
#
# 왜 필요한가
#   WSL2 미러링 모드에서 WSL 의 인바운드는 Hyper-V 방화벽에 걸린다.
#   기본값이 DefaultInboundAction = Block 이라, 이걸 열지 않으면
#   팀원 노트북이 이 노트북의 WSL 포트에 하나도 붙지 못한다.
#
#   · 마스터  : 워커의 DataNode/NodeManager 가 9000·8031 로 붙어야 한다.
#   · 워커    : 마스터의 SSH(22), 다른 노드의 HDFS 블록 읽기(9866),
#               Spark 셔플/블록매니저가 붙어야 한다.
#   → 5대 전부 실행해야 한다.
#
# 노출 범위
#   RemoteAddresses 를 70.12.0.0/16 (SSAFY 교육장 대역)으로 제한한다.
#   EC2 서버에서 노트북으로 ping 이 안 되는 것으로 이 대역이 외부로
#   라우팅되지 않는 것을 확인했다.
#
# 실행
#   PowerShell 을 "관리자 권한으로 실행" 후:
#     Set-ExecutionPolicy -Scope Process Bypass -Force
#     .\06-firewall.ps1
#
#   되돌리기:
#     .\06-firewall.ps1 -Remove

param(
    [switch]$Remove,
    [string]$Allowed = '70.12.0.0/16'
)

$ErrorActionPreference = 'Stop'
$WSL_VM = '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}'   # WSL 의 고정 VMCreatorId

# 관리자 확인
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
              [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "관리자 권한이 필요합니다. PowerShell 을 관리자로 다시 열어 주세요." -ForegroundColor Red
    exit 1
}

# 포트 목록 — 이름, 포트
#
# New-NetFirewallHyperVRule 은 LocalPorts 에 쉼표 목록을 받지 않는다.
# 단일 포트나 범위만 허용된다 (실측: '9864,9866,9867' → "The port is invalid").
# 그래서 쉼표로 묶고 싶은 것은 규칙을 따로 만든다.
$rules = @(
    @{ n = 'ssh';                p = '22' }
    @{ n = 'hdfs-namenode-rpc';  p = '9000' }
    @{ n = 'hdfs-namenode-web';  p = '9870' }
    @{ n = 'hdfs-datanode-http'; p = '9864' }   # 웹 UI
    @{ n = 'hdfs-datanode-data'; p = '9866' }   # 블록 전송 — 이게 없으면 데이터가 안 흐른다
    @{ n = 'hdfs-datanode-ipc';  p = '9867' }
    @{ n = 'yarn-rm';            p = '8030-8033' }
    @{ n = 'yarn-rm-web';        p = '8088' }
    @{ n = 'yarn-nm-localizer';  p = '8040' }
    # RM 이 컨테이너를 띄우려고 접속하는 포트. 04-wsl-node.sh 에서 8041 로 고정했다.
    # 이게 막히면 앱이 ACCEPTED 에서 영원히 멈춘다.
    @{ n = 'yarn-nm-container';  p = '8041' }
    @{ n = 'yarn-nm-web';        p = '8042' }
    @{ n = 'mr-shuffle';         p = '13562' }
    @{ n = 'spark-ui';           p = '4040-4060' }
    @{ n = 'spark-history';      p = '18080' }
    # spark-defaults.conf 의 driver.port 17177 / driver.blockManager 17210 /
    # blockManager 17240 + port.maxRetries 30
    @{ n = 'spark-driver';       p = '17177-17270' }

    # 마스터 전용 — 11-master-batch-infra.sh 로 깔린 것들.
    # 워커에도 규칙이 생기지만 그쪽엔 서비스가 없어서 아무 일도 일어나지 않는다.
    # Spring Batch 원격 파티셔닝에서 워커가 마스터의 이 둘에 붙는다.
    @{ n = 'batch-postgres';     p = '5432' }   # 배치 메타데이터 DB
    @{ n = 'batch-rabbitmq';     p = '5672' }   # 작업 분배 (AMQP)
    @{ n = 'batch-rabbitmq-ui';  p = '15672' }  # 큐 상태를 눈으로 보는 관리 화면
)

if ($Remove) {
    Write-Host "── 규칙 삭제 ──────────────────────────────────"
    foreach ($r in $rules) {
        $name = "dispatch-$($r.n)"
        try {
            Remove-NetFirewallHyperVRule -Name $name -ErrorAction Stop
            Write-Host "  삭제 $name"
        } catch {
            Write-Host "  없음 $name"
        }
    }
    Write-Host "완료."
    exit 0
}

Write-Host "── WSL 인바운드 규칙 추가 ─────────────────────"
Write-Host "   허용 출처: $Allowed"
Write-Host ""

$made = 0; $failed = 0
foreach ($r in $rules) {
    $name = "dispatch-$($r.n)"
    # 멱등하게: 있으면 지우고 다시 만든다
    try { Remove-NetFirewallHyperVRule -Name $name -ErrorAction SilentlyContinue } catch {}
    try {
    New-NetFirewallHyperVRule `
        -Name            $name `
        -DisplayName     "Dispatch $($r.n) (WSL)" `
        -VMCreatorId     $WSL_VM `
        -Direction       Inbound `
        -Protocol        TCP `
        -LocalPorts      $r.p `
        -RemoteAddresses $Allowed `
        -Action          Allow -ErrorAction Stop | Out-Null
        Write-Host ("  {0,-24} TCP {1}" -f $r.n, $r.p)
        $made++
    } catch {
        Write-Host ("  {0,-24} TCP {1}   실패: {2}" -f $r.n, $r.p, $_.Exception.Message) -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
Write-Host "── 확인 ──────────────────────────────────────"
Get-NetFirewallHyperVRule |
    Where-Object { $_.Name -like 'dispatch-*' } |
    Select-Object Name, Protocol, LocalPorts, RemoteAddresses, Action |
    Format-Table -AutoSize | Out-String -Width 160 | Write-Host

Write-Host "── VM 기본 정책 (Block 이어야 정상. 위 규칙만 예외) ──"
Get-NetFirewallHyperVVMSetting -Name $WSL_VM |
    Select-Object Name, DefaultInboundAction, DefaultOutboundAction |
    Format-Table -AutoSize | Out-String -Width 160 | Write-Host

Write-Host ("규칙 {0}개 생성, {1}개 실패" -f $made, $failed)
if ($failed -gt 0) {
    Write-Host "실패한 규칙이 있습니다. 위 오류를 확인하세요." -ForegroundColor Red
    exit 1
}
Write-Host "완료. WSL 재시작 없이 즉시 적용됩니다." -ForegroundColor Green
