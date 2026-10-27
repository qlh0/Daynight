<#
.SYNOPSIS
    同步下单 vs 阻塞队列异步下单 对照测试

.DESCRIPTION
    在相同库存、相同请求量、相同并发下，分别用两种模式压测同一接口，对比：
      1. 接口响应时间（平均 / P50 / P95 / P99 / 最大）
      2. 吞吐量（TPS / 总耗时）
      3. 最终落库成功率（订单数 / 抢到资格数）与落库延迟
    两种模式跑在同一个 JVM 里（通过 /admin/seckill/mode 切换），避免重启和冷启动差异。

.EXAMPLE
    .\ab-sync-vs-async.ps1 -Threads 100 -Stock 100
    .\ab-sync-vs-async.ps1 -Threads 200 -Stock 200
#>
[CmdletBinding()]
param(
    [int]$Port = 8081,
    [int]$Threads = 100,
    [int]$Stock = 100,
    [int]$SeckillId = 1,
    [int]$DishId = 51,
    [string]$JmeterHome = 'D:\develop\apache-jmeter-5.6.3',
    [string]$JavaHome = 'D:\develop',
    [string]$Mysql = 'D:\Program Files\MySQL\bin\mysql.exe',
    [string]$DatabaseName = 'daynight',
    [string]$DbUser = 'root',
    [string]$DbPassword = 'qlhjoy986'
)

$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = $JavaHome
$env:MYSQL_PWD = $DbPassword
$root = $PSScriptRoot
$jmx = Join-Path $root 'jmeter\03-seckill.jmx'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $root "reports\ab-$stamp"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

function Query-Sql([string]$sql) {
    $v = & $Mysql --user=$DbUser --host=localhost --port=3306 --database=$DatabaseName -N -B --execute=$sql
    return ($v -join '').Trim()
}

function Login-Admin {
    $body = '{"username":"admin","password":"123456"}'
    $r = Invoke-WebRequest "http://localhost:$Port/admin/employee/login" -Method Post `
        -ContentType 'application/json' -Body $body -UseBasicParsing
    return ([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json).data.token
}

function Set-Mode([string]$token, [bool]$async) {
    $url = "http://localhost:$Port/admin/seckill/mode?async=" + $async.ToString().ToLower()
    $null = Invoke-WebRequest $url -Method Post -Headers @{ token = $token } -UseBasicParsing
}

function Reset-Data {
    & (Join-Path $root 'reset-testdata.ps1') -SeckillId $SeckillId -Stock $Stock | Out-Null
}

function Run-Jmeter([string]$tag, [int]$threads, [bool]$withReport) {
    $jtl = Join-Path $outDir "$tag.jtl"
    Remove-Item $jtl -Force -ErrorAction SilentlyContinue
    $jmeterArgs = @('-n', '-t', "`"$jmx`"", '-l', "`"$jtl`"", "-Jport=$Port", "-Jthreads=$threads", '-Jloops=1')
    if ($withReport) {
        $rep = Join-Path $outDir "$tag-report"
        Remove-Item $rep -Recurse -Force -ErrorAction SilentlyContinue
        $jmeterArgs += @('-e', '-o', "`"$rep`"")
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $out = & (Join-Path $JmeterHome 'bin\jmeter.bat') @jmeterArgs 2>&1
    $sw.Stop()
    $line = ($out | Where-Object { $_ -match 'summary =' } | Select-Object -Last 1)
    return [pscustomobject]@{ Jtl = $jtl; Summary = $line; WallSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 2) }
}

function Get-JtlStats([string]$jtl) {
    $rows = Import-Csv $jtl
    $el = @($rows | ForEach-Object { [double]$_.elapsed } | Sort-Object)
    # 服务端处理耗时 = 总耗时 - TCP建连耗时（剔除冷连接干扰）
    $srv = @($rows | ForEach-Object { [double]$_.elapsed - [double]$_.Connect } | Sort-Object)
    $n = $el.Count
    $p = { param($arr, $q) $idx = [int][math]::Round(($q / 100.0) * $n - 0.5); if ($idx -lt 0) { $idx = 0 }; if ($idx -ge $n) { $idx = $n - 1 }; return $arr[$idx] }
    $successResp = (Select-String -Path $jtl -Pattern 'SECKILL_SUCCESS' -AllMatches).Count
    $rejectResp = (Select-String -Path $jtl -Pattern 'SECKILL_REJECT' -AllMatches).Count
    return [pscustomobject]@{
        Count      = $n
        Min        = [math]::Round($el[0], 0)
        Avg        = [math]::Round(($el | Measure-Object -Average).Average, 1)
        P50        = [math]::Round((& $p $el 50), 0)
        P95        = [math]::Round((& $p $el 95), 0)
        P99        = [math]::Round((& $p $el 99), 0)
        Max        = [math]::Round($el[$n - 1], 0)
        SrvAvg     = [math]::Round(($srv | Measure-Object -Average).Average, 1)
        SrvP95     = [math]::Round((& $p $srv 95), 0)
        SrvP99     = [math]::Round((& $p $srv 99), 0)
        RespOk     = $successResp
        RespReject = $rejectResp
    }
}

function Wait-Settle([int]$expected, [int]$timeoutSeconds = 20) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $last = -1
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSeconds) {
        $c = [int](Query-Sql "select count(*) from orders o join order_detail d on o.id = d.order_id where o.remark='秒杀订单' and d.dish_id = $DishId")
        if ($c -ge $expected) { break }
        if ($c -eq $last -and $sw.Elapsed.TotalSeconds -gt 1.5) { break }
        $last = $c
        Start-Sleep -Milliseconds 50
    }
    return [math]::Round($sw.Elapsed.TotalSeconds, 2)
}

function Measure-Mode([string]$modeName, [bool]$async, [string]$token) {
    Write-Host ""
    Write-Host "========== 模式: $modeName (async=$async) ==========" -ForegroundColor Cyan

    # 1) 切模式 + 重置数据
    Set-Mode $token $async
    Reset-Data

    # 2) 预热（JIT + 连接池），预热后再重置一次数据
    Write-Host "[预热] 10 线程 ..." -ForegroundColor DarkGray
    $null = Run-Jmeter "warmup-$modeName" 10 $false
    Reset-Data

    # 3) 正式压测
    Write-Host "[压测] $Threads 线程 / 库存 $Stock ..." -ForegroundColor Yellow
    $run = Run-Jmeter $modeName $Threads $true

    # 4) 等待落库稳定
    $settleSeconds = Wait-Settle $Stock

    # 5) 收集结果
    $stats = Get-JtlStats $run.Jtl
    $dbStock = [int](Query-Sql "select stock from seckill_dish where id=$SeckillId")
    $orderCount = [int](Query-Sql "select count(*) from orders o join order_detail d on o.id = d.order_id where o.remark='秒杀订单' and d.dish_id = $DishId")
    $dup = [int](Query-Sql "select count(*) from (select o.number from orders o join order_detail d on o.id=d.order_id where o.remark='秒杀订单' and d.dish_id=$DishId group by o.number having count(*)>1) t")

    $tps = 0.0
    if ($run.Summary -match '=\s+([\d.]+)/s') { $tps = [double]$Matches[1] }

    $rate = 0.0
    if ($stats.RespOk -gt 0) { $rate = [math]::Round(100.0 * $orderCount / $stats.RespOk, 1) }

    $result = [pscustomobject]@{
        模式          = $modeName
        并发          = $Threads
        初始库存      = $Stock
        请求数        = $stats.Count
        抢到资格数    = $stats.RespOk
        业务拒绝数    = $stats.RespReject
        平均RT_ms     = $stats.Avg
        P50_ms        = $stats.P50
        P95_ms        = $stats.P95
        P99_ms        = $stats.P99
        服务端平均_ms = $stats.SrvAvg
        服务端P95_ms  = $stats.SrvP95
        服务端P99_ms  = $stats.SrvP99
        最大RT_ms     = $stats.Max
        TPS           = $tps
        压测耗时_s    = $run.WallSeconds
        落库订单数    = $orderCount
        落库成功率_   = $rate
        落库等待_s    = $settleSeconds
        最终库存      = $dbStock
        重复单号      = $dup
    }
    $result | Format-List
    return $result
}

# ---------------- 主流程 ----------------
Write-Host "目标: http://localhost:$Port   并发=$Threads   库存=$Stock" -ForegroundColor Green
$token = Login-Admin
"admin token 获取成功"

$syncResult  = Measure-Mode 'SYNC'  $false $token
$asyncResult = Measure-Mode 'ASYNC' $true  $token

# 恢复默认异步模式
Set-Mode $token $true

# ---------------- 汇总 ----------------
$all = @($syncResult, $asyncResult)
$md = @()
$md += "# 同步下单 vs 阻塞队列异步下单 对照结果"
$md += ""
$md += "- 时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$md += "- 地址：http://localhost:$Port ，秒杀活动 id=$SeckillId"
$md += "- 条件：并发 $Threads 线程 × 1 次请求，初始库存 $Stock，两种模式跑在同一 JVM"
$md += ""
$md += "| 模式 | 请求数 | 抢到资格 | 平均RT(ms) | P95 | 服务端平均(ms) | 服务端P95 | TPS | 压测耗时(s) | 落库订单 | 落库成功率 | 最终库存 |"
$md += "|---|---|---|---|---|---|---|---|---|---|---|---|"
foreach ($r in $all) {
    $md += "| $($r.模式) | $($r.请求数) | $($r.抢到资格数) | $($r.平均RT_ms) | $($r.P95_ms) | $($r.服务端平均_ms) | $($r.服务端P95_ms) | $($r.TPS) | $($r.压测耗时_s) | $($r.落库订单数) | $($r.落库成功率_)% | $($r.最终库存) |"
}
$mdText = $md -join "`r`n"
$mdPath = Join-Path $outDir 'summary.md'
[System.IO.File]::WriteAllText($mdPath, $mdText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host $mdText -ForegroundColor Green
Write-Host ""
Write-Host "结果已保存: $mdPath" -ForegroundColor Cyan
Write-Host "原始数据: $outDir" -ForegroundColor Cyan
