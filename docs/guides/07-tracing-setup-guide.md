# Distributed Tracing Setup Guide — Module 8
### Reusable across any Spring Boot project — Maven or Gradle, MVC or Hexagonal

> **What this document is**: A step-by-step implementation guide for adding distributed tracing
> to a Spring Boot 3.x service using Micrometer Tracing, the OpenTelemetry bridge, and Grafana
> Tempo as the storage/query backend. Every dependency, property, and config file is explained —
> what it does, why it exists, and what breaks if removed.
>
> **Who should follow this**: Backend engineers adding tracing to a service that's already
> instrumented per Modules 2–6 (logging, actuator, metrics, Prometheus, Grafana), AI assistants
> replicating this setup, or engineers debugging why a trace isn't showing up.

---

## Table of Contents

1. [Conceptual Overview — Read Before Coding](#1-conceptual-overview--read-before-coding)
2. [Step 1 — Add Dependencies](#2-step-1--add-dependencies)
3. [Step 2 — Configure application.properties](#3-step-2--configure-applicationproperties)
4. [Step 3 — The MDC Collision Gotcha](#4-step-3--the-mdc-collision-gotcha)
5. [Step 4 — Tempo Docker Setup](#5-step-4--tempo-docker-setup)
6. [Step 5 — Grafana Tempo Datasource](#6-step-5--grafana-tempo-datasource)
7. [Step 6 — Verify End-to-End](#7-step-6--verify-end-to-end)
8. [Verification Checklist](#8-verification-checklist)
9. [Common Mistakes and What Goes Wrong](#9-common-mistakes-and-what-goes-wrong)
10. [Architecture Adaptation Notes](#10-architecture-adaptation-notes)
11. [Known Limitations — What This Setup Does NOT Give You Yet](#11-known-limitations--what-this-setup-does-not-give-you-yet)

---

## 1. Conceptual Overview — Read Before Coding

### The Problem Tracing Solves

Recall the Module 1 scenario:

```
Citizen Request
    → API Gateway (5ms)
        → Auth Service (45ms)
            → Certificate Service (2400ms) ← WHERE is the 2400ms going?
                → Document Verification Service (200ms)
                → Database Query 1 (50ms)
                → Database Query 2 (2100ms) ← AH. Slow DB query.
                → PDF Generator (50ms)
```

Logs tell you an event happened. Metrics tell you how often and how fast, in aggregate. Neither
tells you **where inside one specific slow request** the time actually went — especially once
that request crosses service boundaries. Tracing is the pillar built specifically to answer that.

### Core Vocabulary

| Term | Meaning |
|---|---|
| **Trace** | The complete journey of one request across every service it touches. Identified by one `traceId` shared by everything in that journey. |
| **Span** | One unit of work within a trace — e.g., "handle this HTTP request," "run this DB query." Has its own `spanId`, a start time, a duration, and a parent span (except the first one). |
| **Trace context** | The `traceId` + current `spanId` + sampling decision, propagated between services via HTTP headers. |
| **Propagation** | How trace context survives a network hop — the caller puts it in a header, the callee reads it and continues the same trace instead of starting a new one. |

### Why Micrometer Tracing (Not Raw OpenTelemetry)

Every earlier module in this course uses **Micrometer** as a vendor-neutral facade: write instrumentation once, swap backends by changing a dependency. Micrometer Tracing extends that same idea to spans:

```
Your Code → Micrometer Tracing API → [bridge] → Brave or OpenTelemetry → [exporter] → Tempo / Jaeger / Zipkin
```

This guide uses the **OpenTelemetry bridge** because OTLP (OpenTelemetry Protocol) is the
vendor-neutral wire format most modern backends accept natively — including Grafana Tempo.

### Why Grafana Tempo

This course has built everything around Grafana as the single pane of glass (Module 6). Tempo is
Grafana Labs' own tracing backend — traces land in the same Grafana instance already showing
metrics dashboards, with no separate UI to context-switch into.

### What You Get Automatically vs. What You Still Write

| Concern | Automatic? |
|---|---|
| A span created for every incoming HTTP request | ✅ Yes — zero code |
| `traceId` / `spanId` added to MDC for every log line | ✅ Yes — zero code, once the bridge dependency is present |
| Trace context propagated to outbound `RestTemplate`/`WebClient` calls | ✅ Yes — zero code |
| A child span for a specific business operation you want to see broken out | ❌ No — use `@Observed` or manual `Tracer` API (not covered in this guide) |
| A child span for JDBC/Hibernate queries | ❌ No — needs a separate library, see [§11](#11-known-limitations--what-this-setup-does-not-give-you-yet) |

This mirrors the exact same infrastructure-vs-business split covered in
[`observability-library-plan.md`](../observability-library-plan.md) for metrics — most of what
makes a trace useful is free; the rest is a deliberate choice about what's worth instrumenting.

---

## 2. Step 1 — Add Dependencies

### For Maven projects (`pom.xml`)

```xml
<!--
  micrometer-tracing-bridge-otel
  Wires Micrometer Tracing's API to the OpenTelemetry implementation.
  Auto-instruments every HTTP request/response and propagates trace context
  to outbound RestTemplate/WebClient calls — no code required for either.

  WHAT HAPPENS IF REMOVED: no spans are created at all. traceId/spanId never
  appear in MDC. management.tracing.* properties silently do nothing.
-->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!--
  opentelemetry-exporter-otlp
  Ships finished spans to a tracing backend over OTLP.

  WHAT HAPPENS IF REMOVED: spans are created in-memory but never sent anywhere.
  Tempo stays empty even though the app "has tracing."
-->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

Both are version-managed by `spring-boot-starter-parent` — no explicit `<version>` needed, same
convention as every other dependency in this project.

### For Gradle projects (`build.gradle` — Groovy DSL)

```groovy
dependencies {
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
}
```

---

## 3. Step 2 — Configure application.properties

### `src/main/resources/application-dev.properties`

```properties
# management.tracing.sampling.probability
# → Fraction of requests that get traced, 0.0 (none) to 1.0 (all).
# → DEV: 1.0 — trace everything so nothing is missed while exploring.
# → PROD: typically 0.05–0.1. At real production throughput, tracing every
#   single request is expensive to store and mostly redundant — you rarely
#   need ALL traces, just a representative sample plus every error.
#   (Error-biased sampling — always keep traces for failed requests regardless
#   of the sampling rate — needs a custom Sampler and isn't covered here.)
management.tracing.sampling.probability=1.0

# management.otlp.tracing.endpoint
# → Where finished spans are shipped, via OTLP/HTTP.
# → localhost, not a Docker service name: the Spring Boot app runs on the HOST
#   (see docker-compose.yml), so it reaches Tempo through the port Tempo
#   publishes to the host — exactly like spring.datasource.url uses
#   localhost:5432 instead of a container name.
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
```

### `src/main/resources/application-prod.properties` (reference — not created in this repo yet)

```properties
# Sample 10% of requests in production — enough to spot systemic latency
# patterns without storing every single trace at high request volume.
management.tracing.sampling.probability=0.1

# Points at the production Tempo endpoint / OTel Collector, not localhost.
management.otlp.tracing.endpoint=http://otel-collector.internal:4318/v1/traces
```

---

## 4. Step 3 — The MDC Collision Gotcha

**This is the most important section in this guide.** If you already have a custom request
filter that manually generates a "traceId" (built before real tracing existed — Module 2's
`MdcRequestFilter` did exactly this), it will silently collide with Micrometer Tracing.

### What Was There Before

```java
// BEFORE — Module 2's stopgap, written before real tracing existed
String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
if (traceId == null || traceId.isBlank()) {
    traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
}
MDC.put("traceId", traceId);
```

### Why This Breaks Once Tracing Is Added

Once `micrometer-tracing-bridge-otel` is on the classpath, Spring Boot automatically populates
MDC with `traceId` and `spanId` for the real, W3C-propagated distributed trace — **using the
exact same MDC key name**, `"traceId"`. Two independent systems now write to one key:

- Your custom filter writes a **random UUID** that has no relationship to any actual trace.
- Micrometer Tracing writes the **real trace ID** that Tempo also has, tied to the actual span.

Whichever one executes last for a given log line wins — non-deterministically, depending on
filter ordering and exactly when each request-scoped context opens. In the worst case, your logs
show a `traceId` that **does not match** what Tempo has for the same request. That silently
defeats the entire point of trace-to-log correlation: you can no longer paste a `traceId` from a
log line into Tempo and find the matching trace.

### The Fix

Remove the manual `traceId` generation entirely. Let Micrometer Tracing own that MDC key alone.
Keep anything your custom filter does that Micrometer Tracing has no way to know about:

```java
// AFTER — traceId/spanId no longer set here at all
private static final String REQUEST_ID_HEADER = "X-Request-Id";
private static final String USER_ID_HEADER    = "X-User-Id";

// requestId stays: it serves a DIFFERENT purpose than traceId — a simple,
// client-facing correlation ID (safe to read back to an end user or support
// ticket) that's independent of the tracing system's internal ID format.
String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
if (requestId == null || requestId.isBlank()) {
    requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
}
MDC.put("requestId", requestId);
```

**Rule of thumb when introducing tracing into an existing project**: grep the codebase for any
place that manually does `MDC.put("traceId", ...)` or `MDC.put("spanId", ...)` before adding the
tracing dependency. If one exists, remove it — don't let it coexist "just in case."

### Update the Logback Pattern

```xml
<!-- traceId/spanId now populated automatically by Micrometer Tracing — no
     filter code sets these. Use :- (default-empty) instead of a bare %X{}
     to avoid a literal "null" string during startup or on threads with no
     active span (scheduled tasks, background jobs). -->
<pattern>
    %d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [${APP_NAME}] [traceId=%X{traceId:-}] [spanId=%X{spanId:-}] [%thread] %logger{40} - %msg%n%ex
</pattern>
```

And add `spanId` to the production JSON encoder's MDC field list alongside the existing
`traceId`, `requestId`, `userId` entries so it's queryable in your log aggregator once Module 7
(Loki) exists.

---

## 4a. A Second Gotcha Found During Verification — Disconnected Security Spans

After wiring everything up, Grafana's Tempo search showed traces named "security filterchain
before" / "security filterchain after" **as their own disconnected root traces**, not as child
spans of the actual HTTP request trace. An orphan span with no parent is worse than no span at
all — it clutters trace search with entries that can never be correlated back to the request
that caused them.

**Root cause**: once an `ObservationRegistry` bean exists (added automatically by
`micrometer-tracing-bridge-otel`), Spring Security starts emitting its own observations for
filter chain execution.

**The fix — and a lesson in verifying documentation against the actual classpath:**

`SecurityObservationSettings` (Spring Security 6.4+, package
`org.springframework.security.config.observation`) is the documented control for this. A first
attempt used a method name pulled from a documentation lookup — `shouldObserveFilterChains(...)`
— which **failed to compile**: that method doesn't exist on `SecurityObservationSettings.Builder`
in the actual Spring Security 6.5.1 on this project's classpath. Rather than guess again, the
actual `-sources.jar` for the exact version in use was extracted and read directly. The real API:

```java
public final class SecurityObservationSettings {
    // By default: observeRequests=false, observeAuthentications=true, observeAuthorizations=true
    public static Builder withDefaults() { return new Builder(false, true, true); }

    public static final class Builder {
        public Builder shouldObserveRequests(boolean excludeFilters) { ... }
        public Builder shouldObserveAuthentications(boolean excludeAuthentications) { ... }
        public Builder shouldObserveAuthorizations(boolean excludeAuthorizations) { ... }
    }
}
```

The field controlling filter-chain/request-level spans is `observeRequests`, exposed via
`shouldObserveRequests(boolean)` — not `shouldObserveFilterChains`. The fix:

```java
@Bean
public SecurityObservationSettings securityObservationSettings() {
    return SecurityObservationSettings.withDefaults()
            .shouldObserveRequests(false)
            .build();
}
```

**Why this matters beyond this one bug**: a documentation source (even a normally reliable one)
described an API that didn't match the exact dependency version actually resolved by this
project's `pom.xml`. When a compile error contradicts what documentation says exists, the
resolved JAR on disk is the ground truth — `mvn dependency:build-classpath` or unzipping the
matching `-sources.jar` from the local Maven repository settles it immediately, rather than
guessing a second time.

**A third round — "authorize request" spans kept appearing.** After fixing the above, a fresh
verification pass still showed disconnected "authorize request" traces, recurring every ~15
seconds — matching Prometheus's `scrape_interval` hitting `/actuator/prometheus`. That scrape
traffic goes through the security filter chain's authorization check like any other request
(even in dev's `permitAll` chain), so this noise never stops in a real deployment — it isn't a
one-time artifact of testing.

`SecurityObservationSettings` also has `shouldObserveAuthorizations(boolean)` — confirmed by the
same source read, not guessed again:

```java
@Bean
public SecurityObservationSettings securityObservationSettings() {
    return SecurityObservationSettings.withDefaults()
            .shouldObserveRequests(false)
            .shouldObserveAuthorizations(false)
            .build();
}
```

**The trade-off being made**: this disables authorization spans for every request, not just
actuator/scrape traffic — including genuine `/api/**` business calls. A more surgical fix exists
(filter by request path via a custom `ObservationPredicate` checked against Spring Security's
`AuthorizationObservationContext<T>`), but that context's authorized object is generic — its
exact runtime type would need its own verification pass before relying on it. Given this
project's authorization is a simple JWT role check (not a slow custom `AuthorizationManager`),
the diagnostic value of a per-request authorization span is low here. The blanket disable was
accepted rather than guessing at a third layer of unverified internals in one session — revisit
this if a project's authorization logic later becomes complex enough that per-request timing
would actually matter.

---

## 5. Step 4 — Tempo Docker Setup

**File**: `docker/tempo/tempo.yaml`

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        http:
        grpc:

ingester:
  max_block_duration: 5m

compactor:
  compaction:
    block_retention: 24h

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces
    wal:
      path: /var/tempo/wal
```

**Line-by-line:**

`server.http_listen_port: 3200` — Tempo's own query API. This is the port Grafana's Tempo
datasource connects to, and the port you'd hit directly for Tempo's HTTP API.

`distributor.receivers.otlp.protocols.http` / `.grpc` — Tempo accepts spans over OTLP in either
transport. We use HTTP here because `management.otlp.tracing.endpoint` in Spring Boot defaults
to HTTP. Both are enabled with empty blocks, which means "use default settings" for each.

`ingester.max_block_duration: 5m` — how long Tempo buffers incoming spans in memory before
flushing them to a queryable block on disk. Lower = spans queryable sooner but more I/O overhead.

`compactor.compaction.block_retention: 24h` — how long trace data is kept before deletion. 24
hours is fine for local dev; production retention is a cost/usefulness trade-off, typically
7–30 days depending on compliance requirements (see Module 10).

`storage.trace.backend: local` — stores trace blocks on local disk. Production deployments
typically use object storage (`s3`, `gcs`, `azure`) instead so multiple Tempo replicas can share
one storage backend — not needed for a single local instance.

**Deliberately NOT included**: the metrics-generator (which would compute Grafana's Service Graph
from span data and push it to Prometheus via remote-write). Enabling it now, with nothing set up
to consume that data, would silently produce nothing — the same class of mistake documented in
[`06-grafana-setup-guide.md` §9](06-grafana-setup-guide.md#9-step-8--grafana-11-specific-gotchas)
for the datasource template variable. Add it when there's an actual reason to use Service Graph.

### `docker-compose.yml` Service Addition

```yaml
tempo:
  image: grafana/tempo:2.5.0
  container_name: tempo
  restart: unless-stopped
  command:
    - '-config.file=/etc/tempo.yaml'
  ports:
    - "3200:3200"   # Tempo query API — Grafana connects here
    - "4317:4317"   # OTLP gRPC receiver
    - "4318:4318"   # OTLP HTTP receiver — Spring Boot app sends spans here
  volumes:
    - ./docker/tempo/tempo.yaml:/etc/tempo.yaml:ro
    - tempo_data:/var/tempo
  networks:
    - observability
```

Same volume-mount pattern as Prometheus and Grafana: config file mounted read-only, a named
volume for the data Tempo writes so it survives `docker-compose down` (but not `down -v`).

---

## 6. Step 5 — Grafana Tempo Datasource

**File**: `docker/grafana/provisioning/datasources/tempo.yml`

```yaml
apiVersion: 1

datasources:
  - name: Tempo
    type: tempo
    uid: tempo
    url: http://tempo:3200
    access: proxy
    editable: false
```

Same structure as the Prometheus datasource from Module 6 — `tempo` resolves via Docker's
internal DNS to the Tempo container, `access: proxy` means Grafana's server (not the browser)
makes the request.

**Deliberately NOT included**: `jsonData.tracesToLogsV2` (would let you jump from a span
directly to its log lines — needs Loki, which is Module 7) and `jsonData.serviceMap` (needs
Tempo's metrics-generator, deliberately not enabled — see §5 above). Configuring either now
would point at data sources that don't exist yet, producing the same "looks wired up but shows
nothing" trap already hit once this session with the old `datasource` template variable in the
RED dashboard. Add both once Module 7 lands.

---

## 7. Step 6 — Verify End-to-End

```bash
# 1. Bring up Tempo (Prometheus + Grafana already running from Module 6)
docker-compose up -d

# 2. Rebuild and restart Spring Boot — pom.xml changed, needs a full rebuild
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Generate a few requests
curl http://localhost:8082/api/products
curl http://localhost:8082/api/products
curl http://localhost:8082/api/products/99999
```

**Check the console log output** — you should see real hex trace/span IDs, not the old
16-character random UUID format:

```
2026-07-28 14:32:10.442  INFO [spring-boot-starter] [traceId=6a1f9e2b8c3d4a5f...] [spanId=3f7a9c2d1b4e...] [http-nio-8082-exec-1] c.S.controller.ProductController - Fetching all products
```

**Then check Grafana** (`http://localhost:3000`):
1. Left sidebar → **Explore**
2. Top-left datasource dropdown → select **Tempo**
3. Search tab → Service Name: `spring-boot-starter` → Run Query
4. Click any trace → see the span waterfall (currently one span per HTTP request — see §11)

**Cross-check correlation**: copy a `traceId` from the console log, paste it into Tempo's
"TraceQL" or "Trace ID" search field in Grafana Explore. It should find the exact same trace —
this is the proof that the MDC collision fix in §4 actually worked.

---

## 8. Verification Checklist

```
□ mvn compile succeeds after adding the two new dependencies
  ./mvnw compile

□ docker-compose config validates without errors
  docker-compose config --quiet

□ Tempo container is running and healthy
  docker-compose ps
  docker-compose logs tempo | grep -i "server listening"

□ Console logs show real hex traceId/spanId, not the old 16-char random UUID
  (compare against a log line captured BEFORE this module, if you have one)

□ Grafana → Explore → Tempo → Search finds traces for spring-boot-starter

□ A traceId copied from a console log line finds the SAME trace in Tempo
  (proves the MDC collision fix in §4 is actually correct, not just "no errors")

□ Startup log line confirms the profile:
  The following 1 profile is active: "dev"
```

---

## 9. Common Mistakes and What Goes Wrong

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Leaving a custom `MDC.put("traceId", ...)` in an existing filter | Logs show a traceId that never matches any trace in Tempo | Remove manual traceId generation — see §4 |
| Adding tracing without a `SecurityObservationSettings` bean | Tempo fills with disconnected "filterchain before/after" AND "authorize request" traces | Add the bean from §4a — `shouldObserveRequests(false)` + `shouldObserveAuthorizations(false)` |
| Not accounting for Prometheus's own scrape traffic | "authorize request" spans recur forever at exactly the scrape_interval, in every environment | Same fix as above — this isn't a one-time test artifact, monitoring traffic never stops |
| Forgetting `mvnw clean` after adding dependencies | Old compiled classes may mask whether the new deps actually took effect | Always `clean` when `pom.xml` changes, same rule as any dependency change |
| Using `http://tempo:4318` in `application-dev.properties` | Connection refused — app runs on host, `tempo` only resolves inside the Docker network | Use `http://localhost:4318/v1/traces` since the app is not containerized |
| Setting `management.tracing.sampling.probability=0.1` in dev | Most requests aren't traced at all while exploring; confusing "why is there no trace for that request" | Use `1.0` in dev, lower it only in prod |
| Enabling Tempo's metrics-generator with nothing consuming it | No error, but Service Graph tab in Grafana just stays empty | Don't enable it until there's a concrete plan to use Service Graph |
| Adding `tracesToLogsV2` to the Tempo datasource before Loki exists | Grafana shows a broken/empty "Logs for this span" link | Add it only after Module 7 (Loki) is in place |
| Expecting a DB query as its own span with no extra library | Confusing "the trace only has one span" | See §11 — JDBC spans need `datasource-micrometer-spring-boot`, not included by default |

---

## 10. Architecture Adaptation Notes

### Standard Layered (MVC) Architecture

No extra placement decisions — Micrometer Tracing instruments at the HTTP filter/servlet level
automatically, regardless of what's underneath. No package changes needed vs. Modules 2–6.

### Hexagonal / Ports & Adapters Architecture

- **Inbound Adapters**: automatic — the span starts before your controller code even runs.
- **Application Services**: if you want a named child span around a specific use case (e.g.,
  "validate then persist"), that's where you'd add a manual `@Observed` annotation or use the
  injected `Tracer` bean directly — not covered in this guide, see §11.
- **Outbound Adapters**: trace context propagates automatically to any outbound `RestTemplate`/
  `WebClient` call made from here — if this service calls another BiharOne service, the callee
  (if it also has Micrometer Tracing configured) automatically continues the SAME trace, not a
  new one. This is what makes cross-service latency visible in one place.

### Multi-Service Propagation (The Actual BiharOne Use Case)

If Certificate Service calls Payment Service via `RestTemplate`, and BOTH services have this
exact setup, no extra code is needed for the trace to span both services — Micrometer Tracing's
HTTP client instrumentation automatically injects the `traceparent` header on the outbound call,
and the receiving service's server instrumentation automatically reads it and continues the same
trace instead of starting a new one. This is the entire payoff described in Module 1 §1.7 — it
requires every participating service to have this module applied, not just one.

---

## 11. Known Limitations — What This Setup Does NOT Give You Yet

Being explicit about scope boundaries, same as every other guide in this bundle:

1. **No JDBC/Hibernate spans.** A trace currently shows one span for "handle this HTTP request"
   with no breakdown of how much of that time was the database. Getting a DB query as its own
   child span needs `net.ttddyy.observation:datasource-micrometer-spring-boot` wrapping the
   `DataSource` bean — not added here since it's a third-party library not covered by Spring
   Boot's dependency management, and the exact compatible version needs to be verified against
   whatever Spring Boot/Micrometer version is in use at the time, not guessed.
2. **No trace-to-logs correlation in Grafana.** Needs Loki (Module 7). The `traceId` is already
   in both places (Tempo and your log lines) — you can search by hand in Grafana Explore today,
   just not with a one-click "Logs for this span" button yet.
3. **No Service Graph.** Needs Tempo's metrics-generator pushing span metrics to Prometheus —
   deliberately deferred, see §5 and §12 below for the exact setup when it's actually needed.
4. **No manual/custom spans.** Everything here is automatic HTTP-level instrumentation. Wrapping
   a specific business operation in its own named span (e.g., "pdf-generation") needs the
   `Tracer` API or `@Observed` — a natural next step once the automatic baseline is verified
   working, not covered in this guide to keep it scoped to "get tracing working end-to-end."

---

## 12. Service Graph — The Exact Setup for When It's Actually Needed

**When to do this**: NOT in a single-service demo/practice repo — a graph needs at least two
services calling each other to show anything. Do this on the SHARED Tempo/Prometheus/Grafana
that multiple real services (e.g. multiple BiharOne services) point to, once at least two of
them have this guide's tracing setup AND call each other over HTTP (`RestTemplate`/`WebClient`).

**Zero application code changes are needed.** Service Graph is built entirely from span data
that traced services are *already* sending once they follow this guide — the client span (from
the calling service) and the server span (in the called service) are already correlated via the
`traceparent` header propagation described in §10. This is three infrastructure config changes,
not a per-service task, and nobody needs to remember to do anything extra in application code.

### 12.1 Tempo — Enable the Metrics-Generator

**File**: `docker/tempo/tempo.yaml`

```yaml
overrides:
  defaults:
    metrics_generator:
      processors: [service-graphs, span-metrics]

metrics_generator:
  registry:
    external_labels:
      source: tempo
  storage:
    path: /var/tempo/generator/wal
    remote_write:
      - url: http://prometheus:9090/api/v1/write
        send_exemplars: true
```

`processors: [service-graphs, span-metrics]` — tells Tempo to compute both the service-to-service
call graph AND per-span latency/rate/error metrics from incoming spans.
`remote_write` — where Tempo pushes the computed metrics. Points at Prometheus's remote-write
endpoint (container name, same reasoning as every other in-cluster/in-network hostname in this
bundle).

### 12.2 Prometheus — Accept the Pushed Metrics

**File**: `docker-compose.yml` (or the equivalent Prometheus deployment config)

```yaml
command:
  - '--config.file=/etc/prometheus/prometheus.yml'
  - '--storage.tsdb.path=/prometheus'
  - '--storage.tsdb.retention.time=7d'
  - '--web.enable-lifecycle'
  - '--web.enable-remote-write-receiver'   # ← ADD THIS — without it Tempo's push is rejected
  - '--web.console.libraries=/etc/prometheus/console_libraries'
  - '--web.console.templates=/etc/prometheus/consoles'
```

Without `--web.enable-remote-write-receiver`, Prometheus does not accept incoming pushed metrics
at all by default — Tempo's `remote_write` would silently fail with nothing to show for it. This
is the one genuine blocker in the current setup; every other piece (Tempo, Grafana) can already
be configured without this flag, but Service Graph won't actually populate until it's added.

### 12.3 Grafana — Link the Tempo Datasource to Prometheus

**File**: `docker/grafana/provisioning/datasources/tempo.yml`

```yaml
apiVersion: 1

datasources:
  - name: Tempo
    type: tempo
    uid: tempo
    url: http://tempo:3200
    access: proxy
    editable: false
    jsonData:
      serviceMap:
        datasourceUid: prometheus   # ← points at the existing Prometheus datasource's uid
```

`serviceMap.datasourceUid` tells Grafana's Tempo panel WHERE to query the service-graph metrics
that Tempo is now pushing into Prometheus — it must match the `uid` already set on the
Prometheus datasource (see `06-grafana-setup-guide.md` §4).

### 12.4 Verify

```bash
docker-compose up -d
# Generate traffic where Service A calls Service B over HTTP, on both traced services
```

Grafana → Explore → Tempo → **Service Graph** tab. A node should appear per service, with edges
showing request rate / error rate / latency between services that actually called each other.
A single-service graph (no edges) means either only one service is sending traces, or no
outbound HTTP calls between traced services have happened yet — not a config problem.

---

*Document maintained alongside codebase. Update whenever tracing config, the Tempo version, or
propagation requirements change.*
