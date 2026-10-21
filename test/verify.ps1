<#
.SYNOPSIS
    压测后的正确性校验：不超卖、订单数正确、无重复单号

.DESCRIPTION
    JMeter只能告诉你"多少个请求失败"，不能告诉你"有没有超卖"。
    真正的判定标准都在数据库里，这个脚本就是做这件事。
    校验通过退出码0，失败退出码1（方便串到流水线）。

.EXAMPLE
    .\verify.ps1
    .\verify.ps1 -SeckillId 1 -ExpectedStock 0 -ExpectedOrders 10
#>
[CmdletBinding()]
param(
    [string]$DatabaseName = 'daynight',
    [int]$SeckillId = 1,
    [int]$ExpectedStock = 0,
    [int]$ExpectedOrders = 10,
    [string]$Mysql = 'D:\Program Files\MySQL\bin\mysql.exe',
    [string]$RedisCli = 'D:\develop\Redis-8.10.0-Windows-x64-cygwin-with-Service\redis-cli.exe',
    [int]$RedisDb = 1,
    [string]$DbUser = 'root',
    [string]$DbPassword = 'qlhjoy986'
)

$ErrorActionPreference = 'Continue'
$env:MYSQL_PWD = $DbPassword
$failures = @()

function Query-Scalar([string]$sql) {
    $v = & $Mysql --user=$DbUser --host=localhost --port=3306 --database=$DatabaseName -N -B --execute=$sql
    return ($v -join '').Trim()
}

function Redis-Get([string[]]$redisArgs) {
    return ((& $RedisCli -n $RedisDb @redisArgs) -join '').Trim()
}

Write-Host "=========== 压测结果校验 ===========" -ForegroundColor Cyan

# 1. 数据库库存
$dbStock = Query-Scalar "select stock from seckill_dish where id=$SeckillId"
Write-Host ("1. 数据库库存 : {0}（期望 {1}）" -f $dbStock, $ExpectedStock)
if ([int]$dbStock -ne $ExpectedStock) {
    $failures += "库存不符：实际 $dbStock，期望 $ExpectedStock"
}
if ([int]$dbStock -lt 0) {
    $failures += "库存出现负数（$dbStock），发生了超卖！"
}

# 2. 订单条数
$orderCount = Query-Scalar "select count(*) from orders where remark='秒杀订单'"
Write-Host ("2. 秒杀订单数 : {0}（期望 {1}）" -f $orderCount, $ExpectedOrders)
if ([int]$orderCount -ne $ExpectedOrders) {
    $failures += "订单数不符：实际 $orderCount，期望 $ExpectedOrders"
}
if ([int]$orderCount -gt $ExpectedOrders) {
    $failures += "订单数超过初始库存（$orderCount > $ExpectedOrders），发生了超卖！"
}

# 3. 重复订单号
$dupCount = Query-Scalar "select count(*) from (select number from orders where remark='秒杀订单' group by number having count(*)>1) t"
Write-Host ("3. 重复订单号 : {0}（期望 0）" -f $dupCount)
if ([int]$dupCount -gt 0) {
    $failures += "存在 $dupCount 个重复的订单号"
}

# 4. Redis库存
$redisStock = Redis-Get @('get', "seckill:stock:$SeckillId")
Write-Host ("4. Redis库存  : {0}（期望 {1}）" -f $redisStock, $ExpectedStock)
if ($redisStock -ne "$ExpectedStock") {
    $failures += "Redis库存不符：实际 $redisStock，期望 $ExpectedStock"
}

# 5. 已购用户数
$buyers = Redis-Get @('scard', "seckill:buyers:$SeckillId")
Write-Host ("5. 已购用户数 : {0}（期望 {1}）" -f $buyers, $ExpectedOrders)
if ([int]$buyers -ne $ExpectedOrders) {
    $failures += "已购用户数不符：实际 $buyers，期望 $ExpectedOrders"
}

Write-Host "===================================" -ForegroundColor Cyan

if ($failures.Count -eq 0) {
    Write-Host "结果：PASS" -ForegroundColor Green
    exit 0
} else {
    Write-Host "结果：FAIL" -ForegroundColor Red
    foreach ($f in $failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
    exit 1
}
