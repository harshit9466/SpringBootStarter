# Grafana Setup Guide
### Provisioned Dashboards + Prometheus Datasource — Spring Boot 3.x

> **What this guide adds**: Grafana auto-configured with Prometheus datasource and a RED method
> dashboard (Rate, Errors, Duration) — all via provisioning files, no manual UI clicks required.
> Restart the container and everything is already there.

---

## Prerequisites

- Guide 04 (Prometheus) complete — `docker-compose.yml` already has Grafana service
- Spring Boot app running on port 8082 with `/actuator/prometheus` exposed
- Micrometer metrics present (`products_added_total`, `product_operation_duration_seconds`)

---

## Step 1 — Create Provisioning Directory Structure

```
docker/
└── grafana/
    ├── provisioning/
    │   ├── datasources/
    │   │   └── prometheus.yml        ← Prometheus auto-connect
    │   └── dashboards/
    │       └── dashboards.yml        ← Dashboard folder config
    └── dashboards/
        └── spring-boot-RED.json      ← Actual dashboard (version controlled)
```

These files are already created. Directory structure must match exactly — Grafana looks for
`/etc/grafana/provisioning/datasources/` and `/etc/grafana/provisioning/dashboards/` inside
the container.

---

## Step 2 — Verify docker-compose.yml Has Provisioning Volumes

The Grafana service in `docker-compose.yml` must have these two volume mounts:

```yaml
volumes:
  - grafana_data:/var/lib/grafana
  - ./docker/grafana/provisioning:/etc/grafana/provisioning:ro
  - ./docker/grafana/dashboards:/var/lib/grafana/dashboards:ro
```

**Why two volumes?**
- `provisioning/` → tells Grafana WHERE datasources and dashboards live
- `dashboards/` → the actual JSON files Grafana reads

**Why `:ro` (read-only)?**
Prevents Grafana from modifying your source files when it processes them.

---

## Step 3 — Restart Stack with Provisioning

```bash
# Existing containers must be removed so Grafana picks up new volumes
docker-compose down

# Restart with provisioning — Grafana reads all files on startup
docker-compose up -d

# Confirm both containers are running
docker-compose ps
```

---

## Step 4 — Verify in Grafana UI

Open: `http://localhost:3000` → Login: `admin` / `admin`

**Check 1 — Datasource auto-configured:**
- Left sidebar → Connections → Data Sources
- "Prometheus" should appear — URL: `http://prometheus:9090`
- Click it → "Save & Test" → "Successfully queried the Prometheus API"

**Check 2 — Dashboard auto-loaded:**
- Left sidebar → Dashboards → Browse
- "Spring Boot — RED Dashboard" should appear without any manual import

**Check 3 — Live data flowing:**
- Open the dashboard → select `spring-boot-starter` from the Application dropdown
- Rate, Error Rate, P95 Latency panels should show live data (may need to generate traffic first)

---

## Step 5 — Generate Traffic to See Data

```bash
# Generate some successful requests
curl http://localhost:8082/api/products
curl http://localhost:8082/api/products

# Generate a 404 (products_not_found_total counter)
curl http://localhost:8082/api/products/9999

# Wait 30s (Prometheus scrape interval) then check dashboard
```

---

## Dashboard Panels Explained

### Row 1 — RED Method

| Panel | PromQL | What to watch |
|-------|--------|---------------|
| Request Rate | `sum(rate(http_server_requests_seconds_count[5m]))` | Sudden drop = traffic stopped (bad); spike = load |
| Error Rate % | `100 * 5xx_count / total_count` | Should be < 1%. Alert threshold: > 1% for 5 min |
| P50/P95/P99 Latency | `histogram_quantile(0.95, ...)` | P99 tail = worst user experience |

### Row 2 — USE Method (Resources)

| Panel | What it shows | Alert when |
|-------|--------------|------------|
| JVM Heap Usage | Heap % of max | > 80% sustained → GC pressure |
| DB Connection Pool | Active vs Max vs Pending | Pending > 0 → saturation; Active/Max > 70% → alert |
| Request Breakdown by Status | 2xx / 4xx / 5xx split | 5xx rising independently of 4xx |

### Row 3 — Business Metrics

| Panel | Metric | Business meaning |
|-------|--------|-----------------|
| Products Created | `products_added_total` | Feature usage — drop to 0 = create flow broken |
| Products Not Found | `products_not_found_total` | Spike = stale client data or bad references |
| Operation Duration | `product_operation_duration_seconds` | Per-operation tail latency (getById, create, delete) |

---

## Adding Your Own Dashboard Panel

1. Export any JSON from Grafana UI:
   - Dashboard → top right "..." menu → JSON Model → Copy
2. Paste into `docker/grafana/dashboards/spring-boot-RED.json` as a new panel object
3. `docker-compose restart grafana` — panel appears immediately (no full stack restart needed)

Or add a new `.json` file in `docker/grafana/dashboards/` — Grafana loads all JSON files in that folder.

---

## Common Mistakes

| Mistake | Symptom | Fix |
|---------|---------|-----|
| `prometheus:9090` → `localhost:9090` in datasource | "Bad Gateway" error in datasource test | Use container service name, not localhost |
| Missing provisioning volume in docker-compose | Dashboard doesn't appear | Add both volume mounts, `docker-compose down && up` |
| `grafana_data` volume has old config | New provisioning ignored | `docker-compose down -v` to wipe volume (loses saved data), then `up` |
| Dashboard JSON has wrong metric names | Panels show "No data" | Check metric names at `/actuator/prometheus` — copy exact names |
| `application` tag not set in app | Variable dropdown empty | Set `management.metrics.tags.application=spring-boot-starter` in properties |

---

## Grafana vs Prometheus UI — When to Use Which

| Task | Use |
|------|-----|
| Quick PromQL exploration | Prometheus UI (`localhost:9090`) |
| Persistent dashboards for team | Grafana (`localhost:3000`) |
| Alerting rules | Grafana (Unified Alerting) |
| Check if target is being scraped | Prometheus UI → Targets |
| Historical trend analysis | Grafana (better time range controls) |
