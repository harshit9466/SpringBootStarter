# Production Observability Engineering — Setup Bundle
### Spring Boot 3.x | Maven + Gradle | MVC + Hexagonal

> **What this bundle is**: Production-grade observability implementation for any Java Spring Boot service.
> Follow the guides below to add structured logging, health probes, business metrics, Prometheus scraping,
> and JWT-secured Actuator endpoints to a new or existing project.

---

## Bundle Structure

```
docs/
├── README.md                          ← Start here
├── context_for_the_goal.md            ← Course philosophy (for AI teaching this course)
├── observability-library-plan.md      ← DEFERRED — shared starter library design doc, revisit after all modules done
├── object-storage-for-observability.md ← MinIO vs ODF deep-dive for reliable on-prem log/trace storage
├── course/
│   └── observability-course.md        ← Full 10-module theory course (for humans)
└── guides/                            ← Implementation guides (for AI + humans)
    ├── 01-logging-setup-guide.md
    ├── 02-actuator-setup-guide.md
    ├── 03-micrometer-setup-guide.md
    ├── 04-prometheus-setup-guide.md
    ├── 05-security-setup-guide.md
    ├── 06-grafana-setup-guide.md
    └── 07-tracing-setup-guide.md
```

---

## For AI Assistants — Implementing Observability

Read and follow these guides **in this exact order**. Each guide is self-contained.

| Step | File | What It Adds |
|------|------|-------------|
| 1 | `guides/01-logging-setup-guide.md` | SLF4J + Logback config, MDC request filter, JSON output for prod |
| 2 | `guides/02-actuator-setup-guide.md` | Health endpoints, Kubernetes probes, custom `DatabaseHealthIndicator` |
| 3 | `guides/03-micrometer-setup-guide.md` | Counters, Timers, Gauges, percentile config, high-cardinality rules |
| 4 | `guides/04-prometheus-setup-guide.md` | Docker Compose setup (Prometheus + Grafana), PromQL queries |
| 5 | `guides/05-security-setup-guide.md` | OAuth2 Resource Server (Keycloak JWT), `CurrentPrincipal`, `KeycloakJwtConverter` |
| 6 | `guides/06-grafana-setup-guide.md` | Provisioned datasource + RED-method dashboard, Grafana 11 gotchas |
| 7 | `guides/07-tracing-setup-guide.md` | Micrometer Tracing + OTLP → Grafana Tempo, MDC collision fix |

**Each guide includes:**
- Maven + Gradle dependency blocks
- Full production-ready code (no skeleton/placeholder code)
- Line-by-line config explanations
- Common mistakes section
- MVC vs Hexagonal placement rules

**Prerequisites before running guides:**
- Spring Boot 3.x project with `spring-boot-starter-web`
- Java 21
- Maven or Gradle build tool

---

## Ordering Constraint — Why This Sequence

```
01-logging    → MDC + Logback in place
     ↓
02-actuator   → /actuator/* endpoints exposed (Micrometer needs /actuator/prometheus)
     ↓
03-micrometer → Business metrics registered in MeterRegistry
     ↓
04-prometheus → Prometheus scrapes /actuator/prometheus (needs steps 2 + 3)
     ↓
05-security   → Secures /actuator/* endpoints (applied last)
     ↓
06-grafana    → Dashboards visualize what Prometheus already collected
     ↓
07-tracing    → Adds Tempo alongside Prometheus/Grafana; fixes the MDC traceId
                collision against step 1's MdcRequestFilter
```

Do **NOT** apply the security guide (step 5) before verifying steps 1–4 work without auth.
Debugging through auth headers while setting up the observability pipeline for the first time adds
unnecessary complexity — verify the full pipeline is working first, then lock it down.

Step 7 (tracing) must come after step 1 (logging) is actually in place, not just after step 6 —
it specifically modifies the `MdcRequestFilter` step 1 created. Applying tracing to a project
that skipped step 1 means there's no existing MDC filter to fix, so that part of the guide
doesn't apply.

---

## Architecture Adaptation Rules

When placing observability code in a new project:

| Concern | Standard Layered (MVC) | Hexagonal / Ports & Adapters |
|---------|------------------------|------------------------------|
| MDC Request Filter | `com.app.filter.MdcRequestFilter` | `com.app.adapter.in.web.filter.MdcRequestFilter` |
| Custom Health Indicators | `com.app.health.DatabaseHealthIndicator` | `com.app.adapter.out.persistence.health.DatabaseHealthIndicator` |
| Business Metrics (Counters/Timers) | Inside `@Service` implementations | Inside Application Services (use cases) |
| Infrastructure Timers | Inside `@Repository` or clients | Inside Outbound Adapters |
| Domain Entities | May have annotations | **Zero observability imports** — pure Java only |

---

## Quick Verification — After Applying All Guides

```bash
# 1. Structured JSON log output (run with prod profile)
cat logs/your-app.log | head -5 | python -m json.tool

# 2. Actuator health
curl http://localhost:8082/actuator/health

# 3. Kubernetes probes
curl http://localhost:8082/actuator/health/liveness
curl http://localhost:8082/actuator/health/readiness

# 4. Prometheus metrics endpoint
curl -s http://localhost:8082/actuator/prometheus | head -30

# 5. Prometheus UI (after docker-compose up -d, from guide 04)
# http://localhost:9090/targets  → spring-boot-starter should show UP

# 6. Grafana (same docker-compose up -d — datasource + dashboard are auto-provisioned
#    by guide 06's provisioning files, no manual "Add datasource" click needed)
# http://localhost:3000  → admin/admin → Dashboards → "Spring Boot — RED Dashboard"

# 7. Tracing (guide 07 — after adding the Tempo service and restarting the app)
curl http://localhost:8082/api/products
# then: http://localhost:3000 → Explore → Tempo → Search → spring-boot-starter
```

---

## For Humans — Learning the Theory

`course/observability-course.md` contains the full course anchored to a real enterprise scenario
(BiharOne Government Portal — Certificate Issuance, Payment, Document Verification).

| Module | Topic | Status |
|--------|-------|--------|
| 1 | Observability Fundamentals — Logs, Metrics, Traces, SLI/SLO/SLA | ✅ |
| 2 | Java Logging Architecture — SLF4J, Logback, MDC, Async | ✅ |
| 3 | Spring Boot Actuator — Internal architecture, Probes, Custom HealthIndicators | ✅ |
| 4 | Micrometer — MeterRegistry, Counters, Timers, Percentiles | ✅ |
| 5 | Prometheus — Pull model, TSDB, PromQL, High cardinality | ✅ |
| 6 | Grafana — Dashboards, RED method, Alerting | ✅ |
| 7 | Logging Infrastructure — Loki, Fluent Bit, Elasticsearch | ⏳ |
| 8 | Distributed Tracing — Micrometer Tracing, OTLP, Grafana Tempo | ✅ |
| 9 | Production Architecture — End-to-end observability stack | ⏳ |
| 10 | Production Readiness — PII masking, retention, compliance | ⏳ |

---

## For AI — Teaching This Course

To teach this observability course from scratch to a new learner:

1. Use `context_for_the_goal.md` as your system prompt — it defines the required depth,
   teaching style (senior architect mentoring a backend engineer), and realistic scenario anchoring.
2. Pair theory from `course/observability-course.md` with hands-on implementation from `guides/`.
3. Every concept should be anchored to the production scenario, not toy examples.
