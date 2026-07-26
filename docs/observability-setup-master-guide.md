# Reusable Production Observability Master Guide
### A Step-by-Step Blueprint for Java Spring Boot 3.x Applications

> **What this document is**: The central entry point for replicating production-grade observability
> across any Java Spring Boot application (Maven/Gradle, Layered MVC/Hexagonal).
>
> Instead of monolithic, hard-to-maintain documentation, this guide follows a **modular architecture**.
> Each component of the observability triad (Logs, Metrics, Probes) is detailed in its own dedicated guide with
> line-by-line code explanations, failure analyses ("what breaks if removed"), and architecture adaptation rules.

---

## The Observability Implementation Roadmap

When onboarding a new service or auditing an existing application for production readiness, implement these three pillars in sequential order:

```
Step 1: Logging Architecture (SLF4J, Logback, MDC, JSON Output)
     │
     ▼
Step 2: Actuator & Probes (Health Indicators, Kubernetes Liveness/Readiness, Security)
     │
     ▼
Step 3: Metrics & Telemetry (Micrometer, Prometheus, Timers, Percentiles, PromQL)
```

---

## Pillar 1: Production Logging Architecture
**Guide File**: [logging-setup-guide.md](file:///D:/Projects/SpringBootStarter/docs/logging-setup-guide.md)

### What it covers:
* **The `System.out.println` Trap**: Why synchronous logging causes lock contention and latency spikes under load.
* **SLF4J & Logback Internals**: Compile-time binding, logger hierarchy, and auto-configuration sequence.
* **MDC (Mapped Diagnostic Context)**: How `MdcRequestFilter` attaches `traceId`, `requestId`, and `userId` to thread-local storage for distributed tracing across multi-pod microservices.
* **Environment-Specific Profiles**: Human-readable ANSI colored logs for `dev` vs. asynchronous structured JSON output (`logstash-logback-encoder`) for `prod` (Loki/Elasticsearch ingestion).
* **Zero-Allocation Parameterized Logging**: Why `log.info("msg {}", val)` prevents GC pressure compared to string concatenation.

---

## Pillar 2: Spring Boot Actuator & Probes
**Guide File**: [actuator-setup-guide.md](file:///D:/Projects/SpringBootStarter/docs/actuator-setup-guide.md)

### What it covers:
* **The X-Ray Engine**: Auto-discovery of `@Endpoint` beans and secure endpoint exposure rules (`when-authorized` vs `never`).
* **SLA-Aware Custom Health Checks**: Writing a `DatabaseHealthIndicator` that measures execution latency (`SELECT 1`) and reports warning states (`"status": "SLOW"`) without triggering pod evictions.
* **Kubernetes Probes Integration**:
  * **Liveness (`/actuator/health/liveness`)**: Verifies JVM responsiveness. Why you must **never** check DB connectivity here (to avoid crash loops).
  * **Readiness (`/actuator/health/readiness`)**: Verifies traffic acceptance capability. Why dependency failures belong here (to temporarily remove pod IP from load balancer endpoints).
* **Live Log Level Mutation**: How senior SREs dynamically change package log levels from `INFO` to `DEBUG` at runtime via HTTP POST to `/actuator/loggers` without restarting pods or dropping user sessions.

---

## Pillar 3: Micrometer & Prometheus Telemetry
**Guide File**: [micrometer-setup-guide.md](file:///D:/Projects/SpringBootStarter/docs/micrometer-setup-guide.md)

### What it covers:
* **The Metrics Facade**: Decoupling instrumentation code from backend time-series databases (Prometheus, Datadog, CloudWatch).
* **Core Meter Types in Practice**:
  * **Counters**: Monotonically increasing event tracking (`products.created.total`, business error counters).
  * **Timers**: Latency and rate tracking (`product.operation.duration`).
  * **Gauges**: Current snapshot measurements (connection pools, queue depth).
* **Percentiles & Tail Latency**: Enforcing client-side `p50`, `p95`, and `p99` computation (`publishPercentiles(0.5, 0.95, 0.99)`). Why average latency hides 1% outages.
* **The High Cardinality Trap**: Critical production rules against tagging metrics with unique IDs (`userId`, `UUID`, timestamps) to prevent JVM `OutOfMemoryError` and Prometheus database crashes.
* **PromQL Integration**: Essential queries for calculating rates, error ratios, and 95th percentile latency in Grafana dashboards.

---

## Architecture Adaptation Matrix

When replicating these guides into a new repository, follow these structural rules based on the target project architecture:

| Observability Concern | Standard Layered (MVC) | Hexagonal / Ports & Adapters |
| :--- | :--- | :--- |
| **MDC Request Filter** | `com.app.filter.MdcRequestFilter` | `com.app.adapter.in.web.filter.MdcRequestFilter` (Inbound Web Adapter concern) |
| **Custom Health Indicators** | `com.app.health.DatabaseHealthIndicator` | `com.app.adapter.out.persistence.health.DatabaseHealthIndicator` (Outbound Infrastructure concern) |
| **Business Metrics (Counters/Timers)** | Inside `@Service` implementations | Inside **Application Services / Use Cases** (The true business orchestration boundary) |
| **Infrastructure Timers** | Inside `@Repository` or external clients | Inside **Outbound Adapters** (Persistence, REST client wrappers) |
| **Domain Entities / Records** | May contain basic annotations | **ZERO OBSERVABILITY DEPENDENCIES**. Keep pure Java (no SLF4J, no Micrometer, no Spring). |

---

## Quick-Start Verification Script

After applying all three setup guides to a new service, run these verification commands:

```bash
# 1. Verify JSON Log output (when running with prod profile)
cat logs/your-app-name.log | head -n 5 | python -m json.tool

# 2. Verify Actuator Health & Custom Indicator Latency
curl -i http://localhost:8082/actuator/health

# 3. Verify Kubernetes Probes
curl -i http://localhost:8082/actuator/health/liveness
curl -i http://localhost:8082/actuator/health/readiness

# 4. Verify Prometheus Exposition Format & Custom Business Metrics
curl -s http://localhost:8082/actuator/prometheus | grep "products_"
```

---

*Master Guide maintained alongside modular technical documentation.*
