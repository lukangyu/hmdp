param(
    [int]$VoucherId = 1,
    [int]$Stock = 1000,
    [int]$UniqueUsers = 1000,
    [int]$Concurrency = 5200,
    [int]$TotalRequests = 5200
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "target\hmdp-1.0-SNAPSHOT.jar"
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$appOut = Join-Path $PSScriptRoot "app.$runId.out.log"
$appErr = Join-Path $PSScriptRoot "app.$runId.err.log"
$summaryPath = Join-Path $PSScriptRoot "k6-summary.json"
$reportPath = Join-Path $PSScriptRoot "loadtest-report.json"
$k6ScriptPath = "/scripts/seckill-k6.js"

$mysqlExe = "E:\codeApp\MySQL\MySQL Server 8.0\bin\mysql.exe"
$mvnExe = "C:\Users\32114\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd"

function Invoke-MySqlScalar {
    param([string]$Sql)
    $result = & $mysqlExe -h 127.0.0.1 -P 3306 -u root -p123456 -N -s -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "mysql command failed"
    }
    return ($result | Select-Object -First 1).Trim()
}

function Invoke-MySql {
    param([string]$Sql)
    & $mysqlExe -h 127.0.0.1 -P 3306 -u root -p123456 -e $Sql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "mysql command failed"
    }
}

function Wait-AppReady {
    param([int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        try {
            Invoke-RestMethod -Uri "http://127.0.0.1:8081/shop-type/list" -Method Get -TimeoutSec 3 | Out-Null
            return $true
        } catch {
            # ignore and retry
        }
    }
    return $false
}

function Ensure-DockerUp {
    Push-Location $projectRoot
    try {
        docker compose up -d | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed"
        }
    } finally {
        Pop-Location
    }
}

function Reset-RedisState {
    param([int]$Voucher)
    $lua = "local ks=redis.call('keys','seckill:order:*'); for _,k in ipairs(ks) do redis.call('del',k) end; redis.call('del','seckill:stock:$Voucher'); redis.call('del','stream.orders'); return #ks"
    docker exec hmdp-redis redis-cli EVAL $lua 0 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "redis cleanup failed"
    }
}

function Ensure-Build {
    if (Test-Path $jarPath) {
        return
    }
    if (-not (Test-Path $mvnExe)) {
        throw "jar not found and mvn not found at $mvnExe"
    }
    Push-Location $projectRoot
    try {
        & $mvnExe -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "maven package failed"
        }
    } finally {
        Pop-Location
    }
}

function Stop-StaleAppProcess {
    $javaProcs = Get-CimInstance Win32_Process -Filter "name = 'java.exe'" | Where-Object {
        $_.CommandLine -like "*hmdp-1.0-SNAPSHOT.jar*"
    }
    foreach ($proc in $javaProcs) {
        Stop-Process -Id $proc.ProcessId -Force
    }
}

function Get-K6MetricValue {
    param(
        [object]$Metrics,
        [string]$MetricName,
        [string]$FieldName,
        [double]$DefaultValue = 0
    )
    if ([string]::IsNullOrWhiteSpace($MetricName)) {
        return $DefaultValue
    }
    $metricProp = $Metrics.PSObject.Properties[$MetricName]
    if ($null -eq $metricProp) {
        return $DefaultValue
    }
    $fieldProp = $metricProp.Value.PSObject.Properties[$FieldName]
    if ($null -eq $fieldProp) {
        return $DefaultValue
    }
    return [double]$fieldProp.Value
}

if ($TotalRequests -lt $Concurrency) {
    throw "TotalRequests must be >= Concurrency"
}

if ($TotalRequests -lt $UniqueUsers) {
    throw "TotalRequests must be >= UniqueUsers"
}

$existingVoucher = Invoke-MySqlScalar "USE hmdp; SELECT COUNT(*) FROM tb_voucher WHERE id = $VoucherId;"
if ([int]$existingVoucher -eq 0) {
    throw "voucher_id=$VoucherId not found in tb_voucher"
}

Ensure-DockerUp
Ensure-Build
Stop-StaleAppProcess

Invoke-MySql @"
USE hmdp;
INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES ($VoucherId, $Stock, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE
stock = VALUES(stock),
begin_time = VALUES(begin_time),
end_time = VALUES(end_time);
DELETE FROM tb_voucher_order WHERE voucher_id = $VoucherId;
"@

Reset-RedisState -Voucher $VoucherId

if (Test-Path $summaryPath) { Remove-Item $summaryPath -Force }
if (Test-Path $reportPath) { Remove-Item $reportPath -Force }

$appProc = $null

try {
    $appProc = Start-Process -FilePath "java" -ArgumentList @("-jar", $jarPath, "--server.port=8081") -WorkingDirectory $projectRoot -RedirectStandardOutput $appOut -RedirectStandardError $appErr -PassThru

    if (-not (Wait-AppReady -TimeoutSec 120)) {
        if ($appProc -and $appProc.HasExited) {
            throw "application exited early, check $appOut and $appErr"
        }
        throw "application did not become ready on 8081"
    }

    $scriptMount = (Resolve-Path $PSScriptRoot).Path
    docker run --rm -v "${scriptMount}:/scripts" grafana/k6 run $k6ScriptPath --summary-export /scripts/k6-summary.json -e BASE_URL=http://host.docker.internal:8081 -e VOUCHER_ID=$VoucherId -e USER_COUNT=$UniqueUsers -e VUS=$Concurrency -e ITERATIONS=$TotalRequests
    if ($LASTEXITCODE -ne 0) {
        throw "k6 load test failed"
    }

    # asynchronous order processing needs a short drain window
    Start-Sleep -Seconds 6

    $k6 = Get-Content $summaryPath -Raw | ConvertFrom-Json
    $durationMetricName = ($k6.metrics.PSObject.Properties.Name | Where-Object { $_ -like "http_req_duration*" } | Select-Object -First 1)
    $okReq = [int](Get-K6MetricValue -Metrics $k6.metrics -MetricName "seckill_ok" -FieldName "count")
    $dupRejected = [int](Get-K6MetricValue -Metrics $k6.metrics -MetricName "seckill_dup_rejected" -FieldName "count")
    $stockRejected = [int](Get-K6MetricValue -Metrics $k6.metrics -MetricName "seckill_stock_rejected" -FieldName "count")
    $otherFail = [int](Get-K6MetricValue -Metrics $k6.metrics -MetricName "seckill_other_fail" -FieldName "count")
    $reqRate = Get-K6MetricValue -Metrics $k6.metrics -MetricName "http_reqs" -FieldName "rate"
    $p95 = Get-K6MetricValue -Metrics $k6.metrics -MetricName $durationMetricName -FieldName "p(95)"

    $orderCount = [int](Invoke-MySqlScalar "USE hmdp; SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = $VoucherId;")
    $dupOrderCount = [int](Invoke-MySqlScalar "USE hmdp; SELECT COUNT(*) - COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id = $VoucherId;")
    $dbStock = [int](Invoke-MySqlScalar "USE hmdp; SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $VoucherId;")

    $redisStockRaw = (docker exec hmdp-redis redis-cli GET "seckill:stock:$VoucherId" | Out-String).Trim()
    $redisStock = if ([string]::IsNullOrWhiteSpace($redisStockRaw) -or $redisStockRaw -eq "(nil)") { 0 } else { [int]$redisStockRaw }

    $duplicateAttempts = [Math]::Max(0, $TotalRequests - $UniqueUsers)
    $duplicateInterceptRate = if ($duplicateAttempts -eq 0) { 0 } else { [Math]::Round(($dupRejected * 100.0) / $duplicateAttempts, 2) }
    $expectedRemain = $Stock - $orderCount

    $report = [ordered]@{
        run_at = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        params = @{
            voucher_id = $VoucherId
            stock = $Stock
            unique_users = $UniqueUsers
            concurrency = $Concurrency
            total_requests = $TotalRequests
        }
        k6 = @{
            http_reqs_rate = [Math]::Round($reqRate, 2)
            p95_ms = [Math]::Round($p95, 2)
            success_requests = $okReq
            duplicate_rejected = $dupRejected
            stock_rejected = $stockRejected
            other_failures = $otherFail
        }
        verify = @{
            orders_in_db = $orderCount
            duplicate_orders_in_db = $dupOrderCount
            db_stock_remaining = $dbStock
            redis_stock_remaining = $redisStock
            expected_stock_remaining = $expectedRemain
            oversell = ($orderCount -gt $Stock)
            one_user_one_order_ok = ($dupOrderCount -eq 0)
            db_stock_consistent = ($dbStock -eq $expectedRemain)
            redis_stock_consistent = ($redisStock -eq $expectedRemain)
            duplicate_attempts = $duplicateAttempts
            duplicate_intercept_rate_percent = $duplicateInterceptRate
        }
    }

    $report | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding UTF8
    Write-Host "Load test done. Report: $reportPath"
    Write-Host "Summary: orders=$orderCount, dup_orders=$dupOrderCount, oversell=$($orderCount -gt $Stock), dup_intercept=${duplicateInterceptRate}%"
    Write-Host "Performance: req_rate=$([Math]::Round($reqRate,2))/s, p95=$([Math]::Round($p95,2))ms"
} finally {
    if ($appProc -and -not $appProc.HasExited) {
        Stop-Process -Id $appProc.Id -Force
    }
}
