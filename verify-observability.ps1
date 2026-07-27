$BASE     = "http://localhost:8082"
$ACTUATOR = "http://localhost:9091"
$PROM     = "http://localhost:9090"
$GRAF     = "http://localhost:3000"
$pass = 0; $fail = 0

# FIX: Invoke-WebRequest returns byte[] for application/json responses in PowerShell 7.
# Decode manually to UTF-8 string before pattern matching.
function Check($label, $url, $expectBody = $null, $expectStatus = 200) {
    try {
        $r = Invoke-WebRequest -Uri $url -TimeoutSec 5 -UseBasicParsing
        if ($r.StatusCode -ne $expectStatus) {
            Write-Host "  FAIL  $label -- Expected $expectStatus got $($r.StatusCode)" -ForegroundColor Red
            $script:fail++; return
        }
        $body = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
        if ($expectBody -and $body -notmatch $expectBody) {
            Write-Host "  FAIL  $label -- Body missing: '$expectBody'" -ForegroundColor Red
            $script:fail++; return
        }
        Write-Host "  PASS  $label" -ForegroundColor Green
        $script:pass++
    } catch {
        Write-Host "  FAIL  $label -- $($_.Exception.Message)" -ForegroundColor Red
        $script:fail++
    }
}

Write-Host "`n MODULE 2 -- LOGGING" -ForegroundColor Cyan
$logFile = "logs\spring-boot-starter.log"
if (Test-Path $logFile) {
    $line = Get-Content $logFile -Tail 1
    if ($line -match '"@timestamp"') {
        Write-Host "  PASS  JSON structured logging active" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "  INFO  Log file exists but not JSON (file appender only in prod profile -- OK for dev)" -ForegroundColor Yellow
    }
} else {
    Write-Host "  INFO  Log file not found (file appender only in prod profile -- OK for dev)" -ForegroundColor Yellow
}

Write-Host "`n MODULE 3 -- ACTUATOR" -ForegroundColor Cyan
Check "Health endpoint UP"           "$ACTUATOR/actuator/health"          '"status":"UP"'
# LivenessState.CORRECT -> serializes as "UP" in health JSON (not "CORRECT")
# ReadinessState.ACCEPTING_TRAFFIC -> serializes as "UP" (not "ACCEPTING_TRAFFIC")
# The enum values are internal Spring Boot state; HTTP status codes carry the real signal.
Check "Liveness probe"               "$ACTUATOR/actuator/health/liveness"  '"status":"UP"'
Check "Readiness probe"              "$ACTUATOR/actuator/health/readiness" '"status":"UP"'
# Key = "database" (Spring Boot strips "HealthIndicator" suffix from bean name)
# Our custom indicator adds "responseTimeMs" detail -- use that as unique fingerprint
Check "Custom DB health indicator"   "$ACTUATOR/actuator/health"           '"responseTimeMs"'
Check "Loggers endpoint accessible"  "$ACTUATOR/actuator/loggers"          '"loggers"'
Check "Info endpoint"                "$ACTUATOR/actuator/info"             '"app"'

Write-Host "`n MODULE 4 -- MICROMETER" -ForegroundColor Cyan
Check "Prometheus metrics endpoint"    "$ACTUATOR/actuator/prometheus" 'jvm_memory_used_bytes'
Check "HikariCP pool metrics present"  "$ACTUATOR/actuator/prometheus" 'hikaricp_connections'
Check "Application tag present"        "$ACTUATOR/actuator/prometheus" 'application="spring-boot-starter"'

Write-Host "  Generating traffic for custom metrics..." -ForegroundColor Gray
try { Invoke-WebRequest "$BASE/api/products" -UseBasicParsing | Out-Null } catch {}
try { Invoke-WebRequest "$BASE/api/products/9999" -UseBasicParsing | Out-Null } catch {}

# http_server_requests_seconds appears AFTER first HTTP request -- check after traffic generation
Check "HTTP server metrics present"       "$ACTUATOR/actuator/prometheus" 'http_server_requests_seconds'
# Counter.builder("products.added") -> Prometheus: products_added_total
# NOTE: "products.created" was avoided because OpenMetrics 1.0 treats "_created" as a
# reserved suffix (counter creation timestamp) -- Micrometer strips it -> "products_total" (wrong).
Check "Custom: products_added_total"      "$ACTUATOR/actuator/prometheus" 'products_added_total'
Check "Custom: product_operation_duration" "$ACTUATOR/actuator/prometheus" 'product_operation_duration'

Write-Host "`n MODULE 5 -- PROMETHEUS" -ForegroundColor Cyan
Check "Prometheus UI accessible" "$PROM/-/healthy"
try {
    $targets = Invoke-WebRequest "$PROM/api/v1/targets" -UseBasicParsing | ConvertFrom-Json
    $active = $targets.data.activeTargets | Where-Object { $_.labels.job -eq "spring-boot-starter" }
    if ($active -and $active.health -eq "up") {
        Write-Host "  PASS  Prometheus scraping Spring Boot -- target UP" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "  FAIL  spring-boot-starter target not UP (check app is running)" -ForegroundColor Red
        $fail++
    }
} catch {
    Write-Host "  FAIL  Could not query Prometheus targets API" -ForegroundColor Red
    $fail++
}

Write-Host "`n MODULE 6 -- GRAFANA" -ForegroundColor Cyan
Check "Grafana UI accessible" "$GRAF/api/health" 'database.*ok'
try {
    $cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:admin"))
    $headers = @{ Authorization = "Basic $cred" }
    $ds = Invoke-WebRequest "$GRAF/api/datasources" -Headers $headers -UseBasicParsing | ConvertFrom-Json
    if ($ds | Where-Object { $_.type -eq "prometheus" }) {
        Write-Host "  PASS  Prometheus datasource auto-provisioned" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "  FAIL  Prometheus datasource NOT found in Grafana" -ForegroundColor Red
        $fail++
    }
    $boards = Invoke-WebRequest "$GRAF/api/search?query=RED" -Headers $headers -UseBasicParsing | ConvertFrom-Json
    if ($boards | Where-Object { $_.title -match "RED" }) {
        Write-Host "  PASS  RED Dashboard auto-provisioned" -ForegroundColor Green
        $pass++
    } else {
        Write-Host "  FAIL  RED Dashboard NOT found (check docker/grafana/dashboards/)" -ForegroundColor Red
        $fail++
    }
} catch {
    Write-Host "  FAIL  Grafana API check failed -- $($_.Exception.Message)" -ForegroundColor Red
    $fail++
}

Write-Host "`n================================================" -ForegroundColor White
Write-Host "  PASSED: $pass   FAILED: $fail" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Yellow" })
if ($fail -eq 0) {
    Write-Host "  All modules working correctly!" -ForegroundColor Green
} else {
    Write-Host "  Fix the FAIL items above." -ForegroundColor Yellow
}
Write-Host "================================================`n" -ForegroundColor White
