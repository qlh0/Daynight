<#
.SYNOPSIS
    重置压测数据：清理秒杀订单、恢复库存、清理Redis缓存

.DESCRIPTION
    幂等，可以反复执行。压测前必须跑一次，否则库存是0，所有请求都会返回"已售罄"。

.EXAMPLE
    .\reset-testdata.ps1
    .\reset-testdata.ps1 -SeckillId 2 -Stock 50
    .\reset-testdata.ps1 -ClearDishCache
#>
[CmdletBinding()]
param(
    [string]$DatabaseName = 'daynight',
    [int]$SeckillId = 1,
    [int]$Stock = 10,
    [switch]$ClearDishCache,
    [string]$Mysql = 'D:\Program Files\MySQL\bin\mysql.exe',
    [string]$RedisCli = 'D:\develop\Redis-8.10.0-Windows-x64-cygwin-with-Service\redis-cli.exe',
    [int]$RedisDb = 1,
    [string]$DbUser = 'root',
    [string]$DbPassword = 'qlhjoy986'
)

$ErrorActionPreference = 'Continue'

# 用环境变量传密码，避免 mysql 在 stderr 输出"Using a password on the command line"警告
$env:MYSQL_PWD = $DbPassword

function Invoke-Sql([string]$sql) {
    & $Mysql --user=$DbUser --host=localhost --port=3306 --database=$DatabaseName `
        --default-character-set=utf8mb4 --execute=$sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "SQL执行失败: $sql" }
}

function Query-Sql([string]$sql) {
    $v = & $Mysql --user=$DbUser --host=localhost --port=3306 --database=$DatabaseName `
        -N -B --execute=$sql
    return ($v -join '').Trim()
}

function Invoke-Redis([string[]]$redisArgs) {
    return (& $RedisCli -n $RedisDb @redisArgs)
}

Write-Host "[1/4] 清理历史秒杀订单 ..." -ForegroundColor Cyan
Invoke-Sql "delete from order_detail where order_id in (select id from orders where remark='秒杀订单')"
Invoke-Sql "delete from orders where remark='秒杀订单'"

Write-Host "[2/4] 恢复数据库库存 (seckillId=$SeckillId, stock=$Stock) ..." -ForegroundColor Cyan
Invoke-Sql "update seckill_dish set stock = $Stock where id = $SeckillId"

Write-Host "[3/4] 清理Redis秒杀key ..." -ForegroundColor Cyan
$keys = Invoke-Redis @('--scan', '--pattern', 'seckill:*')
foreach ($k in $keys) {
    if ($k) { Invoke-Redis @('del', $k) | Out-Null }
}
# 应用只在启动时预热库存，这里手动补上，否则接口会返回"活动不存在"
Invoke-Redis @('set', "seckill:stock:$SeckillId", "$Stock") | Out-Null

if ($ClearDishCache) {
    Write-Host "[4/4] 清理菜品缓存（制造冷缓存）..." -ForegroundColor Cyan
    $dishKeys = Invoke-Redis @('--scan', '--pattern', 'dish_*')
    foreach ($k in $dishKeys) {
        if ($k) { Invoke-Redis @('del', $k) | Out-Null }
    }
} else {
    Write-Host "[4/4] 保留菜品缓存（热缓存）" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "重置完成：" -ForegroundColor Green
Write-Host ("  数据库库存 = {0}" -f (Query-Sql "select stock from seckill_dish where id=$SeckillId"))
Write-Host ("  Redis库存  = {0}" -f ((Invoke-Redis @('get', "seckill:stock:$SeckillId")) -join ''))
Write-Host ("  秒杀订单数 = {0}" -f (Query-Sql "select count(*) from orders where remark='秒杀订单'"))
