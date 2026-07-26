# Production Observability Engineering — Complete Course

> **Application Used Throughout**: BiharOne Citizen Services Platform
> A multi-service government portal handling Certificate Issuance, Payment Processing, Document Verification, and Citizen Authentication.

---

## Course Index

| Module | Topic | Status |
|--------|-------|--------|
| 1 | [Observability Fundamentals](#module-1--observability-fundamentals) | ✅ |
| 2 | [Java Logging Architecture](#module-2--java-logging-architecture) | ✅ |
| 3 | [Spring Boot Actuator](#module-3--spring-boot-actuator) | ✅ |
| 4 | [Micrometer](#module-4--micrometer) | ✅ |
| 5 | [Prometheus](#module-5--prometheus) | ✅ |
| 6 | [Grafana](#module-6--grafana) | ⏳ |
| 7 | [Logging Infrastructure](#module-7--logging-infrastructure) | ⏳ |
| 8 | [Distributed Tracing](#module-8--distributed-tracing) | ⏳ |
| 9 | [Production Architecture](#module-9--production-architecture) | ⏳ |
| 10 | [Production Readiness](#module-10--production-readiness) | ⏳ |

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

### 2.1 Why System.out.println() Nearly Destroyed Production Applications

Before any logging framework existed, Java developers wrote this:

```java
System.out.println("Certificate application received: " + citizenId);
System.err.println("ERROR: DB connection failed");
```

This seems harmless. In production, it is a disaster waiting to happen.

#### Problem 1 — Synchronous I/O on Every Thread

`System.out` is a `PrintStream`. Every `.println()` call is a **synchronized, blocking write to stdout**. This means:

- Thread A calls `println()` → acquires lock on PrintStream → writes → releases lock
- Thread B is waiting the entire time

In a Spring Boot service handling 500 concurrent certificate requests, this creates a **lock contention bottleneck**. Every thread that logs blocks every other thread that logs. Your throughput drops. Response times spike. This is not theoretical — this has taken down production services.

#### Problem 2 — No Log Levels

`System.out` has no concept of INFO vs DEBUG vs ERROR. You cannot say "in production, only show me ERRORs." Everything prints, always. You either drown in noise or comment out debug lines before deploying (and forget to put them back).

#### Problem 3 — No Destinations

Where does `System.out` go? Wherever the JVM's stdout goes. In a container (Docker/Kubernetes) that might be captured. In a legacy server deployment it might go to `/dev/null`. You have zero control.

#### Problem 4 — No Context

```java
System.out.println("ERROR: DB connection failed");
```

Which request? Which user? Which thread? Which time exactly? You have no idea. In production with 500 concurrent requests, this log line is completely useless for diagnosis.

#### Problem 5 — No Structured Output

Log aggregation tools (Elasticsearch, Loki) work best with structured data — JSON, key=value pairs. `System.out.println` produces unstructured strings that are expensive to parse and query.

**The BiharOne Production Consequence:**

Imagine this running on 12 pods, 500 RPS:
```java
System.out.println("Processing certificate for: " + citizenId + " type: " + certType);
```
- 500 threads × 12 pods = 6000 concurrent `println` calls/second
- Each call acquires a JVM-level lock
- Lock contention degrades throughput by 40–60% under load (measured in real benchmarks)
- All output is plain text — DevOps cannot grep for citizenId across 12 pods in real time

**This is why logging frameworks exist.**

---

### 2.2 The Evolution of Java Logging — Why Each Step Was Necessary

#### Era 1: java.util.logging (JUL) — Java 1.4, 2002

Sun Microsystems added a built-in logging API to Java 1.4. It solved the `System.out` problem superficially:

```java
Logger logger = Logger.getLogger(CertificateService.class.getName());
logger.info("Certificate application received");
logger.severe("DB connection failed");
```

**What it solved**: Log levels, configurable handlers (file, console), basic formatting.

**Why it was not enough**:
- Configuration was XML-based and painful
- Performance was poor (string concatenation happened even if the log level was disabled)
- No MDC (Mapped Diagnostic Context) support
- Handler API was rigid — adding a custom output destination was complex
- Large enterprises had already invested in Log4j by the time JUL arrived

#### Era 2: Log4j 1.x — 1999 (before JUL)

Apache Log4j was actually written BEFORE JUL, by Ceki Gülcü. It became the industry standard.

```java
Logger logger = Logger.getLogger(CertificateService.class);
logger.info("Certificate application received");
```

**What it introduced**:
- Logger hierarchy (com.biharone.certificate inherits from com.biharone)
- Appenders (where to write: file, console, socket, database)
- Layouts (how to format: pattern, XML, HTML)
- MDC — `MDC.put("citizenId", "BR-2024-00481923")` — groundbreaking
- Rolling file appenders (rotate daily, by size)

**Why it was not enough**:
- Log4j 1.x was abandoned in 2015 (security issues, no active development)
- Configuration was global and static — hard to test
- No asynchronous logging built-in
- The infamous Log4Shell vulnerability (CVE-2021-44228) was in Log4j2 but poisoned Log4j's reputation

#### Era 3: Apache Commons Logging (JCL) — 2002

As JUL and Log4j both existed, a new problem emerged: **library authors did not know which logging framework their users would use.**

Apache Commons Logging (JCL) was created as a **facade** — a thin abstraction layer that detected which logging framework was on the classpath at runtime and delegated to it.

```java
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

Log log = LogFactory.getLog(CertificateService.class);
log.info("Certificate issued");
```

**Why it was not enough**:
- Runtime class discovery (`LogFactory`) was implemented with complex classloader logic
- This caused `ClassCastException` and `NoClassDefFoundError` in OSGi and application server environments constantly
- The runtime discovery was fragile and hard to debug
- Known in the Java community as "classloader hell"

#### Era 4: SLF4J — 2005 (The Standard That Won)

Ceki Gülcü (the original Log4j author, frustrated with JCL) created **SLF4J** — Simple Logging Facade for Java.

Key insight: instead of runtime discovery, use **compile-time binding** via a static binder.

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

Logger log = LoggerFactory.getLogger(CertificateService.class);
log.info("Certificate issued. certId={}, citizenId={}", certId, citizenId);
```

**What SLF4J solved**:

1. **No classloader hell** — binding happens at compile time via a single JAR on the classpath (`slf4j-logback-classic` OR `slf4j-log4j2` etc.)
2. **Parameterized logging** — `log.info("Processing {}", citizenId)` — the string is NOT concatenated unless that log level is active. Huge performance win.
3. **Stable API** — library authors write to SLF4J API, end users choose the implementation (Logback, Log4j2, JUL)
4. **Bridging** — `log4j-over-slf4j.jar` silently redirects Log4j calls to SLF4J. Legacy code unchanged, output goes to your chosen backend.

**SLF4J is an interface. It has no logging implementation of its own.**

```
Your Code → SLF4J API → [binding JAR] → Logback / Log4j2 / JUL
```

This is why Spring Boot (and every major Java framework) uses SLF4J.

#### Era 5: Logback — 2006 (Spring Boot's Default)

Also by Ceki Gülcü. Logback was written as the **native implementation of SLF4J** — it implements SLF4J's interfaces directly, with no translation layer.

Spring Boot chose Logback as its default because:
- It is the fastest logging framework in Java for synchronous logging
- It has the most mature rolling policy implementation
- Spring Boot team (Pivotal) had deep familiarity with it
- Native SLF4J implementation = zero bridge overhead

#### Era 6: Log4j2 — 2014 (The High-Performance Alternative)

Log4j2 (completely rewritten, not an upgrade of 1.x) introduced **asynchronous logging by default** using the LMAX Disruptor (a lock-free ring buffer).

```
Throughput comparison (from Log4j2 benchmarks):
Logback sync:    ~166,000 messages/second
Log4j2 async:   ~18,000,000 messages/second
```

For extremely high-throughput systems (trading platforms, telemetry pipelines), Log4j2 async is the choice. For most enterprise Spring Boot applications, Logback is sufficient and simpler.

---

### 2.3 SLF4J Architecture — What Happens Internally

When you write:

```java
private static final Logger log = LoggerFactory.getLogger(CertificateService.class);
log.info("Certificate issued. certId={}", certId);
```

Here is what happens at the JVM level:

```
LoggerFactory.getLogger(CertificateService.class)
    ↓
StaticLoggerBinder.getSingleton()          ← compile-time bound class
    ↓
LoggerContext (Logback's implementation)   ← root LoggerContext
    ↓
Returns: ch.qos.logback.classic.Logger     ← implements org.slf4j.Logger
    ↓
log.info("Certificate issued. certId={}", certId)
    ↓
Logger checks: is INFO level enabled?      ← fast int comparison
    ↓ (yes)
LoggingEvent created                       ← timestamp, level, message, MDC snapshot, caller info
    ↓
Logger walks up the hierarchy to root      ← inheritance chain
    ↓
Each Appender in the chain is called       ← ConsoleAppender, FileAppender, etc.
    ↓
Encoder formats the LoggingEvent           ← applies pattern or JSON layout
    ↓
Bytes written to OutputStream              ← console, file, socket
```

**Why parameterized logging matters**:

```java
// BAD — string concatenated even if DEBUG is OFF
log.debug("Processing cert for: " + citizenId + " in district: " + district);

// GOOD — {} replaced only if DEBUG is ON
log.debug("Processing cert for: {} in district: {}", citizenId, district);
```

In production with DEBUG disabled, the bad version still allocates a String object on every call. At 10,000 RPS this is millions of wasted allocations per second → GC pressure → latency spikes.

---

### 2.4 Logger Hierarchy — The Most Misunderstood Concept

Logback loggers form a **hierarchy based on package names**, with `ROOT` at the top.

```
ROOT
 └── com
      └── biharone
           ├── certificate
           │    └── CertificateService          ← Logger("c.b.certificate.CertificateService")
           │    └── CertificatePdfGenerator     ← Logger("c.b.certificate.CertificatePdfGenerator")
           └── payment
                └── PaymentService              ← Logger("c.b.payment.PaymentService")
```

Rules:
1. A logger **inherits** its level from its parent if not explicitly set
2. A logger **inherits** appenders from its parent (additive=true by default)
3. Setting `com.biharone.certificate` to DEBUG enables DEBUG for ALL classes in that package

**Production use case:**

```xml
<!-- logback-spring.xml -->
<root level="INFO">
    <appender-ref ref="CONSOLE"/>
</root>

<!-- Temporarily enable DEBUG for one package during incident investigation -->
<logger name="com.biharone.certificate" level="DEBUG" additivity="false">
    <appender-ref ref="FILE"/>
</logger>
```

`additivity="false"` means: don't also send to ROOT's appenders. Only use FILE.

---

### 2.5 Spring Boot Logging Auto-Configuration Internals

When Spring Boot starts, this is the exact sequence:

**Step 1: JVM starts, before Spring context**

`LoggingApplicationListener` is registered as a Spring `ApplicationListener`. It fires on `ApplicationStartingEvent` — before beans are created, before properties are loaded.

**Step 2: LoggingSystem detection**

`LoggingSystem.get(ClassLoader)` scans the classpath:
- Is `ch.qos.logback.core.Appender` present? → Use `LogbackLoggingSystem`
- Is `org.apache.logging.log4j.core.LoggerContext` present? → Use `Log4J2LoggingSystem`
- Fallback → `JavaLoggingSystem` (JUL)

Since `spring-boot-starter` includes `logback-classic`, Spring Boot always selects `LogbackLoggingSystem`.

**Step 3: Configuration file search**

`LogbackLoggingSystem` looks for configuration in this exact order:
1. `logback-test.xml` (classpath)
2. `logback.xml` (classpath)
3. `logback-spring.xml` (classpath) ← **use this one — Spring processes it**
4. Built-in `base.xml` default configuration

**Why `logback-spring.xml` over `logback.xml`?**

`logback.xml` is loaded by Logback directly, before Spring. This means `<springProfile>` tags and `${spring.application.name}` property substitution do NOT work.

`logback-spring.xml` is loaded by Spring's `LogbackLoggingSystem`, which means:
- `<springProfile name="dev">` works
- `${spring.application.name}` resolves correctly
- Spring's property placeholder system is available

**Step 4: Property binding**

After Spring's `Environment` is ready, `LoggingApplicationListener` applies `application.properties` logging configuration:

```properties
logging.level.root=INFO
logging.level.com.biharone.certificate=DEBUG
logging.file.name=logs/application.log
logging.pattern.console=%d{HH:mm:ss} %-5level %logger{36} - %msg%n
```

These override the `logback-spring.xml` settings.

---

### 2.6 MDC — The Most Critical Logging Concept for Distributed Systems

#### What is MDC?

MDC stands for **Mapped Diagnostic Context**. It is a `ThreadLocal<Map<String, String>>` — a map of key-value pairs attached to the current thread, automatically included in every log line from that thread.

**Without MDC:**
```
INFO  CertificateService - Certificate application received
INFO  DocumentService    - Document validation started
INFO  CertificateService - Certificate issued
ERROR CertificateService - PDF generation failed
INFO  CertificateService - Certificate application received
INFO  CertificateService - Certificate issued
```

Which ERROR belongs to which request? Impossible to tell with 500 concurrent requests.

**With MDC:**
```
INFO  [traceId=abc123] [citizenId=BR-481923] [district=PATNA]  CertificateService - Certificate application received
INFO  [traceId=abc123] [citizenId=BR-481923] [district=PATNA]  DocumentService    - Document validation started
ERROR [traceId=abc123] [citizenId=BR-481923] [district=PATNA]  CertificateService - PDF generation failed
INFO  [traceId=def456] [citizenId=BR-992341] [district=GAYA]   CertificateService - Certificate application received
```

Now you can filter ALL logs for `traceId=abc123` across ALL services and see the exact journey of that one request — across 12 pods, 4 microservices.

#### How MDC works internally

```java
// At start of request (in a Filter or Interceptor):
MDC.put("traceId",   UUID.randomUUID().toString());
MDC.put("citizenId", request.getHeader("X-Citizen-Id"));
MDC.put("district",  extractDistrict(request));

// Inside CertificateService — no MDC code needed:
log.info("Certificate application received");
// Output automatically includes traceId, citizenId, district from MDC

// At end of request — MUST clear MDC (thread pool reuse!):
MDC.clear();
```

`MDC.put()` writes to a `ThreadLocal` map. The Logback encoder reads this map for every log event on that thread. No explicit passing of context between method calls is needed.

**Critical mistake**: If you do NOT call `MDC.clear()` at the end of a request, the thread returns to the pool with stale MDC values. The NEXT request on that thread will log with the PREVIOUS request's citizenId. This creates false correlation in production logs — one of the hardest bugs to diagnose.

---

### 2.7 Async Logging — Why Synchronous Logging Causes Latency Spikes

#### The synchronous logging problem

Default Logback configuration is **synchronous**: every `log.info()` call blocks the application thread until the log is written to disk or console.

```
Application Thread:
  [process request] → [log.info()] → [wait for disk I/O] → [log.info()] → [wait] → [send response]
```

Disk I/O is slow. An SSD write can take 0.1–1ms. With 50 log statements per request at 1000 RPS:
- 50 log writes × 0.5ms average = 25ms of pure I/O wait per request
- This directly adds to your p99 response time

During incidents (when you want verbose logging most), your logging slows down your service further — exactly when you need it to be fast.

#### Async Logging Solution

Logback's `AsyncAppender` decouples the application thread from the I/O:

```
Application Thread:                    Background I/O Thread:
  [process request]                      [reads from queue]
  [log.info()] → puts event in queue →   [writes to disk]
  [continues immediately]
```

The application thread puts the log event into a **blocking queue** and returns instantly. A dedicated background thread drains the queue and writes to disk.

```xml
<appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>2048</queueSize>
    <discardingThreshold>0</discardingThreshold>
    <appender-ref ref="FILE"/>
</appender>
```

**The hidden danger**: `discardingThreshold` defaults to 20 (%). When the queue is 80% full, Logback **silently drops** TRACE, DEBUG, INFO events. In an incident, your most detailed logs vanish exactly when you need them. Set `discardingThreshold=0` in production.

---

### 2.8 Structured JSON Logging — What DevOps Actually Reads

Plain text logs:
```
2024-07-26 11:47:32 ERROR CertificateService - PDF generation failed for citizen BR-481923
```

This is human-readable but machine-unfriendly. Loki, Elasticsearch, Splunk all work better with structured data.

JSON logs:
```json
{
  "timestamp": "2024-07-26T11:47:32.183+05:30",
  "level": "ERROR",
  "logger": "c.b.certificate.CertificateService",
  "message": "PDF generation failed",
  "citizenId": "BR-481923",
  "district": "PATNA",
  "certType": "INCOME",
  "traceId": "3f8a9c2d",
  "spanId": "a1b2c3d4",
  "service": "certificate-service",
  "exception": "com.itextpdf.kernel.exceptions.KernelException: /opt/fonts/DevaNagari.ttf (No such file or directory)"
}
```

Now DevOps can query:
- `level=ERROR AND district=PATNA` → all PATNA errors
- `citizenId=BR-481923` → exact citizen's journey
- `service=certificate-service AND level=ERROR` → all certificate errors
- `traceId=3f8a9c2d` → full request trace across all services

This is done with `logstash-logback-encoder` library in Spring Boot.

---

### 2.9 Module 2 — Practical Implementation (BiharOne Certificate Service)

> See code files added to this project under `src/main/`

**What we implemented:**
1. `logback-spring.xml` — full production Logback configuration with console (dev) and JSON file (prod) appenders
2. `MdcRequestFilter.java` — Servlet filter that populates MDC at request start and clears at request end
3. Updated `application-dev.properties` — logging levels per package
4. Example usage in service layer with proper log levels and MDC context

---

## Module 3 — Spring Boot Actuator

> See implementation: `src/main/java/com/SpringBootStarter/health/DatabaseHealthIndicator.java`

### 3.1 Why Actuator Exists

Pehle ek sawal: **Tumhari app chal rahi hai — andar se kya ho raha hai, kaise pata karoge?**

Bina Actuator ke:
- SSH karo server pe → manually `ps`, `free -m`, `df -h` run karo
- Har team apne alag diagnostic endpoints likhti thi — koi standard nahi
- DB connected hai ya nahi? Pata nahi jab tak app crash na kare

**Actuator (2014) ne ek standardized, secure, production-ready HTTP endpoint set diya** — bina koi extra code likhe. Bas dependency add karo, Spring baaki sab handle karta hai.

> 💡 **Analogy**: Car ka OBD-II port. Koi bhi diagnostic tool lagao — engine state, fuel, errors sab mil jaata hai. Engine ka code touch nahi karna.

---

### 3.2 Internal Architecture

```
spring-boot-starter-actuator adds:
                    │
      ActuatorAutoConfiguration
                    │
      ┌─────────────┴──────────────┐
      │                            │
  EndpointAutoConfiguration    HealthEndpointAutoConfiguration
  Scans @Endpoint beans        Aggregates all HealthIndicator beans
      │                            │
  WebMvcEndpointHandlerMapping  CompositeHealthContributor
  Registers /actuator/* URLs    Runs each indicator, aggregates status
```

Startup pe Spring automatically:
1. Sab `HealthIndicator` beans dhundta hai (tumhara custom bhi)
2. `/actuator/health` pe register karta hai
3. Aggregate status calculate karta hai — ek bhi DOWN → sab DOWN

---

### 3.3 Key Endpoints

| Endpoint | Kya karta hai | Production use |
|----------|--------------|----------------|
| `/actuator/health` | App + dependencies ka UP/DOWN | Kubernetes probes, load balancer |
| `/actuator/health/liveness` | JVM alive hai? | K8s liveness probe |
| `/actuator/health/readiness` | Traffic accept karne ready? | K8s readiness probe |
| `/actuator/metrics` | JVM, HTTP, DB metrics | Prometheus scraping |
| `/actuator/info` | App version, build info | Deployment verification |
| `/actuator/loggers/{name}` | Runtime log level change | **Bina restart DEBUG on karo** |
| `/actuator/threaddump` | JVM thread state | Deadlock diagnosis |
| `/actuator/env` | All properties (sanitized) | Config debugging |

---

### 3.4 Liveness vs Readiness — Kubernetes Ka Critical Concept

```
/actuator/health/liveness   → JVM alive hai?
/actuator/health/readiness  → Traffic lene ready hai?
```

**Kyun do alag probes?**

Startup scenario:
```
t=0s  → Pod starts, JVM starts
t=1s  → Liveness: CORRECT    (JVM alive, restart mat karo)
        Readiness: REFUSING_TRAFFIC  (abhi ready nahi, traffic mat bhejo)
t=15s → Spring context load complete, DB connected
        Readiness: ACCEPTING_TRAFFIC (ab traffic bhejo)
```

Agar sirf ek probe hoti aur woh startup mein DOWN return karti:
→ Kubernetes restart karta → naya pod bhi 15s lagata → restart loop → kabhi start nahi hoti

**Mid-operation DB failure:**
```
t=600s → DB goes down
  Readiness: REFUSING_TRAFFIC  (naye requests mat bhejo)
  Liveness:  CORRECT            (JVM theek hai, restart mat karo)
  → Kubernetes traffic rok deta, pod alive rehta
  → DB wapas aaya → Readiness automatically ACCEPTING_TRAFFIC
```

---

### 3.5 Health Response Structure

`GET /actuator/health` (with `show-details=always`):

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": { "database": "PostgreSQL", "validationQuery": "isValid()" }
    },
    "databaseHealthIndicator": {
      "status": "UP",
      "details": { "responseTimeMs": 12, "query": "SELECT 1" }
    },
    "diskSpace": {
      "status": "UP",
      "details": { "total": 499963174912, "free": 205123584000, "threshold": 10485760 }
    },
    "livenessState":  { "status": "CORRECT" },
    "readinessState": { "status": "ACCEPTING_TRAFFIC" }
  }
}
```

---

### 3.6 Runtime Log Level Change — Production Superpower

**Scenario**: Production mein mysterious error. INFO level pe kuch nahi dikh raha. Restart karne se traffic jaayega. Solution?

```bash
# Step 1: Current level check
GET /actuator/loggers/com.SpringBootStarter
→ { "configuredLevel": "INFO", "effectiveLevel": "INFO" }

# Step 2: DEBUG on karo — LIVE, zero downtime
POST /actuator/loggers/com.SpringBootStarter
Content-Type: application/json
{ "configuredLevel": "DEBUG" }

# Step 3: Logs dekho, diagnose karo

# Step 4: Wapas INFO pe laao
POST /actuator/loggers/com.SpringBootStarter
{ "configuredLevel": "INFO" }
```

Yeh change **sirf is pod pe** aur **sirf restart tak** valid hai. Perfect for incident diagnosis.

---

### 3.7 Security — Production Mein Critical Rules

```properties
# DEV — sab expose (exploration ke liye)
management.endpoints.web.exposure.include=*

# PROD — sirf yeh teen (monitoring tools ko yahi chahiye)
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=never
management.server.port=9090  # Alag port, firewall se internal only
```

**`*` production mein kyun dangerous hai:**
- `/actuator/heapdump` → Full memory dump → tokens, passwords, PII sab leak
- `/actuator/env` → DB credentials (partially sanitized, not fully)
- `/actuator/shutdown` → Koi bhi POST kar ke app band kar sakta hai

---

### 3.8 Custom HealthIndicator — Jo Humne Implement Kiya

`DatabaseHealthIndicator.java` kya karta hai jo built-in `db` check nahi karta:
1. **Response time measure** karta hai — "connected" aur "fast" alag hain
2. **Slow threshold detect** karta hai (> 200ms) — DOWN nahi, lekin warning detail mein
3. **Proper SLF4J logging** — incident ke time trace kiya ja sake

**Apne project mein adapt karo:**
```java
// External API check
restTemplate.getForEntity("https://payment-gateway/ping", String.class);

// Kafka check
kafkaTemplate.send("health-topic", "ping").get(5, TimeUnit.SECONDS);

// File system check
new File("/opt/certificates/fonts").canWrite();
```

---

## Module 4 — Micrometer

> See implementation: `src/main/java/com/SpringBootStarter/service/ProductServiceImpl.java`

### 4.1 Why Micrometer Exists

**Problem**: Tumhara app chal raha hai. Actuator `/actuator/health` se pata chala UP hai. Lekin:
- Kitni requests aa rahi hain per second?
- Products create hone mein average kitna time lag raha hai?
- Kitne 404 errors hue is ghante mein?
- DB connection pool mein kitne connections idle hain?

Yeh sawaalon ke jawaab **metrics** dete hain — numeric measurements jo time ke saath track hoti hain.

**Micrometer (2017)** ek **metrics facade** hai — exactly wahi role jo SLF4J logging ke liye karta hai, wahi Micrometer metrics ke liye karta hai.

```
Tumhara Code → Micrometer API → [backend] → Prometheus / Datadog / CloudWatch / Graphite
```

Ek hi code likho. Backend baad mein decide karo. Switch karna ho toh sirf dependency change karo.

> 💡 **Analogy**: Electricity meter ghar ke bahar lagta hai. Ghar ke andar koi wiring change nahi hoti. Meter badlo — data wahi milta hai.

---

### 4.2 MeterRegistry — Central Registry

`MeterRegistry` Micrometer ka core object hai. Sab metrics is registry mein register hoti hain.

Spring Boot auto-configure karta hai:
- `SimpleMeterRegistry` (in-memory, always present)
- `PrometheusMeterRegistry` (agar `micrometer-registry-prometheus` dependency hai)
- Dono ek saath ho sakti hain — each gets its own copy of all metrics

```java
@Service
public class ProductServiceImpl {

    private final MeterRegistry meterRegistry;

    // Spring automatically injects the configured MeterRegistry
    public ProductServiceImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
}
```

---

### 4.3 The Four Core Metric Types

#### Counter — Sirf badhta hai

```java
// Har product creation pe +1
Counter.builder("products.created.total")
    .description("Total number of products created")
    .tag("status", "success")
    .register(meterRegistry)
    .increment();
```

**Use for**: Request counts, error counts, events. Kabhi ghatta nahi — restart pe reset.
**Prometheus mein**: `products_created_total{status="success"} 47`

#### Gauge — Current snapshot

```java
// Abhi DB mein kitne products hain
Gauge.builder("products.total.count", productRepository, ProductRepository::count)
    .description("Current total products in database")
    .register(meterRegistry);
```

**Use for**: Queue depth, active connections, memory usage. Upar-neeche dono jaata hai.
**Prometheus mein**: `products_total_count 142`

#### Timer — Duration measure karo

```java
// Product fetch kitne time mein complete hua
Timer timer = Timer.builder("product.fetch.duration")
    .description("Time taken to fetch a product")
    .tag("operation", "getById")
    .register(meterRegistry);

Timer.Sample sample = Timer.start(meterRegistry);
Product product = productRepository.findById(id).orElseThrow(...);
sample.stop(timer);
```

**Use for**: API response times, DB query times, external API call durations.
**Prometheus mein**: count, sum, AND histogram buckets (p50, p95, p99)

#### Distribution Summary — Size/value distribution

```java
// Product price distribution
DistributionSummary.builder("product.price.distribution")
    .description("Distribution of product prices")
    .baseUnit("rupees")
    .register(meterRegistry)
    .record(product.getPrice());
```

**Use for**: Request payload sizes, prices, scores — koi bhi numeric value jiska distribution dekhna ho.

---

### 4.4 Tags — Metrics Ko Filter Karne Ka Tarika

Tag = key-value pair jo metric ke saath attach hota hai.

```java
Counter.builder("products.operations")
    .tag("operation", "create")   // create / update / delete
    .tag("status",    "success")  // success / failure
    .register(meterRegistry)
    .increment();
```

**Prometheus query examples:**
```promql
# Sirf create operations
products_operations_total{operation="create"}

# Failure rate
rate(products_operations_total{status="failure"}[5m])

# Success vs failure comparison
products_operations_total{operation="create", status="success"}
products_operations_total{operation="create", status="failure"}
```

**Cardinality warning**: Tags mein high-cardinality values mat daalo.
```java
// WRONG — har product ka alag ID → millions of metric series → Prometheus crash
.tag("productId", String.valueOf(id))

// RIGHT — fixed set of values
.tag("operation", "create")  // sirf create/update/delete = 3 values
```

---

### 4.5 What Spring Boot Auto-Instruments

Bina koi code likhe, Spring Boot + Micrometer automatically track karta hai:

| Metric | Kya track karta hai |
|--------|---------------------|
| `http.server.requests` | Har HTTP request — method, URI, status, duration |
| `jvm.memory.used` | JVM heap/non-heap memory |
| `jvm.gc.pause` | Garbage collection pause duration |
| `jvm.threads.live` | Active thread count |
| `hikaricp.connections.active` | DB connection pool usage |
| `hikaricp.connections.pending` | Requests waiting for a connection |
| `spring.data.repository.invocations` | JPA repository method call counts + duration |

Yeh sab `/actuator/metrics` pe available hain turat — sirf Actuator + Micrometer dependency chahiye.

---

### 4.6 Custom Business Metrics — Jo Humne Implement Kiya

`ProductServiceImpl` mein humne add kiya:
1. **Counter** — products created/deleted successfully
2. **Counter** — product not found errors
3. **Timer** — har operation ki duration (automatic p50, p95, p99)

`GET /actuator/metrics/products.created.total` se verify karo.
`GET /actuator/metrics/product.operation.duration` se percentiles dekho.

---

### 4.7 Percentiles — p50 vs p95 vs p99 Kyun Matter Karte Hain

**Average latency lie bolti hai.**

Example: 100 requests
- 99 requests: 10ms mein complete
- 1 request:   5000ms mein complete (DB lock tha)
- Average: 59.9ms → "system fast hai"

Lekin woh 1 citizen jiska request 5 seconds lag gaya — uska experience?

```
p50 (median) = 10ms   → 50% requests is se faster
p95          = 10ms   → 95% requests is se faster  
p99          = 5000ms → 99% requests is se faster — 1% ka pain dikha
```

**SLO define karo percentiles pe:**
```
"95% of product fetch requests must complete in < 500ms"
```

Average pe nahi — p95 pe. Kyunki average hide kar deta hai tail latency.

---

## Module 5 — Prometheus: TSDB, Scraping & PromQL

### 5.1 Pull Model vs Push Model — WHY Prometheus Pull Use Karta Hai?

Yeh ek fundamental design decision hai. Samajhte hain **real-world analogy** se:

> **Push model** = Patient khud doctor ko call karta hai aur apni heartbeat batataa hai. Agar patient busy hai, beemar hai, ya network down hai — doctor ko pata hi nahi chala.
>
> **Pull model** = Doctor khud patient ke paas aata hai aur check karta hai. Doctor control mein hai — woh decide karta hai KAB check karna hai, KITNI baar karna hai.

| Dimension | Pull (Prometheus) | Push (e.g. StatsD, InfluxDB) |
|---|---|---|
| **Control** | Scraper controls timing | App controls timing |
| **Service Discovery** | Centralized in prometheus.yml | Distributed — every app needs target URL |
| **Network Failure** | Prometheus detects "target down" automatically | Metrics silently disappear — no detection |
| **Back-pressure** | Prometheus slows down naturally | App can flood the metrics server |
| **Security** | One-directional — app exposes read-only endpoint | App needs write access to central server |
| **Debugging** | `curl /actuator/prometheus` — human readable! | Can't easily inspect what's being sent |

**WHY Pull is better for microservices:** Agar 50 services hain aur 3 metrics servers hain, Push model mein har service ko teen servers ke addresses pata hone chahiye. Pull mein sirf Prometheus ko service ka address pata hona chahiye — service completely unaware hai ki kaun usse scrape kar raha hai.

#### Kab PushGateway Use Karte Hain?

Pull model ka ek genuine weakness hai: **short-lived jobs** (cron jobs, batch jobs). Ek job jo 30 seconds mein complete ho jaaye — Prometheus usse kabhi scrape nahi kar sakta (default 15s interval se pehle job khatam).

**Solution: PushGateway** — job apne metrics PushGateway ko push karta hai, Prometheus PushGateway ko scrape karta hai.

```
Batch Job → push → PushGateway ← scrape ← Prometheus
```

**Caution:** PushGateway ek anti-pattern ban jaata hai agar long-running services ke liye use karo. Metrics stale ho jaate hain lekin Prometheus ko pata nahi chalta ki service down hai ya sirf idle hai.

---

### 5.2 Prometheus TSDB (Time-Series Database) Internals

#### Data Model — Har Metric Ek Time Series Hai

```
metric_name{label1="val1", label2="val2"} value timestamp
```

Real example jo humara Spring Boot app expose karta hai:
```
http_server_requests_seconds_count{
  application="spring-boot-starter",
  method="GET",
  status="200",
  uri="/api/products"
} 1547 1722000000000
```

Yeh ek **unique time series** hai. Labels ki different combinations = different time series.

#### HIGH CARDINALITY — OOM Ka Seedha Raasta

```
# BAD — userId label = millions of unique series = Prometheus OOM crash
http_requests_total{userId="user_12345"} 1

# GOOD — fixed, bounded set of label values
http_requests_total{status="200", method="GET"} 1547
```

Rule: Ek label ke possible values **< 100** hone chahiye. Status codes (200, 404, 500), HTTP methods (GET, POST) — safe hain. User IDs, request IDs, timestamps labels mein — kabhi nahi.

#### TSDB Storage Architecture

Prometheus data disk pe **2-hour immutable blocks** mein store karta hai:

```
data/
├── 01BZJ0Q2CQJKZ2M3X9ZKJ9XS/   ← Block (2-hour window of data)
│   ├── chunks/
│   │   └── 000001               ← Gorilla-compressed time series data
│   ├── index                    ← Label index for fast lookups
│   ├── meta.json                ← Block metadata (min/max time)
│   └── tombstones               ← Soft-deletes (for delete API)
└── wal/                         ← Write-Ahead Log
    └── 00000001                 ← In-memory data → WAL → block (on flush)
```

**WAL (Write-Ahead Log):** Naye data pehle WAL mein jaata hai (fast sequential write). Har 2 ghante baad WAL ko compress karke immutable block bana deta hai. Crash hone par WAL se recovery hoti hai — no data loss.

**Gorilla Compression:** Delta-encoding timestamps + XOR encoding values. Result:
- Uncompressed: ~12 bytes per sample
- Compressed: ~1.3 bytes per sample (**~90% compression ratio**)

**Retention:** Default 15 days. Production mein 30-90 days common hai.
```yaml
# docker-compose.yml mein
- '--storage.tsdb.retention.time=30d'
```

---

### 5.3 PromQL — Prometheus Query Language

#### 4 Metric Types — Foundation

```
# 1. Counter — sirf badhta hai, restart pe reset
products_created_total{application="spring-boot-starter"} 1547

# 2. Gauge — current value, up/down jaata hai
jvm_memory_used_bytes{area="heap"} 104857600

# 3. Histogram — request latency distribution in buckets
http_server_requests_seconds_bucket{le="0.1"}  800   ← 800 requests finished < 100ms
http_server_requests_seconds_bucket{le="0.5"}  950   ← 950 requests finished < 500ms
http_server_requests_seconds_bucket{le="+Inf"} 1000  ← total 1000 requests
http_server_requests_seconds_sum               120.5  ← total seconds spent
http_server_requests_seconds_count             1000

# 4. Summary — pre-computed percentiles (client-side)
product_operation_duration_seconds{quantile="0.95"} 0.234
```

**Histogram vs Summary:**
- **Histogram** = Server (Prometheus) computes percentiles from buckets → aggregatable across replicas ✅
- **Summary** = Client (JVM) pre-computes percentiles → NOT aggregatable across replicas ❌
- Always prefer Histogram for services with multiple replicas.

#### `rate()` vs `irate()` — Sabse Common Confusion

**Real-world analogy:** `rate()` = trip ki average speed (smooth). `irate()` = GPS speedometer (instantaneous, spiky).

```promql
# rate() — average per-second rate over 5 minutes (smooth, good for alerts)
rate(products_created_total[5m])

# irate() — rate between last 2 data points only (spiky, good for debugging)
irate(products_created_total[5m])
```

**Rule of thumb:**
- **Alerts ke liye:** `rate()` — ek spike se false alert fire nahi hoga
- **Live debugging dashboards:** `irate()` — recent change immediately dikhta hai

**Range window select karne ka formula:**
> Window >= **4x scrape interval**. Agar scrape 15s hai → minimum `[1m]`. Production standard: `[5m]`.

#### `histogram_quantile()` — P95/P99 Live Calculate Karna

```promql
# P95 latency for all HTTP endpoints
histogram_quantile(
  0.95,
  sum by (le, uri) (
    rate(http_server_requests_seconds_bucket[5m])
  )
)
```

**WHY `sum by (le)` zaroori hai?** Agar 3 replicas hain, teen alag histogram bucket series hain. `sum by (le)` unhe merge karta hai — tabhi `histogram_quantile` mathematically correct answer deta hai.

#### Error Rate — BiharOne SLA Monitoring

```promql
# 5xx error rate as percentage of total requests (last 5 min)
100 * (
  sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  /
  sum(rate(http_server_requests_seconds_count[5m]))
)
```

```promql
# Alert condition: trigger if error rate > 1%
100 * (
  sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  /
  sum(rate(http_server_requests_seconds_count[5m]))
) > 1
```

#### Essential PromQL Cheatsheet

| Function | Use Case | Example |
|---|---|---|
| `rate(counter[5m])` | Per-second rate (smooth) | `rate(products_created_total[5m])` |
| `irate(counter[5m])` | Instantaneous rate | `irate(http_requests_total[5m])` |
| `increase(counter[1h])` | Total increase in window | `increase(products_created_total[1h])` |
| `histogram_quantile(φ, buckets)` | P50/P95/P99 from histogram | `histogram_quantile(0.99, rate(...bucket[5m]))` |
| `sum by (label)` | Group and aggregate | `sum by (status) (rate(...))` |
| `topk(N, metric)` | Top N series by value | `topk(5, rate(http_requests[5m]))` |
| `predict_linear(gauge[1h], 3600)` | Linear prediction (disk full?) | `predict_linear(disk_free[1h], 86400)` |
| `absent(metric)` | Alert if metric disappears | `absent(up{job="spring-boot-starter"})` |

---

### 5.4 Practical Setup — Prometheus + Grafana Locally

#### Files Created:

```
docker/
└── prometheus/
    └── prometheus.yml      ← Scrape config (target: our Spring Boot on :8082)
docker-compose.yml          ← Prometheus :9090 + Grafana :3000
```

#### Step 1: Spring Boot App Start Karo

```bash
# Ensure app is running on port 8082
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Verify metrics endpoint is live:
curl http://localhost:8082/actuator/prometheus | head -30
```

#### Step 2: Prometheus + Grafana Start Karo

```bash
docker-compose up -d
```

#### Step 3: Prometheus UI Explore Karo

Open: `http://localhost:9090`

**Targets check karo:** `http://localhost:9090/targets`
- `spring-boot-starter` ka status `UP` dikhna chahiye
- Last scrape time aur duration visible hai

**Try these queries in Graph tab:**

```promql
# 1. Check app is up
up{job="spring-boot-starter"}

# 2. JVM heap usage in MB
jvm_memory_used_bytes{area="heap", application="spring-boot-starter"} / 1024 / 1024

# 3. HTTP request rate (last 5 min)
rate(http_server_requests_seconds_count{application="spring-boot-starter"}[5m])

# 4. P95 latency per endpoint
histogram_quantile(0.95,
  sum by (le, uri) (
    rate(http_server_requests_seconds_bucket{application="spring-boot-starter"}[5m])
  )
)

# 5. Our custom product counter
products_created_total{application="spring-boot-starter"}

# 6. Active HTTP connections
tomcat_connections_active_current_connections
```

#### Step 4: Grafana Connect Karo (Preview — Module 6 mein detail)

Open: `http://localhost:3000` (admin/admin)

Add Prometheus data source:
- URL: `http://prometheus:9090` (container name, not localhost!)
- Save & Test → "Data source is working"

---

### 5.5 Key Takeaways

1. **Pull model** gives Prometheus centralized control + automatic "target down" detection.
2. **PushGateway** sirf short-lived jobs ke liye — long-running services ke liye never use karo.
3. **TSDB** 2-hour immutable blocks + Gorilla compression = ~90% space savings.
4. **Labels** power hai — lekin high cardinality (userId, requestId) = OOM death.
5. **`rate()` for alerts, `irate()` for debugging** — dono ka range window >= 4x scrape interval.
6. **`histogram_quantile()` + `sum by (le)`** = correct cross-replica percentiles.

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
