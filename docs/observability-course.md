# Production Observability Engineering — Complete Course

> **Application Used Throughout**: BiharOne Citizen Services Platform
> A multi-service government portal handling Certificate Issuance, Payment Processing, Document Verification, and Citizen Authentication.

---

## Course Index

| Module | Topic | Status |
|--------|-------|--------|
| 1 | [Observability Fundamentals](#module-1--observability-fundamentals) | ✅ |
| 2 | Java Logging Architecture | 🔜 |
| 3 | Spring Boot Actuator | 🔜 |
| 4 | Micrometer | 🔜 |
| 5 | Prometheus | 🔜 |
| 6 | Grafana | 🔜 |
| 7 | Logging Infrastructure | 🔜 |
| 8 | Distributed Tracing | 🔜 |
| 9 | Production Architecture | 🔜 |
| 10 | Production Readiness | 🔜 |

---

## Production Application Context

**BiharOne Citizen Services Platform**

```
Citizen Browser / Mobile App
         │
    API Gateway (Kong / NGINX)
         │
    Keycloak (Auth)
         │
    ┌────┴────────────────────────────┐
    │                                 │
Certificate Service         Payment Service
(Spring Boot 3)             (Spring Boot 3)
    │                                 │
Document Verification       UPI Gateway Integration
Service                         │
    │                        Kafka Events
 PostgreSQL                      │
 (per service)              Notification Service
                                 │
                             PostgreSQL
```

Every concept in this course is anchored to a real incident or scenario from this system.

---

## Module 1 — Observability Fundamentals

### 1.1 The Production Reality — Why Monitoring Exists

**The Year 2000 Problem (not Y2K — the OTHER 2000 problem)**

In 2000, most enterprise software ran on a single server. When it broke, you walked up to the server room, opened a terminal, ran `tail -f server.log`, and saw the error. The developer who wrote the code was usually the same person running the server.

By 2010, things changed fundamentally:
- Applications deployed on 10–100 servers simultaneously
- Code written by one team, deployed by another, monitored by a third
- A crash at 3 AM affected thousands of users before anyone knew about it

**The first production horror story every engineer must understand:**

> Amazon had a 49-minute outage in 2013. They lost approximately $66,000 per minute. The root cause? A cascading failure that started with a single misconfigured deployment. Nobody knew which service caused it for over 30 minutes because they were looking at logs on 200 different servers manually.

That incident, and thousands like it, created the discipline of **Observability**.

---

### 1.2 The BiharOne Context

**Scenario: Bihar Certificate System Incident — 26 July 2024**

> **Incident**: Citizens filing Income Certificate applications are getting HTTP 500 errors. The District Magistrate's office is calling. No one knows why.

You, as the Backend Engineer, get a Slack message at 11:47 PM.

Without observability, here is what you face:
- 4 microservices potentially involved
- 12 running pods across 3 nodes in Kubernetes
- No idea which service is failing
- No idea if it's a DB connection issue, a third-party API timeout, or a code bug
- No idea how long it's been happening
- No idea how many citizens are affected

**This is what observability solves.**

---

### 1.3 Monitoring vs Observability — The Exact Distinction

Most engineers use these words interchangeably. They are **NOT** the same.

#### Monitoring

Monitoring is the practice of **watching known failure modes**.

You define in advance:
- "Alert me if CPU > 80%"
- "Alert me if HTTP error rate > 1%"
- "Alert me if response time > 2 seconds"

Monitoring answers: **"Is the system healthy?"**

It is like a car's dashboard. The temperature gauge tells you the engine is overheating. It does NOT tell you WHY.

**Limitation**: Monitoring only catches failures you anticipated. If you did not set up a rule for it, you will never know about it.

#### Observability

Observability is the ability to **understand the internal state of a system by examining its external outputs**, without needing to redeploy or modify the system.

The term comes from control theory (engineering mathematics), formalized by Rudolf Kálmán in 1960:
> "A system is observable if, for any possible evolution of state and control vectors, the current state can be determined in finite time using only the outputs."

Applied to software:
> A system is observable if you can determine WHAT happened, WHERE it happened, and WHY it happened, purely by looking at the data the system emitted — without SSH-ing into the server.

Observability answers: **"Why is the system behaving this way?"**

**The practical test**: When something breaks that you have NEVER seen before and never anticipated — can you still diagnose it? That is the test of observability.

#### Summary Table

| Dimension | Monitoring | Observability |
|-----------|-----------|---------------|
| Question | Is it broken? | Why is it broken? |
| Coverage | Known failure modes | Unknown failures too |
| Data Flow | You define what to watch | System emits rich data |
| Diagnosis | Detect + alert | Detect + diagnose + explain |
| Historical analogy | Car dashboard | Black box flight recorder |

---

### 1.4 The Three Pillars of Observability

This is now industry standard terminology, formalized by Charity Majors (Honeycomb) and Peter Bourgon (Weave Works) around 2017.

The three pillars are:

```
┌─────────────────────────────────────────────────────────┐
│                   OBSERVABILITY                          │
│                                                          │
│   ┌──────────┐    ┌──────────┐    ┌──────────────────┐  │
│   │  LOGS    │    │ METRICS  │    │     TRACES        │  │
│   │          │    │          │    │                   │  │
│   │ What     │    │ How      │    │ Where in the      │  │
│   │ happened │    │ much /   │    │ call chain did    │  │
│   │ in       │    │ how      │    │ it happen?        │  │
│   │ detail   │    │ often?   │    │                   │  │
│   └──────────┘    └──────────┘    └──────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

These three answer different questions during an incident. You need ALL THREE to investigate properly.

---

### 1.5 Logs — Deep Explanation

#### What is a Log?

A log is a **timestamped, immutable record of a discrete event that occurred inside a system**.

Key word: **discrete event**. A log captures something that happened at a specific moment.

```
2024-07-26T11:47:32.183Z  INFO  [certificate-service]
  [traceId=3f8a9c2d]  [citizenId=BR-2024-00481923]
  Certificate application submitted. Type=INCOME, District=PATNA,
  Processing queue depth=47, estimated_wait_seconds=120
```

This is a log. It records ONE event: a certificate application was submitted. Every field carries diagnostic value.

#### Log Levels — The Mental Model

| Level | When to use | BiharOne Example |
|-------|------------|-----------------|
| `TRACE` | Hyper-detailed internal state, only in dev | "Entering PDF rendering loop, iteration 3 of 12" |
| `DEBUG` | Developer diagnostic information | "Cache miss for citizen BR-2024-00481923, fetching from DB" |
| `INFO` | Normal business operations | "Income certificate issued. certId=CERT-2024-88421, citizenId=BR-2024-00481923" |
| `WARN` | Abnormal but handled situations | "Payment gateway timeout, retrying (attempt 2/3)" |
| `ERROR` | Failure that needs engineer attention | "Certificate generation failed. citizenId=BR-2024-00481923. DB connection lost." |
| `FATAL` | System cannot continue, shutdown imminent | (Rarely used in modern Spring Boot — JVM crash level) |

**The rule nobody teaches**: Log levels are a COMMUNICATION PROTOCOL between your application and the operations team. When you log at ERROR, you are telling the DevOps team: "Someone needs to look at this NOW." When you log at WARN, you are saying: "This is worth noting, but not urgent." Take this contract seriously.

#### What Should NEVER Be Logged

1. **Passwords, API keys, tokens** — Even masked versions are risky
2. **Full PII** — Aadhaar number, full bank account numbers, full phone numbers
3. **Health/medical data** — Legal liability under data protection law
4. **Credit card numbers** — PCI-DSS violation
5. **OTPs** — Security breach waiting to happen

(PII masking is covered in detail in Module 10.)

---

### 1.6 Metrics — Deep Explanation

#### What is a Metric?

A metric is a **numeric measurement of a system's state or behavior, collected at regular intervals over time**.

Metrics are **aggregated** — they summarize many events into a single number.

```
certificate_service_http_requests_total{
  method="POST",
  endpoint="/api/v1/certificates",
  status="500",
  district="PATNA"
} = 847
```

This tells you: Since the service started, there have been 847 HTTP 500 errors on the POST certificate endpoint for the PATNA district.

Logs told you what happened to ONE citizen. Metrics tell you the **scale** of the problem.

#### Four Core Metric Types

**1. Counter**
Monotonically increasing number. Only goes up. Reset on restart.
```
certificate_applications_submitted_total = 48291
certificate_applications_failed_total = 47
```
Use for: Request counts, error counts, events processed.

**2. Gauge**
Current snapshot of a value that can go up or down.
```
certificate_queue_depth = 47
jvm_memory_used_bytes = 512000000
active_citizen_sessions = 1241
```
Use for: Queue depths, memory usage, active connections.

**3. Timer / Histogram**
Measures duration of operations, with statistical distribution.
```
certificate_pdf_generation_seconds{quantile="0.5"}  = 0.342
certificate_pdf_generation_seconds{quantile="0.95"} = 1.891
certificate_pdf_generation_seconds{quantile="0.99"} = 4.721
```
This tells you: Half of PDF generations complete in 342ms. But 1% take over 4.7 seconds — your problematic cases that affect real citizens.

**4. Summary / Distribution**
Pre-computed percentiles over a sliding time window.

#### Why Metrics Instead of Just Counting Logs?

1. **Cost**: Log lines are text. Storing and searching 10 million log lines per hour is expensive. A metric is a number — tiny storage, fast query.
2. **Speed**: Querying metrics for "What was my p99 response time over the last 7 days?" takes milliseconds. Querying logs for the same takes minutes.
3. **Alerting**: You can set alerts on metrics trivially. Alerting on log patterns is complex and error-prone.
4. **Aggregation**: Metrics are already aggregated. "Total requests across all 12 pods" is one metric query. Getting this from logs requires aggregating across 12 log streams.

**Trade-off**: Metrics tell you WHAT is happening at scale. They lose the per-event detail. That is why you need both.

---

### 1.7 Traces — Deep Explanation

#### The Problem That Created Tracing

In a monolith, when a request fails, you know exactly which function failed because the call stack is in a single process.

In microservices:

```
Citizen Request
    → API Gateway (5ms)
        → Auth Service/Keycloak (45ms)
            → Certificate Service (2400ms) ← WHERE is the 2400ms going?
                → Document Verification Service (200ms)
                → Database Query 1 (50ms)
                → Database Query 2 (2100ms) ← AH. Slow DB query.
                → PDF Generator (50ms)
```

The citizen experienced 2450ms total. But WHERE was the time spent? Without tracing, you have NO WAY to know. Certificate Service logs show "processed in 2400ms" but do not break down where.

**Distributed Tracing** solves this by assigning a unique **Trace ID** to each incoming request and propagating it through every service call. Every operation becomes a **Span**, and all spans with the same Trace ID are connected into a **Trace**.

#### Trace Anatomy

```
TraceId: 3f8a9c2d-e1b4-4a2c-8f71-9d2e3c4b5a6f

Span 1: api-gateway          [==========] 0ms → 2450ms
Span 2:   keycloak-auth        [===]       5ms → 50ms
Span 3:   certificate-svc        [===============================] 50ms → 2450ms
Span 4:     doc-verify-svc         [==]     50ms → 250ms
Span 5:     db-query-1              [=]     250ms → 300ms
Span 6:     db-query-2              [========================] 300ms → 2400ms ← CULPRIT
Span 7:     pdf-generator           [=]   2400ms → 2450ms
```

The slow DB query is immediately visible. Without tracing, the Certificate Service would just report "I took 2400ms" and you would have no idea which internal operation was slow.

---

### 1.8 The Fourth Pillar — Events

An event is a **structured log record representing a complete, rich context snapshot at a specific moment** — richer than a traditional log line.

Traditional log:
```
INFO Certificate application submitted. citizenId=BR-2024-00481923
```

Event (structured, high-cardinality):
```json
{
  "timestamp": "2024-07-26T11:47:32.183Z",
  "service": "certificate-service",
  "action": "certificate.submitted",
  "citizen_id": "BR-2024-00481923",
  "district": "PATNA",
  "certificate_type": "INCOME",
  "application_channel": "MOBILE_APP",
  "device_os": "Android",
  "session_duration_seconds": 340,
  "queue_depth_at_submission": 47,
  "estimated_processing_seconds": 120,
  "trace_id": "3f8a9c2d",
  "span_id": "a1b2c3d4",
  "user_agent": "BiharOne-Android/2.3.1",
  "ip_country": "IN",
  "ip_state": "Bihar"
}
```

Events enable **high-cardinality queries** — "Show me all certificate submissions from Android users in PATNA where queue depth was over 40 and processing took more than 5 minutes." This kind of query is impossible with traditional metrics (cardinality explosion) and expensive with traditional logs (full text search).

---

### 1.9 SRE Basics — The Framework That Defines "Good Enough"

#### What is SRE?

Site Reliability Engineering (SRE) was invented at Google around 2003, formalized in the book "Site Reliability Engineering" (2016).

The core insight:
> You cannot make a system 100% reliable. Trying to do so costs more than the value it provides. Instead, define what "reliable enough" means — and spend your effort elsewhere.

#### The Three Core SRE Concepts

**SLI — Service Level Indicator**

A metric that MEASURES the reliability of a service from the user's perspective.

```
SLI = (Number of good requests) / (Total requests) × 100

For BiharOne Certificate Service:
SLI = (HTTP 2xx responses) / (Total HTTP responses) × 100
```

Other common SLIs:
- Availability: % of time the service is up
- Latency: % of requests completed under Xms
- Error rate: % of requests that fail
- Throughput: Requests processed per second

**SLO — Service Level Objective**

An INTERNAL target for the SLI. This is your team's promise to itself.

```
Certificate Service SLOs:
- Availability SLO : 99.5% uptime per month
                     = allowed downtime: ~3.6 hours/month

- Latency SLO      : 95% of certificate submissions respond in < 2 seconds

- Error Rate SLO   : < 0.1% HTTP 5xx rate, measured weekly
```

SLOs define your **error budget**:

```
Error Budget = 100% - SLO

If SLO = 99.5% availability:
Error Budget = 0.5% per month = ~3.6 hours of allowed downtime
```

If you burn through your error budget, you stop deploying new features and focus entirely on reliability.

**SLA — Service Level Agreement**

An EXTERNAL contract with a customer or stakeholder, with penalties if violated.

```
BiharOne SLA with Bihar Government:
- Certificate issuance portal uptime : 99% per quarter
- Penalty                            : Department head review if violated
- Measured                           : 24×7, excluding scheduled maintenance windows
```

SLAs are typically weaker than internal SLOs intentionally:
- SLO: 99.5% (internal target)
- SLA: 99.0% (external commitment)

The gap between SLO and SLA is your **safety margin**. If you consistently meet SLO 99.5%, you will never breach SLA 99%.

#### Why This Matters to You as a Backend Developer

1. Your code generates the SLI data — bad instrumentation = inaccurate SLIs.
2. You choose what metrics to instrument.
3. During an incident, you will be asked "Are we breaching SLO?" — you need to know what that means.
4. Feature work gets paused when error budget is exhausted — your deployment schedule depends on this.

---

### 1.10 The Production Incident Lifecycle

This is the most important section of Module 1. Everything in the rest of this course exists to serve this lifecycle.

```
Detection → Triage → Diagnosis → Mitigation → Resolution → Post-Mortem
```

#### Phase 1: Detection (< 5 minutes target)

- **Best case**: Automated alert fires before users notice.
- **Common case**: Citizen complaint → support desk → Jira → escalated to dev.
- **Worst case**: District Magistrate calls the department head directly.

Good observability means automated detection, ideally before users are impacted.

#### Phase 2: Triage (5–15 minutes)

What is the scope? Questions answered by **METRICS**:
- What is the current error rate? (Was 0.1%, now 23%)
- Which service? (Certificate Service)
- Which endpoint? (POST /api/v1/certificates)
- When did it start? (11:43 PM — 4 minutes ago)
- How many citizens affected? (~340)
- Is it getting worse or stabilizing?

#### Phase 3: Diagnosis (15–60 minutes)

WHY is it failing? Questions answered by **LOGS and TRACES**:
- What exact error is occurring?
- Which downstream service?
- Is it a DB issue, a third-party API, a code bug?
- Did anything change recently (deployment, config change)?

#### Phase 4: Mitigation

Stop the bleeding. Examples:
- Rollback the last deployment
- Feature flag off the broken feature
- Scale up the broken service
- Temporarily bypass the failing downstream dependency

#### Phase 5: Resolution

Root cause is fixed. Service is stable. SLOs recovering.

#### Phase 6: Post-Mortem (24–48 hours later)

Blameless analysis of what happened, why it was not caught earlier, and how to prevent it.

```
Incident Post-Mortem: Certificate Service Outage — 26 July 2024

Timeline:
  11:43 PM — First errors begin (undetected)
  11:47 PM — Citizen complaints start reaching support
  11:52 PM — Alert fires: HTTP 500 rate > 5%
  11:53 PM — On-call engineer paged
  11:58 PM — Root cause identified: Missing font file in Docker image
  12:02 AM — Mitigation: Rolled back to previous Docker image
  12:07 AM — Service restored

Root Cause:
  A Docker build script silently failed to copy /opt/fonts/ because the
  font directory was moved in a refactor but the Dockerfile was not updated.
  Build succeeded, tests passed (tests did not cover PDF generation with
  Devanagari font). First deployment to production exposed the bug.

Contributing Factors:
  1. No test for PDF generation with actual font loading
  2. Alert threshold for 500 errors was 5% — should have been 1%
  3. Alert took 9 minutes to fire — should have been < 2 minutes

Action Items:
  1. Add integration test for PDF generation   (Owner: Dev,    Due: 2 Aug)
  2. Reduce 500 error alert threshold 5% → 1%  (Owner: DevOps, Due: 28 Jul)
  3. Add alert for alert-to-page latency > 3m  (Owner: DevOps, Due: 28 Jul)
  4. Add Dockerfile validation step in CI      (Owner: Dev,    Due: 30 Jul)
```

---

### 1.11 Module 1 Summary — Mental Model

```
OBSERVABILITY = LOGS + METRICS + TRACES + (EVENTS)

During an Incident:

METRICS  → "Something is wrong with Certificate Service.
             500 error rate jumped to 23% at 11:43 PM."

LOGS     → "The exact error is: Missing font file DevaNagari.ttf.
             Happening for all certificate PDF generation requests."

TRACES   → "The failure happens in certificate-service → pdf-generator
             at span: renderPdfDocument. Takes 0ms then throws exception.
             All other services are healthy."

SLO      → "We have breached our 99.5% availability SLO.
             Error budget for this month is now exhausted.
             All feature deployments must stop until we recover."
```

---

## Module 2 — Java Logging Architecture

> _Content to be added_

Topics:
- Why `System.out.println()` nearly destroyed production applications
- The evolution: JUL → Log4j 1 → Commons Logging → SLF4J → Logback → Log4j2
- Spring Boot's logging auto-configuration internals
- How Logback initializes during Spring Boot startup (every class involved)
- Appenders, Encoders, Layouts — full internal architecture
- MDC — why it is critical for distributed systems
- Async logging — why synchronous logging causes latency spikes in production
- Structured JSON logging — the format your DevOps team actually reads
- Correlation IDs, Request IDs, Trace IDs — how they connect logs to traces

---

## Module 3 — Spring Boot Actuator

> _Content to be added_

---

## Module 4 — Micrometer

> _Content to be added_

---

## Module 5 — Prometheus

> _Content to be added_

---

## Module 6 — Grafana

> _Content to be added_

---

## Module 7 — Logging Infrastructure

> _Content to be added_

---

## Module 8 — Distributed Tracing

> _Content to be added_

---

## Module 9 — Production Architecture

> _Content to be added_

---

## Module 10 — Production Readiness

> _Content to be added_
