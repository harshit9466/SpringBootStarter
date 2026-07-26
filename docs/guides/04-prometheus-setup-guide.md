# Prometheus Setup Guide — Module 5
## Production Observability Engineering Course

> **What this guide covers:** Prometheus aur Grafana ka local Docker setup, Spring Boot app ko scrape target banana, aur essential PromQL queries verify karna.
>
> **Prerequisites:** Module 4 complete — Micrometer aur Prometheus registry already configured hai in `pom.xml` aur `application-dev.properties`.

---

## Files Created in This Module

```
docker/
└── prometheus/
    └── prometheus.yml      ← Prometheus scrape configuration
docker-compose.yml          ← Prometheus + Grafana containers
```

---

## Step 1 — Verify Actuator Prometheus Endpoint

Spring Boot app start karo (dev profile):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Endpoint verify karo:

```bash
curl http://localhost:8082/actuator/prometheus | head -40
```

**Expected output (sample):**
```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{application="spring-boot-starter",area="heap",...} 5.6e+07

# HELP http_server_requests_seconds Duration of HTTP server request handling
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_bucket{application="spring-boot-starter",le="0.001",...} 0.0
...

# HELP products_created_total Total number of products created
# TYPE products_created_total counter
products_created_total{application="spring-boot-starter"} 0.0
```

---

## Step 2 — Start Prometheus + Grafana

```bash
# Start in detached mode (background)
docker-compose up -d

# Check containers are running
docker-compose ps

# Follow Prometheus logs
docker-compose logs -f prometheus
```

**Expected `docker-compose ps` output:**
```
NAME         IMAGE                      STATUS          PORTS
grafana      grafana/grafana:11.1.0     Up              0.0.0.0:3000->3000/tcp
prometheus   prom/prometheus:v2.53.0    Up              0.0.0.0:9090->9090/tcp
```

---

## Step 3 — Verify Scrape Target

Open: **http://localhost:9090/targets**

- `spring-boot-starter` job dikhna chahiye
- **State: UP** (green)
- **Last Scrape:** few seconds ago
- **Labels:** `application="spring-boot-starter"`, `job="spring-boot-starter"`, `service="backend"`

**Agar State: DOWN hai:**
```bash
# Check if Spring Boot is accessible from Docker
curl http://localhost:8082/actuator/prometheus

# Check Prometheus logs for scrape errors
docker-compose logs prometheus | grep -i "error\|warn\|spring"
```

---

## Step 4 — PromQL Queries (Prometheus UI)

Open: **http://localhost:9090/graph**

### 4.1 Basic Health Check

```promql
# App UP hai ki nahi (1 = UP, 0 = DOWN)
up{job="spring-boot-starter"}
```

### 4.2 JVM Memory

```promql
# Heap usage in MB
jvm_memory_used_bytes{area="heap", application="spring-boot-starter"} / 1024 / 1024

# Heap usage as % of max
100 * jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
```

### 4.3 HTTP Request Rate

```promql
# Per-second request rate (last 5 min)
rate(http_server_requests_seconds_count{application="spring-boot-starter"}[5m])

# Per-status rate
sum by (status) (
  rate(http_server_requests_seconds_count{application="spring-boot-starter"}[5m])
)
```

### 4.4 Latency Percentiles

```promql
# P50 / P95 / P99 per endpoint
histogram_quantile(0.95,
  sum by (le, uri) (
    rate(http_server_requests_seconds_bucket{application="spring-boot-starter"}[5m])
  )
)
```

### 4.5 Error Rate

```promql
# 5xx error percentage
100 * (
  sum(rate(http_server_requests_seconds_count{
    application="spring-boot-starter", status=~"5.."}[5m]))
  /
  sum(rate(http_server_requests_seconds_count{
    application="spring-boot-starter"}[5m]))
)
```

### 4.6 Custom Business Metrics (Module 4 mein banaye the)

```promql
# Product creation counter
products_created_total{application="spring-boot-starter"}

# Product creation rate per second
rate(products_created_total{application="spring-boot-starter"}[5m])

# 404 errors (product not found)
products_not_found_total{application="spring-boot-starter"}
```

---

## Step 5 — Grafana Connect Karo (Preview)

Open: **http://localhost:3000**
- Username: `admin`
- Password: `admin`

**Add Prometheus Data Source:**
1. Left sidebar → Connections → Data Sources → Add new
2. Type: Prometheus
3. URL: `http://prometheus:9090`
   - WHY `prometheus:9090` not `localhost:9090`: Grafana bhi container ke andar hai. Container networking mein service name hi hostname hota hai.
4. **Save & Test** → "Successfully queried the Prometheus API"

---

## Step 6 — Hot Reload Config (Without Restart)

`prometheus.yml` mein changes karne ke baad restart ki zaroorat nahi:

```bash
# HTTP POST to reload endpoint (enabled by --web.enable-lifecycle flag)
curl -X POST http://localhost:9090/-/reload
```

---

## Useful Docker Commands

```bash
# Stop everything (data preserved in named volumes)
docker-compose down

# Stop AND delete all data (fresh start)
docker-compose down -v

# Check Prometheus data directory size
docker exec prometheus du -sh /prometheus

# Access Prometheus container shell
docker exec -it prometheus sh

# Check what Prometheus is scraping right now
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health, lastScrape: .lastScrape}'
```

---

## Troubleshooting

### Issue: `host.docker.internal` not resolving (Linux Docker)

`extra_hosts` already added hai `docker-compose.yml` mein:
```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```
Agar phir bhi issue aaye, Spring Boot ka actual host IP use karo:

```bash
# Host IP find karo
ip route show default | awk '/default/ {print $3}'
# Example output: 172.17.0.1

# prometheus.yml mein update karo
targets: ['172.17.0.1:8082']
```

### Issue: Prometheus shows "context deadline exceeded"

`scrape_timeout` (10s) `scrape_interval` (15s) se kam hona chahiye — already configured hai. Agar Spring Boot slow hai:
```bash
# Check actuator response time
time curl http://localhost:8082/actuator/prometheus > /dev/null
```

### Issue: Metrics nahi dikh rahe

```bash
# Verify Micrometer dependency in pom.xml
grep -A2 "micrometer-registry-prometheus" pom.xml

# Verify actuator endpoint exposed
curl http://localhost:8082/actuator | jq '.["_links"]["prometheus"]'
```

---

## Next: Module 6 — Grafana Dashboards

Module 6 mein hum:
- RED Method dashboard banayenge (Rate, Errors, Duration)
- USE Method dashboard (Utilization, Saturation, Errors — JVM resources)
- Alert rules configure karenge (error rate > 1%, p99 > 2s)
- Grafana provisioning via YAML (reproducible, version-controllable dashboards)
