# Shared Observability Library — Future Plan
### A design doc to revisit later, not a guide to implement now

> **Status: IMPLEMENTED.** Built as `observability-starter`
> (group `io.observability`), source hosted at
> `https://github.com/harshit9466/observability-starter` (personal, so it stays with its author
> regardless of employer) and mirrored to the company's own Bitbucket for internal ownership.
> Deliberately kept the group/package fully generic — no personal name, no platform reference —
> so it can be reused across other services/projects, not just this one. Group ID and package
> naming are independent of which git host the source lives in; see the conversation this was
> built from for the full reasoning if it needs re-explaining later.
>
> **Honest note on timing**: condition 1 below (all 10 course modules complete) was met before
> building. Condition 2 (2-3 real services already adopted) was NOT strictly met first — the
> decision was made to build anyway given real-world time constraints, accepting the
> premature-abstraction risk described below rather than waiting further. Re-validate the
> candidate contents (§5) against real usage as actual services adopt it.

---

## 1. Why This Document Exists

While working through the observability course, the question came up: *"Can we package all of
this into a shared library so other BiharOne services don't have to redo the same setup?"*

The direction is right, but the timing and the design boundary matter. This document captures
the thinking now, while it's fresh, so we don't have to re-derive it later — but the actual
build is intentionally postponed (see Status above).

---

## 2. The Proposal

Build an `observability-starter` — a Spring Boot **auto-configuration starter**, the
same pattern as `spring-boot-starter-web` or `spring-boot-starter-data-jpa`. Any service adds
one dependency and gets the observability baseline without copy-pasting files:

```kotlin
// some-other-service/build.gradle.kts
implementation("io.observability:observability-starter:1.0.0-SNAPSHOT")
```

No manual wiring. Spring Boot's `AutoConfiguration.imports` mechanism auto-registers the beans.

---

## 3. Why We're Deferring

1. **Only one reference implementation exists.** We genuinely don't know yet what's common
   across services vs. incidental to how `spring-boot-starter` happens to be built.
2. **Publishing infrastructure isn't decided.** Where does the JAR live — internal Nexus,
   Artifactory, GitHub Packages? This needs a decision before any team can `implementation` it.
3. **Premature abstraction risk.** Extracting a library from one example is guessing at
   boundaries. Extracting from 2–3 real examples means the boundaries are observed, not guessed.

---

## 4. Design Principles Already Decided

These were worked out in conversation and apply regardless of when the library gets built.

### 4.1 The Core Split: Infrastructure vs. Business Metrics

> The library provides **infrastructure** — how to measure. It can never provide **business
> metrics** — what to measure. Only the service team that owns a domain (products, certificates,
> payments) knows what its own meaningful events are.

**Analogy used**: Spring Data JPA's `JpaRepository` gives you shared infrastructure
(`EntityManager`, transaction handling, query execution) — but you still write your own
`findByEmail()`. The framework can't know your domain's query needs in advance. Same logic
applies to observability: the library can't know that "a product was added" or "a certificate
application was submitted" are meaningful events — only the owning service knows that.

**Second analogy**: Google's SRE model splits responsibility the same way — a platform/SRE team
provides generic golden-signal tooling (Traffic, Errors, Latency, Saturation) company-wide;
each product team instruments its own business KPIs because only they know what matters for
their domain. This library plan mirrors that split.

### 4.2 What "Automatic" Already Means (No Library Needed For This)

Spring Boot + Micrometer already auto-instrument these with **zero code**, in every service,
today — a library doesn't add value here, it's already free:

- `http_server_requests_seconds_count` / `_bucket` — every HTTP request, by status/method/uri
- `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_live`
- `hikaricp_connections_active/max/pending`

Business-reason 404s (e.g. "a valid product ID lookup failed") are a different, deliberately
separate concept from raw HTTP 404 counts — see `guides/03-micrometer-setup-guide.md` §4.6 for
why both exist side by side.

### 4.3 The Convention-Enforcing Helper Pattern

To reduce per-service boilerplate for the metrics that genuinely can't be automated (business
counters/timers), the library should ship a small helper that enforces naming conventions
instead of trying to author the metrics themselves:

```java
// Library class: io.observability.metrics.BusinessMetrics
public final class BusinessMetrics {
    private BusinessMetrics() {}

    public static Counter counter(MeterRegistry registry, String domain, String action) {
        if (action.equalsIgnoreCase("created")) {
            throw new IllegalArgumentException(
                "Use 'added' instead of 'created' — OpenMetrics reserves the _created suffix");
        }
        return Counter.builder(domain + "." + action).register(registry);
    }

    public static Timer timer(MeterRegistry registry, String domain, String operation) {
        return Timer.builder(domain + ".operation.duration")
                .tag("operation", operation)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
```

Each service still declares its own domain vocabulary (`"products"`, `"certificate.applications"`)
— the library only prevents known mistakes (like the `_created` suffix issue hit in this project)
and keeps naming consistent across services.

### 4.4 Consider Micrometer's Own Annotation Support

`@Counted` / `@Timed` (Micrometer core annotations) can reduce manual `.increment()`/`.record()`
calls further — but they only work if `TimedAspect` / `CountedAspect` beans are registered.
**This bean registration is exactly the kind of boilerplate that belongs in the library's
auto-configuration** — services would then just annotate a method instead of wiring aspects
themselves.

```java
@Counted(value = "products.added", description = "Total products created")
@Timed(value = "product.operation.duration", percentiles = {0.5, 0.95, 0.99})
public Product saveProduct(Product product) { ... }
```

Note when implementing: verify the exact Micrometer version's aspect package before writing this
— don't guess the import path.

---

## 5. Candidate Library Contents

| Component | Goes in library? | Reasoning |
|---|---|---|
| `DatabaseHealthIndicator` | ✅ Yes | Generic — works with any `JdbcTemplate`, no domain knowledge needed |
| `MdcRequestFilter` | ✅ Yes | Generic — every service needs traceId/request correlation the same way |
| `logback-spring.xml` defaults | ✅ Yes | Console (dev) + JSON (prod) pattern is identical across services |
| `MeterRegistry` global `application` tag config | ✅ Yes | Already just binds `spring.application.name` — zero per-service code either way |
| `TimedAspect` / `CountedAspect` bean registration | ✅ Yes | Enables `@Timed`/`@Counted` annotations without each service wiring aspects |
| `BusinessMetrics` naming-convention helper | ✅ Yes | Enforces conventions; domain vocabulary still supplied by each service |
| Grafana RED-method dashboard JSON | ✅ Yes, as a template | Already parameterized via `$application` variable — see `guides/06-grafana-setup-guide.md` |
| Actual business `Counter`/`Timer` instances | ❌ No | Domain-specific — library cannot know "products" or "certificates" exist |
| Business Metrics dashboard row/panels | ❌ No | Service-specific — each service's panel measures its own domain events |
| `SecurityConfig` dev/prod profile split | ❓ Open question | See below — security may warrant its own separate shared module |

---

## 6. Open Questions to Resolve When Revisiting

1. **Publishing**: Internal Nexus, Artifactory, or GitHub Packages? Who administers it?
2. **Versioning**: SemVer discipline — who approves breaking changes across all consuming services?
3. **First adopters**: Which 2–3 BiharOne services pilot this before wider rollout?
4. **Security scope**: Should the dev-profile-permits-all / prod-profile-full-JWT pattern
   (see `SecurityConfig.java` in this repo) live in this same library, or in a separate
   `biharone-security-starter`? Security config changes have higher blast radius than
   observability config — bundling them may make upgrades riskier than necessary.
5. **Ownership**: Which team maintains this long-term once multiple services depend on it?

---

## 7. Related Reading

- [`course/observability-course.md`](course/observability-course.md) — the full theory course this plan grew out of
- [`guides/03-micrometer-setup-guide.md`](guides/03-micrometer-setup-guide.md) §4.6 — business vs. HTTP-layer 404 distinction
- [`guides/06-grafana-setup-guide.md`](guides/06-grafana-setup-guide.md) §14 — multi-service dashboard notes, written with this exact future in mind

---

*This document is a decision record, not a task list. When revisiting, re-validate every
assumption above against however many services have adopted the pattern by then — don't
implement blindly from this doc without checking current reality first.*
