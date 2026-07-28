# Logging Infrastructure Setup Guide — Module 7
### Reusable across any Spring Boot project — Maven or Gradle, MVC or Hexagonal

> **What this document is**: A step-by-step implementation guide for centralizing logs into
> Grafana Loki, using a direct-push Logback appender instead of a log-shipping agent, and closing
> the loop with Module 8's tracing so a trace can jump straight to its own log lines. Every
> dependency, property, and config file is explained — what it does, why it exists, and what
> breaks if removed.
>
> **Who should follow this**: Backend engineers adding centralized logging to a service already
> instrumented per Modules 1–6 (and ideally Module 8, for the trace-to-logs payoff), AI assistants
> replicating this setup, or engineers debugging why logs aren't reaching Loki.

---

## Table of Contents

1. [Conceptual Overview — Read Before Coding](#1-conceptual-overview--read-before-coding)
2. [Step 1 — Add Dependencies](#2-step-1--add-dependencies)
3. [Step 2 — Configure Logback](#3-step-2--configure-logback)
4. [Step 3 — Loki Docker Setup](#4-step-3--loki-docker-setup)
5. [Step 4 — Grafana Loki Datasource](#5-step-4--grafana-loki-datasource)
6. [Step 5 — Closing the Loop: Trace-to-Logs](#6-step-5--closing-the-loop-trace-to-logs)
7. [Step 6 — The Permission Denied Gotcha](#7-step-6--the-permission-denied-gotcha)
8. [Step 7 — Verify End-to-End](#8-step-7--verify-end-to-end)
9. [Verification Checklist](#9-verification-checklist)
10. [Common Mistakes and What Goes Wrong](#10-common-mistakes-and-what-goes-wrong)
11. [Architecture Adaptation Notes](#11-architecture-adaptation-notes)
12. [Known Limitations](#12-known-limitations)

---

## 1. Conceptual Overview — Read Before Coding

### Why This Module Exists

By Module 6, every service already writes structured logs (console in dev, JSON file in prod —
Module 2) and exposes metrics (Module 4/5) and traces (Module 8). But logs still only exist
*locally* — on whatever machine or container is running the app. During an incident spanning
multiple BiharOne services, "SSH into each pod and grep its local log file" doesn't scale. Loki
centralizes logs the same way Prometheus centralizes metrics: one place to query across every
service, correlated by the same labels and trace IDs already flowing through the system.

### Why Grafana Loki (Not Elasticsearch/Splunk)

This course has built everything around Grafana as the single pane of glass (Modules 6 and 8).
Loki is Grafana Labs' own log aggregation system — logs land in the same Grafana instance
already showing metrics dashboards and traces, with no separate UI, login, or query language
family to learn (LogQL deliberately mirrors PromQL's structure).

The other key difference from Elasticsearch: Loki indexes only **labels** (small, bounded
metadata like `app`, `level`), not the full text of every log line. This keeps storage and
indexing cost far lower — the same high-cardinality discipline from Module 4/5's Prometheus tags
applies here identically.

### Why Direct Push (loki-logback-appender), Not Promtail

The standard way logs reach Loki in production is an agent (Promtail, or its successor Grafana
Alloy) tailing log files or container stdout and shipping them to Loki. That model assumes logs
land somewhere the agent can read — a file, or a container's stdout stream.

This project's Spring Boot app runs on the **host**, not in a container (see `docker-compose.yml`
— deliberate, for fast local restarts). There's no container stdout for an agent to tail, and the
dev profile doesn't write to a file either (console only, per Module 2). Rather than invent a
file just to have something to tail, **`loki-logback-appender`** pushes log events directly from
the JVM to Loki's HTTP push API — the same reasoning already used for Tempo's OTLP export in
Module 8.

**This is a dev-environment decision, not a universal one** — see §11 for what changes in a real
containerized/Kubernetes deployment.

### What's Automatic vs. What You Still Write

| Concern | Automatic? |
|---|---|
| Every log line the app already emits reaching Loki | ✅ Yes — one appender added to the existing root logger |
| `traceId`/`spanId` attached to each log line in Loki | ✅ Yes — reads the same MDC keys Module 8 already populates |
| Jumping from a Tempo span to its exact log lines | ✅ Yes, once wired — Grafana's built-in `tracesToLogsV2` feature |
| Deciding what's a Loki **label** vs **structured metadata** | ❌ No — this is a deliberate design choice, see §3 |
| Log retention / durable storage for compliance timeframes | ❌ No — separate topic, see `docs/object-storage-for-observability.md` |

---

## 2. Step 1 — Add Dependencies

### For Maven projects (`pom.xml`)

```xml
<!--
  loki-logback-appender
  Pushes logs directly from this JVM to Grafana Loki over HTTP — no Promtail or
  file-tailing needed. Chosen because this app runs on the HOST, not in a
  container, so there's no stdout for an agent to tail in this dev setup.
  Third-party library, NOT covered by Spring Boot's dependency management —
  version verified against Maven Central (search.maven.org), not guessed.
  WHAT HAPPENS IF REMOVED: logs stay in console/file only, never reach Loki.
-->
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>2.0.0</version>
</dependency>
```

### For Gradle projects (`build.gradle` — Groovy DSL)

```groovy
dependencies {
    implementation 'com.github.loki4j:loki-logback-appender:2.0.0'
}
```

---

## 3. Step 2 — Configure Logback

**File**: `src/main/resources/logback-spring.xml` (dev profile)

```xml
<appender name="LOKI" class="com.github.loki4j.logback.Loki4jAppender">
    <http>
        <url>http://localhost:3100/loki/api/v1/push</url>
    </http>
    <labels>
        app = ${APP_NAME}
    </labels>
    <structuredMetadata>
        level = %level
        traceId = %X{traceId:-}
        spanId = %X{spanId:-}
        requestId = %X{requestId:-}
    </structuredMetadata>
    <message>
        <pattern>[%thread] %logger{40} - %msg%n%ex</pattern>
    </message>
</appender>

<root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="LOKI"/>
</root>
```

**The most important design decision here — labels vs. structured metadata:**

`<labels>` are Loki's **indexed** fields — this is exactly the same high-cardinality rule as
Prometheus tags from Module 4/5. Only `app` is a label here. Adding `traceId` as a label would be
the logging equivalent of tagging a Prometheus metric with a unique request ID: every request
creates a brand-new label value, and Loki's index grows without bound until it falls over.

`<structuredMetadata>` attaches per-log-line data (`traceId`, `spanId`, `requestId`, `level`) that
stays fully queryable and filterable, without becoming part of the index. This is precisely why
`traceId`/`spanId` — high-cardinality by nature, one unique value per request — belong here and
not in `<labels>`.

`http.url` for the push endpoint uses `localhost`, not a Docker service name, for the same reason
as Module 8's OTLP endpoint: the app runs on the host, so it reaches Loki through the port Loki
publishes to the host.

---

## 4. Step 3 — Loki Docker Setup

**File**: `docker/loki/loki-config.yaml`

```yaml
auth_enabled: false

server:
  http_listen_port: 3100

common:
  instance_addr: 127.0.0.1
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2020-10-24
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

limits_config:
  allow_structured_metadata: true
```

This is Loki's own maintained single-node example config (`cmd/loki/loki-docker-config.yaml` in
the `grafana/loki` repository), not hand-guessed — schema/store version fields have changed
across Loki releases, and getting them wrong means Loki fails to start entirely.

`limits_config.allow_structured_metadata: true` is explicit here rather than relying on whatever
schema v13's default happens to be, since this project's Logback config specifically depends on
structured metadata being accepted (§3).

### `docker-compose.yml` Service Addition

```yaml
loki:
  image: grafana/loki:3.6.0
  container_name: loki
  restart: unless-stopped
  user: "0:0"   # see §7 — required on a fresh named volume
  command:
    - '-config.file=/etc/loki/loki-config.yaml'
  ports:
    - "3100:3100"
  volumes:
    - ./docker/loki/loki-config.yaml:/etc/loki/loki-config.yaml:ro
    - loki_data:/loki
  networks:
    - observability
```

---

## 5. Step 4 — Grafana Loki Datasource

**File**: `docker/grafana/provisioning/datasources/loki.yml`

```yaml
apiVersion: 1

datasources:
  - name: Loki
    type: loki
    uid: loki
    url: http://loki:3100
    access: proxy
    editable: false
```

Same structure as the Prometheus and Tempo datasources from earlier modules — `loki` resolves via
Docker's internal DNS to the Loki container (Grafana itself IS containerized, unlike the app).

---

## 6. Step 5 — Closing the Loop: Trace-to-Logs

Module 8's guide deliberately left this disabled, since Loki didn't exist yet. Now it does.

**File**: `docker/grafana/provisioning/datasources/tempo.yml` — add to `jsonData`:

```yaml
jsonData:
  tracesToLogsV2:
    datasourceUid: loki
    spanStartTimeShift: '-5m'
    spanEndTimeShift: '5m'
    filterByTraceID: false
    filterBySpanID: false
    tags:
      - key: 'service.name'
        value: 'app'
```

Verified against Grafana's own provisioning docs
(`grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/provision/`), not
guessed — an earlier attempt in this exact project used a documented-sounding method name for an
unrelated Spring Security config that turned out not to exist in the actual library version, so
config shapes get checked against a live source before being written here.

`tags` maps the span's OTel resource attribute `service.name` to this project's Loki label `app`
(§3) — this is what lets Grafana build a matching Loki query scoped to the right service and time
window when you click "Logs for this span."

`filterByTraceID` is left `false` deliberately: this project's structured metadata field is
camelCase `traceId` (matching Micrometer Tracing's MDC key), and Grafana's `filterByTraceID`
option may expect a different exact field name — unverified, so left off rather than risk a
silent partial mismatch. Tag-plus-time-window filtering alone is still a real improvement over
manually copying a traceId between Tempo and Loki by hand.

---

## 7. Step 6 — The Permission Denied Gotcha

**What happened during this project's own verification** — worth documenting exactly as
encountered, since it's a well-known, common issue with this exact Docker image:

```
loki  | mkdir /loki/chunks: permission denied
loki  | error creating object client
```

Loki's official Docker image runs as a **non-root user** by default. A freshly-created Docker
named volume (`loki_data`) is **root-owned** until something writes to it. When non-root Loki
tries `mkdir /loki/chunks` inside that root-owned volume on first boot, it fails — this is a
widely-reported issue specific to `grafana/loki` images on Docker/docker-compose (not Kubernetes,
where this is handled differently via `securityContext`/`fsGroup`).

**The pragmatic local-dev fix**:

```yaml
loki:
  user: "0:0"   # run as root — bypasses the permission mismatch entirely
```

**Why this is fine here, and what NOT to do in real production**: this repo's `docker-compose.yml`
is a local practice/verification environment. Running Loki as root here trades a security
best-practice for simplicity, which is an acceptable trade for local dev. Real production
deployments should keep Loki non-root and instead run a one-time init container that `chown`s the
volume to Loki's actual UID before Loki starts — more setup, but doesn't weaken the running
container's privileges. Not implemented here, to keep this repo's compose file focused on local
verification rather than production hardening.

---

## 8. Step 7 — Verify End-to-End

```bash
# 1. Bring up Loki (Prometheus/Grafana/Tempo already running from earlier modules)
docker-compose up -d

# 2. Confirm Loki is actually healthy before testing anything else
curl http://localhost:3100/ready
# Expect: "ready" — if you see "Ingester not ready: waiting for 15s after being ready",
# that's normal startup behavior, not an error — wait 15-20s and retry.

# 3. Rebuild and restart Spring Boot — pom.xml changed, needs a full rebuild
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=dev

# 4. Generate a few requests
curl http://localhost:8082/api/products
curl http://localhost:8082/api/products
```

**Check Grafana** (`http://localhost:3000`):
1. Explore → select **Loki** datasource → query `{app="spring-boot-starter"}` → Run Query →
   application log lines should appear (not just infra noise).
2. Explore → select **Tempo** → search for `spring-boot-starter` → click a Trace ID link (not
   just the row's expand arrow) → click directly on the span bar in the waterfall → a span detail
   popup opens showing Service/Duration/Kind, and a blue **"Logs for this span"** button.

---

## 9. Verification Checklist

```
□ mvn compile succeeds after adding the new dependency
  ./mvnw compile

□ docker-compose config validates without errors
  docker-compose config --quiet

□ Loki container reaches "ready", not stuck restarting
  docker-compose ps                    → loki should show "Up", not "Restarting"
  curl http://localhost:3100/ready     → "ready"

□ Application logs (not just infra scrape noise) appear in Grafana → Explore → Loki
  Query: {app="spring-boot-starter"}

□ traceId/spanId are visible as structured metadata on Loki log lines,
  and match the traceId/spanId shown in the corresponding Tempo trace

□ Clicking a span in Tempo's waterfall view (not just the search results row)
  shows a "Logs for this span" button
```

---

## 10. Common Mistakes and What Goes Wrong

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Using a fresh named volume without `user: "0:0"` | Loki crash-loops: `mkdir /loki/chunks: permission denied` | Add `user: "0:0"` to the Loki service — see §7 |
| Putting `traceId` in `<labels>` instead of `<structuredMetadata>` | Loki's index grows unbounded, same failure mode as a high-cardinality Prometheus tag | Keep only bounded values (like `app`) as labels |
| Checking `/ready` immediately after container start | `Ingester not ready: waiting for 15s after being ready` — looks like an error, isn't one | Wait 15-20 seconds, this is normal ring-stabilization behavior |
| Adding `tracesToLogsV2` before Loki actually exists | Grafana shows a broken/empty "Logs for this span" link | Only wire this after Loki is confirmed receiving logs |
| Assuming `filterByTraceID: true` "just works" | Possible silent mismatch if the structured metadata field name doesn't match what Grafana expects | Left `false` here deliberately — verify the exact expected field name before enabling |
| Forgetting `mvnw clean` after adding the dependency | Old compiled state may mask whether the new appender actually loaded | Always `clean` when `pom.xml` changes |

---

## 11. Architecture Adaptation Notes

### This Project's Dev Setup vs. Real Kubernetes/OKD Production

This guide's direct-push approach (`loki-logback-appender`) is specifically suited to a
host-based dev app talking to Dockerized infrastructure. **Do not carry this pattern unchanged
into a real Kubernetes/OKD deployment.** There, the standard and recommended approach is:

1. The application logs to **stdout only** (no Loki-specific code, no direct HTTP push) — this is
   the Kubernetes-native convention, and decouples the app from any specific log backend.
2. A cluster-level agent (Promtail, or its successor Grafana Alloy — typically run as a
   DaemonSet, one per node) tails every container's stdout and ships it to Loki.
3. This means the `loki-logback-appender` dependency and its Logback config block are **dev-only**
   — a real production `application-prod.properties`/profile should not carry them forward
   unchanged; the prod path relies on the platform's own log collection, not app-level pushing.

### Standard Layered (MVC) / Hexagonal Architecture

No package-level placement decisions — this is pure Logback/appender configuration, entirely
independent of application architecture style.

---

## 12. Known Limitations

1. **No durable, long-term storage.** This Loki instance uses `filesystem` storage on a local
   Docker volume — fine for verification, not for the multi-month retention a real service needs.
   See `docs/object-storage-for-observability.md` for the MinIO/OpenShift Data Foundation options
   evaluated for BiharOne specifically, and why NFS was ruled out for this use case.
2. **No retention policy configured.** Without `compactor.retention_enabled` +
   `limits_config.retention_period`, this Loki instance keeps data indefinitely (bounded only by
   disk space) — not a deliberate 6-month policy. See the same object-storage document for the
   exact config once durable storage is in place.
3. **`filterByTraceID` left off.** See §6 — enabling this precisely needs the exact structured
   metadata field name Grafana's Tempo datasource expects, which wasn't verified in this pass.
4. **Dev-only log shipping mechanism.** See §11 — this guide's direct-push pattern must NOT be
   assumed to carry forward into a real cluster deployment without reconsidering the approach.

---

*Document maintained alongside codebase. Update whenever Loki config, the Docker image version,
or the trace-to-logs wiring changes.*
