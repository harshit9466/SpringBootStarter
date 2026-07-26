# Production Spring Boot Actuator Setup Guide
### Reusable across any Spring Boot project — Maven or Gradle, MVC or Hexagonal

> **What this document is**: A step-by-step implementation guide for setting up production-grade
> Spring Boot Actuator with custom health checks, Kubernetes liveness/readiness probes, and secure endpoint exposure.
> Every line of code and configuration is explained — what it does, why it exists, and what breaks if removed.
>
> **Who should follow this**: Backend engineers, AI assistants replicating this setup in a new project,
> or DevOps engineers verifying application production-readiness.

---

## Table of Contents

1. [Conceptual Overview — Read Before Coding](#1-conceptual-overview--read-before-coding)
2. [Step 1 — Add Dependencies](#2-step-1--add-dependencies)
3. [Step 2 — Configure application.properties (Dev vs Prod)](#3-step-2--configure-applicationproperties-dev-vs-prod)
4. [Step 3 — Implement Custom HealthIndicator](#4-step-3--implement-custom-healthindicator)
5. [Step 4 — Understand Kubernetes Probes Integration](#5-step-4--understand-kubernetes-probes-integration)
6. [Step 5 — Runtime Log Level Management via Actuator](#6-step-5--runtime-log-level-management-via-actuator)
7. [Verification Checklist](#7-verification-checklist)
8. [Common Mistakes and What Goes Wrong](#8-common-mistakes-and-what-goes-wrong)
9. [Architecture Adaptation Notes](#9-architecture-adaptation-notes)

---

## 1. Conceptual Overview — Read Before Coding

Spring Boot Actuator provides built-in HTTP endpoints to monitor and interact with your application without writing boilerplate operational controllers.

```
Incoming Monitoring Traffic (Kubernetes Probes, Load Balancer, DevOps, Prometheus)
     │
     ▼
┌────────────────────────────────────────────────────────────────────────┐
│  Actuator Endpoints (/actuator/*)                                      │
│  ├── /health          → Overall status (UP/DOWN/OUT_OF_SERVICE)        │
│  ├── /health/liveness → "Is the JVM alive?" (Kubernetes Liveness)      │
│  ├── /health/readiness→ "Can we handle traffic?" (Kubernetes Readiness)│
│  ├── /info            → Git commit, build version, app metadata        │
│  └── /loggers         → Live log level mutation without restart        │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ (Aggregates)
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│  CompositeHealthContributor                                            │
│  ├── DataSourceHealthIndicator (Built-in JPA check: "SELECT 1")        │
│  ├── DiskSpaceHealthIndicator  (Built-in disk space check)             │
│  └── DatabaseHealthIndicator   (OUR CUSTOM CHECK: Latency + SLI)       │
└────────────────────────────────────────────────────────────────────────┘
```

### Why standard health checks are not enough for enterprise production:
- Built-in DB checks only verify if a TCP connection can be established or a simple query executes.
- If your database takes **5,000ms (5 seconds)** to respond, built-in indicators still report `"status": "UP"`.
- To user traffic, a 5-second DB latency is an outage. Our custom indicator introduces **latency threshold alerting** and structured diagnosis.

---

## 2. Step 1 — Add Dependencies

### For Maven projects (`pom.xml`)

```xml
<!--
  spring-boot-starter-actuator
  ============================
  WHY: Pulls in auto-configurations for production readiness endpoints (/health, /metrics, /info, etc.).
  
  WHAT HAPPENS IF REMOVED: None of the /actuator/* URLs will exist. Kubernetes probes will receive 404 Not Found,
  causing load balancers to drop the pod or Kubernetes to repeatedly restart it in a crash loop.
-->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### For Gradle projects (`build.gradle` — Groovy DSL)

```groovy
dependencies {
    // Actuator starter for production monitoring and health probes
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
}
```

### For Gradle projects (`build.gradle.kts` — Kotlin DSL)

```kotlin
dependencies {
    // Actuator starter for production monitoring and health probes
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

---

## 3. Step 2 — Configure application.properties (Dev vs Prod)

Actuator configuration MUST differ between development and production environments. Exposing all endpoints in production is a severe security vulnerability.

### `src/main/resources/application-dev.properties` (Development / Exploration)

```properties
# =============================================================================
# SPRING BOOT ACTUATOR CONFIGURATION (DEV)
# =============================================================================

# management.endpoints.web.exposure.include
# → Controls which actuator endpoints are accessible over HTTP.
# → In DEV, we set "*" to expose all endpoints (/beans, /env, /mappings, etc.) for debugging.
# → WHAT HAPPENS IF MISSING: Defaults to "health". You won't be able to inspect /metrics, /loggers, or /info.
management.endpoints.web.exposure.include=*

# management.endpoint.health.show-details
# → Controls the granularity of JSON returned by /actuator/health.
# → "always": Shows individual status and details of DB, disk space, and custom indicators.
# → WHAT HAPPENS IF REMOVED (Defaults to "never"): You only get {"status":"UP"} without knowing WHICH database or disk is checked.
management.endpoint.health.show-details=always

# management.endpoint.health.show-components
# → Forces Spring Boot to group health details under a "components" JSON block.
# → Essential for structured parsing by monitoring dashboards.
management.endpoint.health.show-components=always

# Liveness and Readiness Probes Integration
# → Enables /actuator/health/liveness and /actuator/health/readiness endpoints.
# → In production, Kubernetes uses these. We enable in DEV to test probe responses locally.
# → WHAT HAPPENS IF REMOVED: In non-Kubernetes environments, these endpoints are disabled by default unless explicitly set to true.
management.endpoint.health.probes.enabled=true

# Enable environment properties in /actuator/info
management.info.env.enabled=true

# Populate /actuator/info from Maven/Gradle build metadata
info.app.name=@project.name@
info.app.version=@project.version@
info.app.description=@project.description@
info.app.java-version=@java.version@
```

### `src/main/resources/application-prod.properties` (Production / Hardened)

```properties
# =============================================================================
# SPRING BOOT ACTUATOR CONFIGURATION (PROD - HARDENED)
# =============================================================================

# CRITICAL SECURITY RULE: NEVER use "*" in production.
# Expose ONLY what your monitoring infrastructure (Prometheus, Kubernetes, Grafana) explicitly needs.
# Why? Exposing /env or /heapdump can leak database passwords, API keys, and user PII in memory.
management.endpoints.web.exposure.include=health,info,metrics,prometheus

# Hide internal stack traces and infrastructure details from unauthenticated external users.
# Use "when-authorized" if placed behind Spring Security, or "never" if scraped by external load balancers.
management.endpoint.health.show-details=never

# Probes are automatically enabled when Spring Boot detects it is running inside Kubernetes.
# Setting it explicitly ensures consistent behavior across bare-metal or Docker deployments.
management.endpoint.health.probes.enabled=true

# OPTIONAL BUT RECOMMENDED FOR ENTERPRISE: Run Actuator on a different internal port
# Why? You can block port 9090 on public firewalls/API Gateways while allowing port 8080 for user traffic.
# What happens if removed: Actuator runs on the main server.port (8080), requiring strict URL-based security rules.
management.server.port=9090
```

---

## 4. Step 3 — Implement Custom HealthIndicator

Standard database connectivity checks don't measure latency. We implement a custom indicator that measures execution time and flags slow responses before they cause connection pool starvation.

**Location**: Create in a `health` package inside your base project.
- MVC architecture: `com.example.app.health.DatabaseHealthIndicator`
- Hexagonal architecture: `com.example.adapter.out.persistence.health.DatabaseHealthIndicator` (Outbound persistence infrastructure concern)

```java
package com.yourpackage.health; // ADAPT: change to your base package

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/*
 * DatabaseHealthIndicator
 * =======================
 * PURPOSE: Provides an SLA-aware database health check that monitors execution latency.
 *
 * HOW IT WORKS:
 * Spring Boot Actuator auto-discovers any bean implementing HealthIndicator.
 * The class name without the "HealthIndicator" suffix becomes the JSON component key:
 * "DatabaseHealthIndicator" -> JSON key "database" (or "databaseHealthIndicator" depending on bean naming).
 *
 * WHY NOT USE ONLY Spring's DataSourceHealthIndicator?
 * 1. DataSourceHealthIndicator only tells you if a connection succeeded.
 * 2. If a network glitch causes queries to take 1,500ms instead of 5ms, standard checks say "UP".
 * 3. This check records responseTimeMs and sets detail attributes that Grafana alerts can evaluate.
 */
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    /*
     * Execution threshold in milliseconds.
     * If query execution exceeds this time, we log a warning and attach a "SLOW" marker.
     * ADAPT: Adjust this based on your database location (e.g., 50ms for local DB, 250ms for cloud RDS).
     */
    private static final long SLOW_QUERY_THRESHOLD_MS = 200;

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        long startTime = System.currentTimeMillis();

        try {
            /*
             * "SELECT 1" is universal across PostgreSQL, MySQL, H2, and SQL Server.
             * It validates database engine responsiveness without locking application tables.
             */
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long responseTimeMs = System.currentTimeMillis() - startTime;

            /*
             * CASE 1: Slow Database Response
             * Why return Health.up() instead of Health.down() when slow?
             * If we return DOWN, Kubernetes readiness probe will fail and remove this pod from the load balancer.
             * Removing pods under high load increases load on remaining pods, causing cascading system failure!
             * Instead, keep status UP, mark as SLOW in details, and let Prometheus alert DevOps.
             */
            if (responseTimeMs > SLOW_QUERY_THRESHOLD_MS) {
                log.warn("Database health check slow. responseTimeMs={}, thresholdMs={}", 
                         responseTimeMs, SLOW_QUERY_THRESHOLD_MS);

                return Health.up()
                        .withDetail("status", "SLOW")
                        .withDetail("responseTimeMs", responseTimeMs)
                        .withDetail("thresholdMs", SLOW_QUERY_THRESHOLD_MS)
                        .withDetail("query", "SELECT 1")
                        .build();
            }

            // CASE 2: Healthy and Fast
            log.debug("Database health check passed. responseTimeMs={}", responseTimeMs);
            return Health.up()
                    .withDetail("responseTimeMs", responseTimeMs)
                    .withDetail("query", "SELECT 1")
                    .build();

        } catch (Exception e) {
            long responseTimeMs = System.currentTimeMillis() - startTime;

            /*
             * CASE 3: Complete Database Failure
             * Return DOWN. When Actuator aggregates this, overall /actuator/health becomes DOWN.
             * Kubernetes liveness/readiness probes will react accordingly.
             */
            log.error("Database health check failed. responseTimeMs={}", responseTimeMs, e);

            return Health.down()
                    .withException(e)
                    .withDetail("responseTimeMs", responseTimeMs)
                    .withDetail("query", "SELECT 1")
                    .build();
        }
    }
}
```

---

## 5. Step 4 — Understand Kubernetes Probes Integration

In a cloud-native deployment, Kubernetes relies on Actuator to make pod lifecycle decisions.

```
       Kubernetes Kubelet
          │          │
Liveness  │          │ Readiness
Probe     ▼          ▼ Probe
   ┌───────────┐  ┌───────────┐
   │ /liveness │  │/readiness │
   └─────┬─────┘  └─────┬─────┘
         │              │
         ▼              ▼
   [LivenessState] [ReadinessState]
```

### 1. Liveness Probe (`/actuator/health/liveness`)
* **Question Asked**: "Is the JVM running and not deadlocked?"
* **Success (200 OK)**: Returns `{"status": "UP"}` (LivenessState = `CORRECT`). Pod keeps running.
* **Failure (503 Service Unavailable)**: Returns `{"status": "DOWN"}` (LivenessState = `BROKEN`).
* **Kubernetes Action on Failure**: Kubelet **kills and restarts the container**.
* **Rule**: NEVER check database or external API connectivity inside Liveness. If the database goes down, restarting your application pods will not fix the database—it will only cause crash loops!

### 2. Readiness Probe (`/actuator/health/readiness`)
* **Question Asked**: "Is this specific pod ready to accept user HTTP traffic?"
* **Success (200 OK)**: Returns `{"status": "UP"}` (ReadinessState = `ACCEPTING_TRAFFIC`). Load balancer sends traffic.
* **Failure (503 Service Unavailable)**: Returns `{"status": "OUT_OF_SERVICE"}` or `"DOWN"`.
* **Kubernetes Action on Failure**: Removes pod IP from the Service Endpoints. **No restarts occur**.
* **Rule**: Database connectivity and required cache readiness belong here. If DB fails, stop sending user requests to this pod until DB recovers.

---

## 6. Step 5 — Runtime Log Level Management via Actuator

Actuator allows you to dynamically mutate SLF4J/Logback log levels at runtime without restarting the application or losing existing user sessions.

### Scenario: Debugging a live production issue
You notice errors in a specific service package (`com.yourpackage.service`) but `INFO` level logs do not provide enough context.

#### 1. Check current configured log level:
```bash
curl -X GET http://localhost:8082/actuator/loggers/com.yourpackage.service
```
**Response**: `{"configuredLevel":"INFO","effectiveLevel":"INFO"}`

#### 2. Dynamically change level to DEBUG without restart:
```bash
curl -X POST http://localhost:8082/actuator/loggers/com.yourpackage.service \
     -H "Content-Type: application/json" \
     -d '{"configuredLevel":"DEBUG"}'
```
*(Returns 204 No Content on success)*

#### 3. Revert back to INFO after capturing diagnosis:
```bash
curl -X POST http://localhost:8082/actuator/loggers/com.yourpackage.service \
     -H "Content-Type: application/json" \
     -d '{"configuredLevel":"INFO"}'
```

> **Security Warning**: Because `/actuator/loggers` allows modifying application state, it must ALWAYS be secured or restricted to internal DevOps networks in production.

---

## 7. Verification Checklist

Run your Spring Boot application and execute these terminal verifications:

```
□ Test general health endpoint (should show custom indicator and DB response time):
  curl -i http://localhost:8082/actuator/health

□ Verify Kubernetes Liveness probe returns 200 OK:
  curl -i http://localhost:8082/actuator/health/liveness

□ Verify Kubernetes Readiness probe returns 200 OK:
  curl -i http://localhost:8082/actuator/health/readiness

□ Check application git/version info:
  curl -i http://localhost:8082/actuator/info

□ Test dynamic logger level inspection:
  curl -i http://localhost:8082/actuator/loggers/root
```

---

## 8. Common Mistakes and What Goes Wrong

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Exposing `*` in production properties | Security scan fails; passwords/env variables exposed to public | Set `exposure.include=health,info,metrics,prometheus` in prod |
| Returning `Health.down()` on slow database response | Kubernetes readiness probe fails; healthy pods removed from service | Return `Health.up()` with custom `"status":"SLOW"` detail attribute |
| Adding heavy DB queries to Liveness check | Transient DB locks cause Kubernetes to kill and restart app pods | Keep Liveness checks lightweight (pure JVM memory/thread check) |
| Missing `@Component` on custom HealthIndicator | Custom check does not appear in `/actuator/health` JSON | Annotate class with `@Component` so Actuator auto-discovers it |
| Using same port (8080) in prod without Spring Security | External users can access `/actuator/env` or `/actuator/metrics` | Set `management.server.port=9090` and block 9090 on external firewall |

---

## 9. Architecture Adaptation Notes

### Standard Layered (MVC) Architecture
* **Package location**: `com.example.app.health`
* **Dependency injection**: Inject `JdbcTemplate` or `Repository` directly into your custom `HealthIndicator`.

### Hexagonal / Ports & Adapters Architecture
* **Package location**: `com.example.adapter.out.persistence.health`
* **Why**: Health checks against infrastructure (databases, message brokers, external APIs) are **outbound adapter concerns**. Never place Actuator health checks inside the pure domain model or core application service layers.
* **Interface isolation**: If checking an external REST service, inject the outbound port interface rather than calling raw HTTP clients inside the indicator.

---

*Document maintained alongside codebase. Update whenever health check requirements change.*
