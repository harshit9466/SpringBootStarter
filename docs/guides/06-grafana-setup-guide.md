# Grafana Setup Guide — Module 6
### Production Observability Engineering Course

> **What this document is**: A step-by-step implementation guide for setting up Grafana with
> provisioned datasources and dashboards using the RED method (Rate, Errors, Duration). Every
> configuration file, JSON field, and PromQL query is explained — what it does, why it exists,
> and what breaks if removed or misconfigured.
>
> **Who should follow this**: Backend engineers adding dashboards to a new service, AI assistants
> replicating this observability stack, or DevOps engineers verifying dashboard provisioning setup.

---

## Table of Contents

1. [Conceptual Overview — Read Before Coding](#1-conceptual-overview--read-before-coding)
2. [Step 1 — Prerequisites Verification](#2-step-1--prerequisites-verification)
3. [Step 2 — Directory Structure](#3-step-2--directory-structure)
4. [Step 3 — Datasource Provisioning File](#4-step-3--datasource-provisioning-file)
5. [Step 4 — Dashboard Folder Config](#5-step-4--dashboard-folder-config)
6. [Step 5 — Docker Compose Volume Mounts](#6-step-5--docker-compose-volume-mounts)
7. [Step 6 — Dashboard JSON — Anatomy of a Panel](#7-step-6--dashboard-json--anatomy-of-a-panel)
8. [Step 7 — Dashboard Panels Explained](#8-step-7--dashboard-panels-explained)
9. [Step 8 — Grafana 11 Specific Gotchas](#9-step-8--grafana-11-specific-gotchas)
10. [Step 9 — Verify in Grafana UI](#10-step-9--verify-in-grafana-ui)
11. [Step 10 — Generate Traffic and Validate](#11-step-10--generate-traffic-and-validate)
12. [Verification Checklist](#12-verification-checklist)
13. [Common Mistakes and What Goes Wrong](#13-common-mistakes-and-what-goes-wrong)
14. [Architecture Adaptation Notes](#14-architecture-adaptation-notes)

---

## 1. Conceptual Overview — Read Before Coding

### Why Grafana Exists — Prometheus Ka Limitation

Prometheus ka built-in UI (`http://localhost:9090`) sirf ek basic graph explorer hai. Production
mein yeh kaafi nahi:

- Ek time mein multiple queries ek screen pe nahi dekh sakte
- Dashboards session ke baad lost ho jaate hain — save nahi hote
- Team ke saath share nahi kar sakte
- Multiple data sources (Prometheus + Loki + Jaeger) ek jagah nahi

**Grafana** yeh gap fill karta hai — visualization aur alerting platform jo multiple data sources
ko ek unified UI mein laata hai.

> **Analogy**: Prometheus ek raw database hai. Grafana ek BI tool hai (Power BI, Tableau) — data
> wahi rehta hai Prometheus mein, sirf visualization layer alag hoti hai.

**Critical distinction**: Grafana data STORE nahi karta. Woh sirf Prometheus ko PromQL queries
bhejta hai aur result render karta hai. Delete Grafana → data safe in Prometheus TSDB.

### How Data Flows

```
Spring Boot App (/actuator/prometheus)
         │
         │ scrape every 15s (Prometheus pulls)
         ▼
   Prometheus TSDB
   (stores time series data)
         │
         │ PromQL query (Grafana pulls)
         ▼
   Grafana Query Engine
         │
         │ render
         ▼
   Browser Dashboard
```

### Provisioning — The Production Way

Grafana dashboards can be created in two ways:

**❌ Click-based (dev toy)**
- Create in UI → stored in Grafana's internal SQLite DB
- Container restart without volume = everything lost
- Team members can't replicate it
- No version history, no rollback

**✅ Provisioning (production standard)**
- Dashboard = JSON file in Git
- Datasource = YAML file in Git
- Container restart → everything auto-loads from files
- Team shares same files → consistent dashboards everywhere
- Version controlled → rollback is `git revert`

This guide uses provisioning exclusively.

---

## 2. Step 1 — Prerequisites Verification

Before starting, verify these are already in place from Modules 3, 4, and 5:

```bash
# 1. Spring Boot app running with dev profile (port 8082, management 9091)
curl -s http://localhost:9091/actuator/health | python -m json.tool

# 2. Prometheus endpoint working and returning metrics
curl -s http://localhost:8082/actuator/prometheus | grep "products_added_total"

# 3. Prometheus container running and scraping the app
curl -s http://localhost:9090/api/v1/targets | python -m json.tool
# Look for "health":"up" for the spring-boot-starter job
```

**Expected**: All three commands return data. If any fails, complete the earlier modules first.

---

## 3. Step 2 — Directory Structure

```
docker/
└── grafana/
    ├── provisioning/
    │   ├── datasources/
    │   │   └── prometheus.yml        ← Tells Grafana HOW to connect to Prometheus
    │   └── dashboards/
    │       └── dashboards.yml        ← Tells Grafana WHERE to find dashboard JSON files
    └── dashboards/
        └── spring-boot-RED.json      ← The actual dashboard (version controlled in Git)
```

**Why two separate directories under `grafana/`?**

`provisioning/` contains configuration — it tells Grafana about its infrastructure (data
connections, dashboard locations). These are YAML files Grafana reads at startup.

`dashboards/` contains content — the actual dashboard JSON files. These are what Grafana renders.

Keeping them separate follows the same principle as `application.properties` (config) vs
`migration scripts` (content) — they change for different reasons.

---

## 4. Step 3 — Datasource Provisioning File

**File**: `docker/grafana/provisioning/datasources/prometheus.yml`

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus
    url: http://prometheus:9090
    access: proxy
    isDefault: true
    jsonData:
      timeInterval: "15s"
```

**Line-by-line explanation:**

`apiVersion: 1`
Grafana's provisioning schema version. Always 1 — no other version exists currently.

`name: Prometheus`
Display name shown in Grafana UI under Connections → Data Sources. Can be anything, but
"Prometheus" is conventional. Dashboard panel queries reference this datasource by `uid`, not name.

`type: prometheus`
The datasource plugin type. Grafana has built-in plugins for prometheus, loki, jaeger, mysql, etc.
This determines which query editor and HTTP client Grafana uses internally.

`uid: prometheus`
**Critical field.** This is the stable identifier used in dashboard JSON to reference this
datasource: `"datasource": {"type": "prometheus", "uid": "prometheus"}`. If you change the uid
here, every dashboard panel that references it will show "No data" until updated.
Use a simple lowercase string — avoid auto-generated UUIDs which change on re-provision.

`url: http://prometheus:9090`
**WHY `prometheus:9090` not `localhost:9090`?**
Both Grafana and Prometheus run in Docker containers. Inside Docker's network, containers
communicate using service names defined in `docker-compose.yml`, not `localhost`. When Grafana
queries `http://prometheus:9090`, Docker resolves `prometheus` to the Prometheus container's
internal IP. Using `localhost:9090` would point to the Grafana container itself → "Bad Gateway".

`access: proxy`
Grafana server makes the request to Prometheus on behalf of the browser. Alternative is `direct`
(browser makes the request directly) — that would fail because the browser cannot reach
`http://prometheus:9090` (internal Docker network). Always use `proxy`.

`isDefault: true`
When creating a new dashboard panel in UI, Grafana pre-selects this datasource. Only one
datasource should have `isDefault: true`.

`timeInterval: "15s"`
Matches Prometheus's `scrape_interval`. Grafana uses this as the minimum step for queries.
If set lower than the scrape interval, Grafana would request data at finer granularity than
Prometheus has — resulting in gaps between points on graphs.

---

## 5. Step 4 — Dashboard Folder Config

**File**: `docker/grafana/provisioning/dashboards/dashboards.yml`

```yaml
apiVersion: 1

providers:
  - name: Spring Boot Dashboards
    type: file
    disableDeletion: true
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

**Line-by-line explanation:**

`name: Spring Boot Dashboards`
Internal name for this provider. Appears in Grafana logs when dashboards load. Not user-facing.

`type: file`
Tells Grafana to read dashboards from the filesystem. Alternative is `database` (UI-created
dashboards stored in Grafana's internal DB). We always use `file` for version-controlled dashboards.

`disableDeletion: true`
Prevents Grafana UI from deleting provisioned dashboards. If a team member accidentally hits
"Delete" in the UI, Grafana ignores the request and reloads the dashboard from disk on next sync.
Without this, clicking delete in UI permanently removes the dashboard from Grafana's DB even
though the JSON file still exists — confusing behavior.

`updateIntervalSeconds: 30`
How often Grafana checks the dashboard folder for new or updated JSON files. If you edit
`spring-boot-RED.json` and save it, Grafana reloads the dashboard within 30 seconds without
container restart. Set to 0 to disable hot-reload (only loads on startup).

`path: /var/lib/grafana/dashboards`
Inside the container, this is where Grafana looks for dashboard JSON files. This path is mapped
from your host via Docker volume mount: `./docker/grafana/dashboards:/var/lib/grafana/dashboards`.

---

## 6. Step 5 — Docker Compose Volume Mounts

The Grafana service in `docker-compose.yml` must have three volume mounts:

```yaml
services:
  grafana:
    image: grafana/grafana:11.1.0
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
      - ./docker/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./docker/grafana/dashboards:/var/lib/grafana/dashboards:ro
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
```

**Why each volume:**

`grafana_data:/var/lib/grafana`
Named Docker volume. Persists Grafana's internal state: user accounts, UI-created dashboards,
alert history, saved preferences. Without this, every container restart requires re-logging in.
This is SEPARATE from provisioned dashboards — provisioned ones come from files, not this volume.

`./docker/grafana/provisioning:/etc/grafana/provisioning:ro`
Mounts your provisioning YAML files into the exact path Grafana reads on startup.
`/etc/grafana/provisioning` is hardcoded in Grafana — it always looks here.
`:ro` (read-only) prevents Grafana from writing back to your source files.

`./docker/grafana/dashboards:/var/lib/grafana/dashboards:ro`
Mounts your dashboard JSON files. The path must match what you set in `dashboards.yml`
(`options.path: /var/lib/grafana/dashboards`). `:ro` prevents accidental modification.

**CRITICAL — Stale volume problem:**
If `grafana_data` volume was created BEFORE the provisioning files existed, Grafana may have
initialized without datasources. New file mounts don't override what's cached in the volume.

Fix: `docker-compose down -v` (deletes volume, fresh start, re-provisions everything).
Note: This also deletes any UI-created dashboards stored in the volume — acceptable since
all our dashboards are file-provisioned anyway.

---

## 7. Step 6 — Dashboard JSON — Anatomy of a Panel

Every Grafana dashboard is a JSON object. Here is the minimum structure with explanations:

```json
{
  "title": "Spring Boot — RED Dashboard",
  "uid": "spring-boot-red",
  "schemaVersion": 39,
  "refresh": "30s",
  "time": { "from": "now-1h", "to": "now" },
  "templating": {
    "list": [
      {
        "name": "application",
        "type": "query",
        "datasource": { "type": "prometheus", "uid": "prometheus" },
        "query": {
          "qryType": 1,
          "query": "label_values(application)",
          "refId": "PrometheusVariableQueryEditor-VariableQuery"
        },
        "refresh": 2
      }
    ]
  },
  "panels": [
    {
      "id": 2,
      "type": "timeseries",
      "title": "Request Rate (req/s)",
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 1 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        {
          "expr": "sum(rate(http_server_requests_seconds_count{application=\"$application\"}[5m]))",
          "legendFormat": "req/s",
          "refId": "A"
        }
      ]
    }
  ]
}
```

**Key fields explained:**

`uid: "spring-boot-red"`
Stable identifier for this dashboard. Used in URLs and bookmarks. If changed, all browser
bookmarks break. Keep it stable across edits.

`schemaVersion: 39`
Grafana's internal dashboard schema version. Must match your Grafana version's expected schema.
Grafana 11 uses version 39. Mismatch causes "Failed to upgrade legacy queries" errors.

`refresh: "30s"`
Auto-refresh interval. Dashboard re-queries Prometheus every 30 seconds automatically.

`templating.list`
Dashboard variables (the dropdowns at the top). The `application` variable populates the dropdown
from all unique values of the `application` label in Prometheus, letting you switch between services.

**Variable query — Grafana 11 object format (CRITICAL):**
```json
"query": {
  "qryType": 1,
  "query": "label_values(application)",
  "refId": "PrometheusVariableQueryEditor-VariableQuery"
}
```
Grafana 11 requires this object format. The legacy string format `"query": "label_values(...)"` 
triggers "Failed to upgrade legacy queries" error. Always use the object form in Grafana 11+.

`panels[].datasource: { "type": "prometheus", "uid": "prometheus" }`
References the datasource by uid. This uid must exactly match what you set in
`provisioning/datasources/prometheus.yml`. Mismatch = all panels show "No data".

`panels[].targets[].expr`
The PromQL query string. `$application` is replaced by Grafana at render time with the selected
variable value (e.g., "spring-boot-starter").

`panels[].targets[].refId`
Unique identifier for this query within the panel (A, B, C...). Multiple targets in one panel
appear as separate series. Used in transformations and overrides.

---

## 8. Step 7 — Dashboard Panels Explained

### Row 1 — RED Method

#### Request Rate (req/s)
```promql
sum(rate(http_server_requests_seconds_count{application="$application"}[5m]))
```
- `http_server_requests_seconds_count` — auto-instrumented by Micrometer. Increments once per HTTP request.
- `rate(...[5m])` — per-second rate over last 5 minutes (smoothed). NOT total count.
- `sum(...)` — adds rates across all URIs, methods, status codes → single "total req/s" number.

**What to watch**: Sudden drop to 0 = traffic stopped (deployment issue, service crash).
Sudden spike = load surge, retry storm, or bot traffic.

**Why "No data" without traffic**: `rate()` needs at least 2 data points in the window to
compute a rate. With no traffic in last 5 minutes, the metric has 0 increment → empty vector → "No data".

#### Error Rate (%)
```promql
100 * sum(rate(http_server_requests_seconds_count{application="$application", status=~"5.."}[5m]))
     / sum(rate(http_server_requests_seconds_count{application="$application"}[5m]))
     or vector(0)
```
- `status=~"5.."` — regex match for all 5xx status codes (500, 502, 503, 504...).
- Division = errors as fraction of total.
- `* 100` = percentage.
- `or vector(0)` — **critical addition**: when no 5xx errors exist, the numerator is empty vector.
  Dividing empty vector gives empty vector → "No data". `or vector(0)` substitutes 0 when the
  expression returns nothing → shows "0%" instead of blank. Without this, a healthy service (zero
  5xx) looks the same as a service with no data.

**SLO threshold**: Alert when > 1% sustained for 5 minutes.

#### P50 / P95 / P99 Latency
```promql
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{application="$application"}[5m]))
) * 1000
```
- `http_server_requests_seconds_bucket` — histogram buckets auto-instrumented by Micrometer.
  Each bucket counts how many requests finished within that time (le = less than or equal).
- `rate(...[5m])` — rate of bucket increments.
- `sum by (le)` — merge buckets across all URIs/methods (required for correct math).
  If you omit `by (le)`, you merge time series in a way that breaks quantile calculation.
- `histogram_quantile(0.95, ...)` — Prometheus computes the 95th percentile from the bucket data.
- `* 1000` — converts seconds to milliseconds for readability.

**Why three quantiles (P50, P95, P99)?**
Average hides tail latency. P99 shows the worst 1% of users' experience.
Example: 99 requests in 10ms, 1 request in 5000ms → average = 60ms (misleading), P99 = 5000ms (truth).

### Row 2 — USE Method (Resources)

#### JVM Heap Usage
```promql
100 * sum(jvm_memory_used_bytes{application="$application", area="heap"})
     / sum(jvm_memory_max_bytes{application="$application", area="heap"})
```
Heap as percentage of max allocated. Alert at > 80% sustained — GC overhead increases sharply.
This is a gauge — always has data, no traffic needed.

#### DB Connection Pool — Active vs Max
```promql
hikaricp_connections_active{application="$application"}   -- active (in use)
hikaricp_connections_max{application="$application"}      -- max pool size
hikaricp_connections_pending{application="$application"}  -- waiting for a connection
```
HikariCP is Spring Boot's default connection pool. Alert when `pending > 0` — threads waiting
for connections means requests are queuing. Alert at `active/max > 70%` proactively (before
saturation, not after). These are gauges — always have data.

#### HTTP Request Breakdown by Status
```promql
sum by (status) (rate(http_server_requests_seconds_count{application="$application"}[5m]))
```
`by (status)` groups the rate by HTTP status code. Each status (200, 201, 404, 500...) becomes
a separate line on the chart. Pattern to watch: 5xx rising independently of 4xx = server error,
not client misuse.

### Row 3 — Business Metrics

#### Products Created (total)
```promql
increase(products_added_total{application="$application"}[1h])
```
`increase()` over 1 hour shows total products created in the last hour. Unlike `rate()`, this
returns an absolute count (not per-second), making it intuitive for business stakeholders.
Shows 0 if no products created in last hour — not "No data". Gauge-like behavior via `increase()`.

**WHY "added" not "created"?** OpenMetrics 1.0 reserves the `_created` suffix for counter
creation timestamps. `Counter.builder("products.created")` → Micrometer strips `_created` →
produces `products_total` (wrong). Use `products.added` → `products_added_total` (correct).

#### Products Not Found (total)
```promql
increase(products_not_found_total{application="$application"}[1h])
```
Business-level 404 counter — distinct from HTTP 404 (which includes bot traffic to unmapped URLs).
This counter increments only when a valid product lookup fails in the service layer.
Spike here = stale client data, bad deployment, or data consistency issue.

#### Product Operation Duration — P50 / P95 / P99
```promql
histogram_quantile(0.95,
  sum by (le, operation) (rate(product_operation_duration_seconds_bucket{application="$application"}[5m]))
) * 1000
```
Custom Timer from `ProductServiceImpl`. `by (le, operation)` groups by both bucket boundary AND
operation tag (`getById`, `save`) — gives per-operation percentile breakdown.
Shows "No data" without recent traffic (rate-based, 5m window).

---

## 9. Step 8 — Grafana 11 Specific Gotchas

These issues were discovered in production and fixed. Document them so you don't repeat the debugging.

### Gotcha 1 — `__inputs` Block Causes "Failed to upgrade legacy queries"

When exporting a dashboard from Grafana UI for sharing, it adds an `__inputs` block:
```json
{
  "__inputs": [{ "name": "DS_PROMETHEUS", "type": "datasource" }],
  ...
}
```
This block is for the import wizard (manual import via UI). For **provisioning**, this block
triggers "Failed to upgrade legacy queries" in Grafana 11 because the `${DS_PROMETHEUS}` variable
is never resolved during file-based load.

**Fix**: Remove the `__inputs` block entirely from provisioned dashboard JSON. Hardcode the
datasource uid directly in panels: `"datasource": { "type": "prometheus", "uid": "prometheus" }`.

### Gotcha 2 — `pluginId` Field in Datasource Variable Causes "No data sources found"

A common pattern is to create a `datasource` template variable so panels can dynamically switch
datasources. This pattern uses `"pluginId": "prometheus"` — a field that does NOT exist in
Grafana's variable schema:
```json
{
  "name": "datasource",
  "type": "datasource",
  "pluginId": "prometheus"   ← This field does NOT exist. Variable resolves to empty.
}
```
When the variable is empty, all panel datasource UIDs become null → all panels show "No data".

**Fix**: Remove the datasource variable entirely. Hardcode `uid: "prometheus"` directly in all
panel datasource references. The abstraction adds fragility without value when you have a
single stable datasource.

### Gotcha 3 — Legacy String Query Format in Variables

```json
// WRONG — triggers "Failed to upgrade legacy queries" in Grafana 11
"query": "label_values(http_server_requests_seconds_count, application)"

// CORRECT — Grafana 11 object format
"query": {
  "qryType": 1,
  "query": "label_values(application)",
  "refId": "PrometheusVariableQueryEditor-VariableQuery"
}
```
Also note: `label_values(application)` (no metric argument) is preferred over
`label_values(http_server_requests_seconds_count, application)` because the latter returns empty
if no HTTP traffic has arrived yet, making the Application dropdown blank on a fresh start.

### Gotcha 4 — Stale `grafana_data` Named Volume

If the `grafana_data` Docker volume was created before provisioning files existed, Grafana
initialized without datasources and cached that empty state. New volume mounts for provisioning
files DO NOT override the cached state.

```bash
# Only fix:
docker-compose down -v   # Removes named volume — fresh init picks up provisioning files
docker-compose up -d
```

### Gotcha 5 — Spring Security Applies to Management Port Too

If your `SecurityConfig` does not explicitly permit `/actuator/**`, Prometheus scraping on port
9091 returns 401. Grafana then shows data gaps or permanent "No data" for all panels.

```java
private static final String[] OPEN_PATHS = {
    "/actuator/**"   // Required even on management port (9091)
};
```

---

## 10. Step 9 — Verify in Grafana UI

```bash
# Start the stack
docker-compose down -v    # Fresh start (clears stale volume state)
docker-compose up -d

# Wait 10 seconds for Grafana to initialize
docker-compose logs grafana | grep "HTTP Server Listen"
```

Open: `http://localhost:3000` → Login: `admin` / `admin`

**Check 1 — Datasource auto-configured:**
- Left sidebar → Connections → Data Sources
- "Prometheus" should appear — URL: `http://prometheus:9090`
- Click it → scroll to bottom → "Save & Test" → "Successfully queried the Prometheus API"

**Check 2 — Dashboard auto-loaded:**
- Left sidebar → Dashboards
- "Spring Boot — RED Dashboard" should appear without any manual import
- If missing: check `docker-compose logs grafana` for provisioning errors

**Check 3 — Application dropdown populated:**
- Open the dashboard
- Top left: "Application" dropdown should show `spring-boot-starter`
- If empty: Spring Boot app not running, or no metrics in Prometheus yet

**Check 4 — Static panels have data immediately:**
Even without any HTTP traffic, these panels should show data:
- JVM Heap Usage (gauge — instant snapshot)
- DB Connection Pool (gauge — instant snapshot)
- Error Rate (shows 0% via `or vector(0)`)

---

## 11. Step 10 — Generate Traffic and Validate

Rate-based panels (Request Rate, P95 Latency, HTTP Breakdown, Operation Duration) need actual
HTTP traffic in the last 5 minutes to display data.

```bash
# Generate mixed traffic — creates products, fetches, and 404s
for i in {1..10}; do
  curl -s -X POST http://localhost:8082/api/products \
       -H "Content-Type: application/json" \
       -d '{"name":"Product'$i'","price":99.9}' > /dev/null

  curl -s http://localhost:8082/api/products > /dev/null
  curl -s http://localhost:8082/api/products/99999 > /dev/null || true
  sleep 0.3
done
```

**Wait 30 seconds** (Prometheus scrape interval) then refresh the dashboard.

**Expected after traffic:**

| Panel | Expected |
|-------|----------|
| Request Rate | Non-zero line (e.g., 0.01 req/s) |
| Error Rate | 0% flat line (no 5xx generated) |
| P95 Latency | p50/p95/p99 lines with ms values |
| JVM Heap | Same value, already showing |
| DB Pool | Active connections during traffic, 0 after |
| HTTP Breakdown | Separate 200, 201, 404 series |
| Products Created | ~10 (matches POST count) |
| Products Not Found | ~10 (matches /99999 count) |
| Operation Duration | p50/p95/p99 ms per operation type |

---

## 12. Verification Checklist

```
□ docker-compose ps shows both prometheus and grafana as Up
  docker-compose ps

□ Grafana datasource test passes (Save & Test → green)
  http://localhost:3000 → Connections → Data Sources → Prometheus

□ Dashboard loads without errors (no "Failed to upgrade legacy queries" toast)
  http://localhost:3000 → Dashboards → Spring Boot — RED Dashboard

□ Application dropdown shows "spring-boot-starter"
  (if empty: Spring Boot not running or no metrics scraped yet)

□ JVM Heap and DB Pool panels show values without any traffic
  (these are gauges — instant values, always present)

□ Error Rate shows "0%" not "No data" when app is healthy with no traffic
  (validates the `or vector(0)` fix is working)

□ After generating traffic: Request Rate panel shows non-zero values
  curl -s http://localhost:8082/api/products (5+ times)
  Wait 30s → refresh dashboard

□ After POST requests: Products Created counter shows expected count
  curl -s http://localhost:9091/actuator/prometheus | grep products_added_total
  (compare value in Prometheus text output vs dashboard)

□ After 404 requests: Products Not Found shows expected count
  curl -s http://localhost:9091/actuator/prometheus | grep products_not_found_total
```

---

## 13. Common Mistakes and What Goes Wrong

| Mistake | Symptom | Fix |
|---------|---------|-----|
| `url: http://localhost:9090` in datasource | "Bad Gateway" on Save & Test | Use container service name: `http://prometheus:9090` |
| Missing `/actuator/**` in SecurityConfig | All panels "No data", Prometheus target shows 401 | Add `/actuator/**` to OPEN_PATHS in SecurityConfig |
| `__inputs` block in provisioned JSON | "Failed to upgrade legacy queries" toast | Remove `__inputs` block, hardcode `uid: "prometheus"` |
| Legacy string form in variable query | "Failed to upgrade legacy queries" toast | Use Grafana 11 object form with `qryType`, `refId` |
| `datasource` variable using `pluginId` | "No data sources found", all panels blank | Remove datasource variable, hardcode uid in each panel |
| Stale `grafana_data` volume | Provisioning ignored, empty datasource list | `docker-compose down -v && docker-compose up -d` |
| Missing provisioning volume in compose | Dashboard never appears, manual import needed | Add both volume mounts to Grafana service in compose |
| Rate panels after traffic stops | "No data" on Request Rate, P95, Breakdown | Expected behavior — rate([5m]) needs traffic in last 5 min |
| `products_created_total` metric name | Prometheus shows `products_total` (wrong) | Use `products.added` → `products_added_total` (correct) |
| Spring Boot running without dev profile | All API requests return 401, business metrics = 0 | Run with `-Dspring-boot.run.profiles=dev` |

---

## 14. Architecture Adaptation Notes

### Adding a New Service's Dashboard

1. Create `docker/grafana/dashboards/your-service-RED.json`
2. Change `title`, `uid`, and `application` variable default value
3. Update all PromQL `application="$application"` references (they work as-is via the variable)
4. Grafana auto-loads within `updateIntervalSeconds` (30s) — no container restart needed

### Multi-Service Dashboard (Single Dashboard, Service Switcher)

The `application` variable already supports this. If two services set:
```properties
management.metrics.tags.application=certificate-service
management.metrics.tags.application=payment-service
```
The Application dropdown automatically shows both. Selecting one filters all panels to that service.

### Adding Custom Business Metric Panels

```java
// In your new service's ServiceImpl:
Counter.builder("applications.submitted")
    .description("Total certificate applications submitted")
    .register(meterRegistry);
```

```json
// In dashboard JSON, add a new panel:
{
  "type": "stat",
  "title": "Applications Submitted (last 1h)",
  "targets": [{
    "expr": "increase(applications_submitted_total{application=\"$application\"}[1h])"
  }]
}
```

### Hexagonal Architecture

Business metrics (counters, timers) belong in the **Application Service layer** — the use-case
orchestrator. Not in inbound adapters (controllers) and not in outbound adapters (repositories).

```
com.biharone.certificate.application.service.CertificateApplicationService
    ↓ Counter.builder("certificate.applications.submitted")
    ↓ Timer.builder("certificate.processing.duration")
```

Outbound adapter metrics (DB query timers, external API call durations) belong in the
**Outbound Adapter** layer:
```
com.biharone.certificate.adapter.out.persistence.CertificateJpaAdapter
    ↓ Timer.builder("adapter.db.certificate.query.duration")
```

---

*Document maintained alongside codebase. Update whenever dashboard panels, provisioning config,
or PromQL queries change. Grafana version: 11.1.0*
