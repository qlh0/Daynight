<#
.SYNOPSIS
    一键跑 JMeter 压测：重置数据 -> 执行计划 -> 生成HTML报告 -> 校验结果

.DESCRIPTION
    JMeter在GUI模式下跑压测数据不准，所以这里全部用命令行(-n)执行。
    秒杀场景跑完会自动调用 verify.ps1 校验有没有超卖。

.PARAMETER Plan
    测试计划：01-baseline / 02-dish-list / 03-seckill / 04-cache-breakdown

.PARAMETER Threads
    线程数（并发用户数），不传则用计划里的默认值

.PARAMETER Loops
    每线程循环次数，不传则用计划里的默认值

.EXAMPLE
    .\run-jmeter.ps1 -Plan 03-seckill
    .\run-jmeter.ps1 -Plan 03-seckill -Threads 100 -Stock 10
    .\run-jmeter.ps1 -Plan 02-dish-list -Threads 50 -Loops 20
#>
[CmdletBinding()]
param(
    [ValidateSet('01-baseline', '02-dish-list', '03-seckill', '04-cache-breakdown')]
    [string]$Plan = '03-seckill',
    [int]$Threads = 0,
    [int]$Loops = 0,
    [int]$Port = 8080,
    [int]$SeckillId = 1,
    [int]$Stock = 10,
    [switch]$SkipReset,
    [string]$JmeterHome = 'D:\develop\apache-jmeter-5.6.3',
    [string]$JavaHome = 'D:\develop'
)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$jmx = Join-Path $root "jmeter\$Plan.jmx"

if (-not (Test-Path $jmx)) { throw "找不到测试计划: $jmx" }
if (-not (Test-Path (Join-Path $JmeterHome 'bin\jmeter.bat'))) { throw "找不到JMeter: $JmeterHome" }

# JMeter启动需要JAVA_HOME
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = $JavaHome }

# 各计划的默认并发参数
$presets = @{
    '01-baseline'         = @{ Threads = 30; Loops = 20 }
    '02-dish-list'        = @{ Threads = 50; Loops = 20 }
    '03-seckill'          = @{ Threads = 30; Loops = 1 }
    '04-cache-breakdown'  = @{ Threads = 60; Loops = 1 }
}
if ($Threads -le 0) { $Threads = $presets[$Plan].Threads }
if ($Loops -le 0) { $Loops = $presets[$Plan].Loops }

Write-Host "==================== JMeter: $Plan ====================" -ForegroundColor Cyan
Write-Host ("目标: http://localhost:{0}    线程: {1}    循环: {2}" -f $Port, $Threads, $Loops)

# ---------- 1. 重置数据 ----------
if (-not $SkipReset) {
    if ($Plan -eq '03-seckill') {
        & (Join-Path $root 'reset-testdata.ps1') -SeckillId $SeckillId -Stock $Stock
    } elseif ($Plan -eq '02-dish-list') {
        & (Join-Path $root 'reset-testdata.ps1') -SeckillId $SeckillId -Stock $Stock -ClearDishCache
    } elseif ($Plan -eq '04-cache-breakdown') {
        & (Join-Path $root 'reset-testdata.ps1') -SeckillId $SeckillId -Stock $Stock -ClearDishCache
        Write-Host "提示: 只清理了菜品缓存，秒杀库存保持不变" -ForegroundColor DarkGray
    }
} else {
    Write-Host "跳过数据重置" -ForegroundColor DarkYellow
}

# ---------- 2. 执行压测 ----------
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportDir = Join-Path $root "reports\$Plan-$stamp"
$jtl = Join-Path $reportDir 'result.jtl'
$htmlDir = Join-Path $reportDir 'html'
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null

Write-Host ""
Write-Host "[压测中] 非GUI模式执行 ..." -ForegroundColor Yellow
$sw = [System.Diagnostics.Stopwatch]::StartNew()

$jmeterArgs = @(
    '-n',
    '-t', "`"$jmx`"",
    '-l', "`"$jtl`"",
    '-e', '-o', "`"$htmlDir`"",
    "-Jport=$Port",
    "-Jthreads=$Threads",
    "-Jloops=$Loops"
)

$output = & (Join-Path $JmeterHome 'bin\jmeter.bat') @jmeterArgs 2>&1
$sw.Stop()

$summary = $output | Where-Object { $_ -match 'summary =' } | Select-Object -Last 1
Write-Host ""
Write-Host "耗时: $([math]::Round($sw.Elapsed.TotalSeconds,1)) 秒"
if ($summary) {
    Write-Host $summary -ForegroundColor Green
} else {
    Write-Host "未取到汇总行，完整输出如下：" -ForegroundColor Red
    $output | Select-Object -Last 20 | ForEach-Object { Write-Host $_ }
}

# ---------- 3. 校验 ----------
$exitCode = 0
if ($Plan -eq '03-seckill') {
    Write-Host ""
    $verifyScript = Join-Path $root 'verify.ps1'
    & $verifyScript -SeckillId $SeckillId -ExpectedStock 0 -ExpectedOrders $Stock
    $exitCode = $LASTEXITCODE
} elseif ($Plan -eq '04-cache-breakdown') {
    Write-Host ""
    Write-Host "击穿验证：请在服务端日志里搜 '回源写缓存.*dish_' ，正常只出现1次" -ForegroundColor Yellow
}

# ---------- 4. 报告位置 ----------
Write-Host ""
Write-Host "HTML报告: $htmlDir\index.html" -ForegroundColor Cyan
Write-Host "原始结果: $jtl"

exit $exitCode
