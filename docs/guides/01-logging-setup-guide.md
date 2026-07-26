# Production Logging Setup Guide
### Reusable across any Spring Boot project — Maven or Gradle, MVC or Hexagonal

> **What this document is**: A step-by-step implementation guide for production-grade
> structured logging in any Spring Boot 3.x application. Every line of every file is
> explained — what it does, why it exists, and what breaks if you remove it.
>
> **Who should follow this**: Backend developers, AI assistants replicating this setup
> in a new project, or anyone onboarding to a team that uses this logging standard.

---

## Table of Contents

1. [Conceptual Overview — Read Before Coding](#1-conceptual-overview--read-before-coding)
2. [Step 1 — Add Dependencies](#2-step-1--add-dependencies)
3. [Step 2 — Configure application.properties](#3-step-2--configure-applicationproperties)
4. [Step 3 — Create logback-spring.xml](#4-step-3--create-logback-springxml)
5. [Step 4 — Create MdcRequestFilter](#5-step-4--create-mdcrequestfilter)
6. [Step 5 — Use Logging Correctly in Your Code](#6-step-5--use-logging-correctly-in-your-code)
7. [Verification Checklist](#7-verification-checklist)
8. [Common Mistakes and What Goes Wrong](#8-common-mistakes-and-what-goes-wrong)
9. [Architecture Adaptation Notes](#9-architecture-adaptation-notes)

---

## 1. Conceptual Overview — Read Before Coding

Before touching any file, understand the full picture.

### What we are building and why

```
HTTP Request
     │
     ▼
┌─────────────────────────────────────────────────────────┐
│  MdcRequestFilter                                        │
│  Sets: traceId, requestId, userId into MDC               │
│  (ThreadLocal map — every log on this thread gets        │
│   these values automatically appended)                   │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
         Controller / Service / Repository
         (all log with SLF4J — traceId appears automatically)
                   │
                   ▼
┌─────────────────────────────────────────────────────────┐
│  Logback (the logging engine)                            │
│                                                          │
│  DEV profile  → Console, human-readable colored text     │
│  PROD profile → File, structured JSON (async)            │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
         DevOps collects logs → Loki / Elasticsearch
         → Grafana dashboard → Alerts → Incident diagnosis
```

### The three problems this setup solves

| Problem | Without this setup | With this setup |
|---------|--------------------|-----------------|
| Which request caused the error? | Impossible to tell with 500 concurrent requests | Every log line has `traceId` — filter by it |
| Log format incompatible with DevOps tooling | Plain text — Loki/ELK can't parse efficiently | Structured JSON — native ingestion, no regex |
| Logs slow down the application | Synchronous I/O on every log call adds latency | AsyncAppender — I/O happens on background thread |

---

## 2. Step 1 — Add Dependencies

### For Maven projects (`pom.xml`)

```xml
<!--
  logstash-logback-encoder
  ========================
  WHY: Spring Boot's default Logback writes plain text logs.
       Production log aggregation tools (Loki, Elasticsearch, Splunk) work best
       with structured JSON. This library teaches Logback to write JSON.

  WHY THIS VERSION: 8.x is compatible with Logback 1.4+ which ships with
  Spring Boot 3.x. Do NOT use version 7.x with Spring Boot 3 — Logback API
  changed and you will get ClassNotFoundException at startup.

  WHAT HAPPENS IF REMOVED: logback-spring.xml will fail to start if you
  reference LogstashEncoder in it. Remove the encoder reference from
  logback-spring.xml too if you remove this dependency. Plain text logs will
  still work — you just lose JSON output.
-->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

> **Note on version management**: `logstash-logback-encoder` is NOT in Spring Boot's
> BOM (Bill of Materials), so you must specify the version explicitly. Check
> https://github.com/logfellow/logstash-logback-encoder/releases for latest.

### For Gradle projects (`build.gradle` — Groovy DSL)

```groovy
dependencies {
    // logstash-logback-encoder — same reasoning as Maven above
    // Not in Spring Boot BOM, version must be explicit
    implementation 'net.logstash.logback:logstash-logback-encoder:8.0'
}
```

### For Gradle projects (`build.gradle.kts` — Kotlin DSL)

```kotlin
dependencies {
    // logstash-logback-encoder — same reasoning as Maven above
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
}
```

### Verify existing dependencies (no action needed — just understand)

Spring Boot Starter Web/Data JPA already includes:
- `logback-classic` — the Logback logging engine (native SLF4J implementation)
- `slf4j-api` — the logging facade/interface your code writes to
- `log4j-to-slf4j` — bridge that redirects any Log4j calls to SLF4J
- `jul-to-slf4j` — bridge that redirects java.util.logging to SLF4J

You do NOT need to add these separately. Adding them will cause duplicate binding errors.

---

## 3. Step 2 — Configure application.properties

### `src/main/resources/application.properties`

```properties
# spring.application.name
# =======================
# WHY: logback-spring.xml reads this via <springProperty> to include the
#      service name in every log line and in the log file name.
#      Example log output: [spring-boot-starter] INFO c.e.ProductController - ...
#      Example log file:   logs/spring-boot-starter.log
#
# WHAT HAPPENS IF MISSING: logback-spring.xml falls back to defaultValue="app"
#      so it won't crash, but all your log files will be named "app.log" — useless
#      in a multi-service environment where every service writes to the same path.
#
# ADAPT FOR YOUR PROJECT: Replace with your actual service name.
#      Use kebab-case (hyphens), not underscores. This is Spring convention.
#      Examples: certificate-service, payment-gateway, user-management
spring.application.name=your-service-name

# spring.profiles.active
# ======================
# WHY: Tells Spring which environment-specific properties file to load.
#      Also controls which <springProfile> block in logback-spring.xml is active.
#      "dev"  → human-readable console logs
#      "prod" → JSON file logs with async appender
#
# WHAT HAPPENS IF MISSING: Spring uses the default profile. logback-spring.xml
#      will not match any <springProfile> block and NO logging config will apply —
#      Logback falls back to its own minimal default (no MDC, no JSON, no file).
#
# IMPORTANT: In production deployments (Kubernetes, Docker), override this via
#      environment variable: SPRING_PROFILES_ACTIVE=prod
#      Never hardcode "prod" here — this file is committed to Git.
spring.profiles.active=dev
```

### `src/main/resources/application-dev.properties`

```properties
# Logging level configuration for DEV environment
# ================================================
# WHY: Controls verbosity per package. Root = INFO means all Spring internals
#      log at INFO level. Your own code package is set to DEBUG so you see
#      more detail during development without drowning in Spring/Hibernate noise.
#
# WHAT HAPPENS IF ROOT IS DEBUG: You will see thousands of Hibernate SQL parameter
#      binding logs, Spring Security filter chain logs, Tomcat request logs — 
#      your own logs become impossible to find. Always keep root at INFO in dev.
#
# ADAPT FOR YOUR PROJECT: Replace "com.yourpackage" with your actual base package.
#      Example: logging.level.com.example.myservice=DEBUG

logging.level.root=INFO
logging.level.com.yourpackage=DEBUG

# spring.jpa.open-in-view
# =======================
# WHY THIS IS FALSE: Open-in-View (OSIV) keeps the JPA EntityManager (and therefore
#      a DB connection from HikariCP pool) open for the ENTIRE HTTP request lifecycle —
#      from the moment the request enters the servlet until the response is fully
#      serialized. This means:
#      - DB connection is held even during JSON serialization (pure CPU work, no DB needed)
#      - Under high load, your connection pool exhausts faster
#      - Lazy-loaded associations can be triggered accidentally in the view layer
#      Setting false: connection is released as soon as @Transactional method returns.
#
# WHAT HAPPENS IF TRUE (Spring Boot default): A WARN log at startup:
#      "spring.jpa.open-in-view is enabled by default..." — Spring itself warns you.
#      In low-traffic apps you won't notice. At 1000+ RPS, connection pool exhaustion
#      causes HikariCP timeout exceptions under load.
spring.jpa.open-in-view=false
```

---

## 4. Step 3 — Create logback-spring.xml

**Location**: `src/main/resources/logback-spring.xml`

> **CRITICAL — Why `logback-spring.xml` and NOT `logback.xml`?**
>
> `logback.xml` is loaded by Logback itself during JVM startup, BEFORE Spring
> initializes. This means:
> - `<springProfile name="dev">` tags DO NOT WORK — Spring isn't running yet
> - `${spring.application.name}` property substitution DOES NOT WORK
> - You cannot use Spring's `Environment` or `PropertySource`
>
> `logback-spring.xml` is loaded by Spring's `LogbackLoggingSystem` AFTER Spring's
> `Environment` is ready. All Spring features work inside it.
>
> **Rule**: Always use `logback-spring.xml` in Spring Boot projects. Never `logback.xml`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <!--
        springProperty: Read a value from Spring's Environment (application.properties)
        and make it available as a variable inside this XML file.

        source="spring.application.name" → reads the property you set in application.properties
        defaultValue="app"              → fallback if property is missing (won't crash)
        scope="context"                 → available throughout this entire config file

        WHAT HAPPENS IF REMOVED: The APP_NAME variable won't exist. Any reference to
        ${APP_NAME} below will output the literal string "${APP_NAME}" in your logs
        and file names — obviously broken.

        ADAPT FOR YOUR PROJECT: No change needed. This always reads spring.application.name.
    -->
    <springProperty scope="context" name="APP_NAME" source="spring.application.name" defaultValue="app"/>


    <!-- ====================================================================
         DEV PROFILE CONFIGURATION
         Active when: spring.profiles.active=dev
         Output: Colored, human-readable text to console
         Use for: Local development, debugging
    ===================================================================== -->
    <springProfile name="dev">

        <!--
            ConsoleAppender: Writes log output to stdout (System.out).
            In a terminal this shows as colored text.
            In a Kubernetes pod, stdout is captured by the container runtime.

            WHY ConsoleAppender in dev: Developers watch the terminal.
            No file rotation complexity needed during local development.

            WHAT HAPPENS IF REMOVED: No output at all in dev profile.
            You will see a startup warning: "No appenders could be found for logger".
        -->
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <!--
                    Pattern encoder: Formats each log event into a string.
                    Each % token is replaced with actual data from the log event.

                    %d{yyyy-MM-dd HH:mm:ss.SSS}
                        → Timestamp. ISO format, millisecond precision.
                          WHY: Millisecond precision is critical for ordering events
                          during incident investigation. Second-level precision loses
                          events that happened in the same second.

                    %highlight(%-5level)
                        → Log level: INFO, DEBUG, WARN, ERROR (padded to 5 chars).
                          %highlight adds ANSI color codes: ERROR=red, WARN=yellow, INFO=green.
                          WHY %-5: Padding aligns all log lines so they're readable.
                          WHAT HAPPENS IF REMOVED: All levels appear in same color.
                          The -5 means left-aligned, padded to 5 chars.

                    [%cyan(${APP_NAME})]
                        → Service name in cyan color.
                          WHY: When you have multiple services printing to the same
                          terminal (docker-compose logs), this visually separates them.

                    [traceId=%X{traceId}]
                        → %X{key} reads from MDC (Mapped Diagnostic Context).
                          traceId is put into MDC by MdcRequestFilter for every request.
                          WHAT HAPPENS IF MDC IS EMPTY: Prints "[traceId=]" — empty but
                          present. This is fine for startup logs before any request arrives.
                          WHAT HAPPENS IF %X{traceId} IS REMOVED: You lose request
                          correlation — cannot trace a single request across log lines.

                    [%thread]
                        → Name of the thread writing this log.
                          WHY: In a thread pool, knowing which thread helps correlate
                          logs when MDC is not set (e.g., startup logs, background jobs).
                          For Tomcat: "http-nio-8082-exec-3" tells you it's request thread 3.

                    %logger{40}
                        → Fully qualified class name of the Logger, truncated to 40 chars.
                          Example: com.example.myservice.OrderService
                          becomes: c.e.m.OrderService (abbreviated).
                          WHY 40: Balance between readability and line length.

                    %msg
                        → The actual message passed to log.info("..."), log.error("...") etc.

                    %n
                        → Platform-specific newline character (\n on Linux, \r\n on Windows).

                    %ex
                        → Full exception stack trace, if an exception was passed as the
                          last argument to the log call: log.error("Failed", ex).
                          WHAT HAPPENS IF REMOVED: Stack traces don't appear in logs.
                          You'll see "Failed" but not WHERE it failed.
                -->
                <pattern>
                    %d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [%cyan(${APP_NAME})] [traceId=%X{traceId}] [%thread] %logger{40} - %msg%n%ex
                </pattern>
            </encoder>
        </appender>

        <!--
            root: The root logger. Every logger in the application inherits from this.
            level="INFO" means: print INFO, WARN, ERROR. Suppress TRACE and DEBUG.

            WHY INFO in dev root: Spring Boot internally logs hundreds of DEBUG messages
            (SQL queries, security filter chain, request mappings, bean initialization).
            Setting root to DEBUG would flood your console with Spring internals,
            making your own application logs impossible to find.

            WHAT HAPPENS IF LEVEL IS DEBUG: Startup alone produces thousands of log
            lines from Hibernate, Spring MVC, HikariCP, Tomcat — all before your first
            request arrives.
        -->
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>

        <!--
            Package-specific logger override.
            Replace "com.yourpackage" with your actual base package.

            level="DEBUG": Show DEBUG logs for YOUR code only.
            additivity="false": Do NOT also send these logs to the root appender.
                                 Without this, every log line from your package would
                                 appear TWICE — once here, once via root inheritance.

            WHAT HAPPENS IF additivity IS NOT SET (defaults to true):
            Every log from com.yourpackage appears twice in the console.
            This is a very common mistake that causes confusion.

            ADAPT FOR YOUR PROJECT: Replace "com.yourpackage" with:
            - MVC project:       com.example.myapp
            - Hexagonal project: com.example.myapp.domain (the domain package)
            - Multi-module:      com.example (covers all sub-packages)
        -->
        <logger name="com.yourpackage" level="DEBUG" additivity="false">
            <appender-ref ref="CONSOLE"/>
        </logger>

    </springProfile>


    <!-- ====================================================================
         PROD PROFILE CONFIGURATION
         Active when: spring.profiles.active=prod
         Output: Structured JSON written to a rolling file, asynchronously
         Use for: Production servers, Kubernetes pods, Docker containers
    ===================================================================== -->
    <springProfile name="prod">

        <!--
            RollingFileAppender: Writes to a file. Rotates the file based on
            a rolling policy (daily rotation here).

            file="logs/${APP_NAME}.log"
                → The active log file path. ${APP_NAME} is replaced with
                  spring.application.name (e.g., "logs/certificate-service.log").
                  WHY use APP_NAME: In multi-service deployments on the same host,
                  each service writes to its own file, not "logs/application.log".

            WHAT HAPPENS IF THIS APPENDER IS REMOVED: No log file in production.
            DevOps has no logs to collect. Incident diagnosis is impossible.
        -->
        <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>logs/${APP_NAME}.log</file>

            <!--
                TimeBasedRollingPolicy: Creates a new log file every day.
                The old file is compressed (.gz) and archived.

                fileNamePattern="logs/${APP_NAME}.%d{yyyy-MM-dd}.log.gz"
                    → Archived files named: certificate-service.2024-07-26.log.gz
                      %d{yyyy-MM-dd} = date of the log.

                maxHistory=30
                    → Keep at most 30 archived log files (30 days of history).
                      Files older than 30 days are automatically DELETED.
                      WHY: Log files grow forever if not cleaned. Disk fills up.
                      WHAT HAPPENS IF REMOVED: Unlimited file accumulation → disk full
                      → application crashes or slows down → incident.
                      ADAPT: Change based on your compliance requirements.
                      Financial/govt systems often require 90 days minimum.

                totalSizeCap=1GB
                    → Even within 30 days, total log storage never exceeds 1GB.
                      If 1GB is reached, oldest files are deleted first.
                      WHY: In high-traffic systems, even 30 days could be > 1GB.
                      WHAT HAPPENS IF REMOVED: 30 days × high volume = potential disk full.
                      ADAPT: Set based on your disk capacity and log volume.
                      For high-traffic (1000+ RPS): consider 500MB.
                      For low-traffic: 2-5GB is safe.
            -->
            <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                <fileNamePattern>logs/${APP_NAME}.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
                <maxHistory>30</maxHistory>
                <totalSizeCap>1GB</totalSizeCap>
            </rollingPolicy>

            <!--
                LogstashEncoder: Formats each log event as a JSON object.
                This is from the logstash-logback-encoder library we added in pom.xml.

                Without this library, you'd need a PatternLayoutEncoder (plain text).
                With this, every log line is valid JSON that Loki/ELK can parse natively.

                WHAT HAPPENS IF THIS ENCODER IS REMOVED (and you don't add another):
                RollingFileAppender with no encoder = startup error.
                You MUST have exactly one encoder per appender.

                WHAT HAPPENS IF YOU USE PatternLayoutEncoder INSTEAD:
                Plain text logs → DevOps must configure parsing rules in Fluent Bit
                or Logstash to extract fields. Much harder to maintain.
            -->
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">

                <!--
                    customFields: Static JSON fields added to EVERY log line.
                    This is metadata that never changes per log event.

                    "service":"${APP_NAME}"
                        → Every log line includes: "service": "certificate-service"
                          WHY: When DevOps queries Loki/ELK across hundreds of services,
                          they need to filter by service name. Without this, all services'
                          logs mix together with no way to separate them.
                          WHAT HAPPENS IF REMOVED: DevOps cannot filter logs by service.
                          All services' logs look identical in the aggregation tool.

                    ADAPT FOR YOUR PROJECT: Add any other static metadata:
                    {"service":"${APP_NAME}","team":"platform","env":"prod"}
                -->
                <customFields>{"service":"${APP_NAME}"}</customFields>

                <!--
                    includeMdcKeyName: Pull a specific MDC key out of the MDC map
                    and include it as a TOP-LEVEL JSON field in every log line.

                    WHY TOP-LEVEL MATTERS: If MDC fields are nested inside a sub-object,
                    Loki's LogQL and Elasticsearch's query DSL make it harder to filter.
                    Top-level means: { "traceId": "abc123", "level": "INFO", ... }
                    Nested means:    { "mdc": { "traceId": "abc123" }, "level": "INFO" }
                    Top-level is faster to query and more compatible with alerting rules.

                    traceId  → set by MdcRequestFilter — links ALL logs of ONE request
                    citizenId/userId → set by MdcRequestFilter or your service layer
                    district → domain-specific context (adapt/remove for your project)
                    requestId → unique ID for this specific HTTP request

                    WHAT HAPPENS IF AN includeMdcKeyName IS REMOVED:
                    That field is still in MDC but NOT exported to the JSON output.
                    DevOps cannot filter by it. Internal correlation still works, but
                    log aggregation tools can't see it.

                    WHAT HAPPENS IF ALL includeMdcKeyName ARE REMOVED:
                    MDC fields appear in a generic "mdc" object in the JSON instead
                    of as top-level fields. Still there, but harder to query.

                    ADAPT FOR YOUR PROJECT:
                    Add any domain-specific identifiers your team needs to filter by.
                    Remove fields that don't apply (e.g., "district" for a payment service).
                    Common ones to always keep: traceId, requestId, userId
                -->
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>requestId</includeMdcKeyName>
                <includeMdcKeyName>userId</includeMdcKeyName>
                <!-- Add your domain-specific MDC keys below: -->
                <!-- <includeMdcKeyName>customerId</includeMdcKeyName> -->
                <!-- <includeMdcKeyName>orderId</includeMdcKeyName>    -->

            </encoder>
        </appender>

        <!--
            AsyncAppender: Wraps JSON_FILE appender to make logging non-blocking.

            HOW IT WORKS:
            - Application thread calls log.info("...") → puts LoggingEvent into a queue → returns IMMEDIATELY
            - A single background I/O thread drains the queue → writes to JSON_FILE
            - No application thread ever waits for disk I/O

            WITHOUT AsyncAppender (synchronous):
            Every log.info() call blocks until the OS confirms the write.
            SSD write latency: 0.1–1ms. With 50 log calls/request at 1000 RPS:
            50 × 0.5ms = 25ms of pure I/O wait added to every request's response time.
            This directly degrades your p99 latency.

            WHAT HAPPENS IF ASYNC_JSON_FILE IS REMOVED AND ROOT USES JSON_FILE DIRECTLY:
            Logging becomes synchronous. Your latency increases under load.
            Fine for low-traffic applications (< 100 RPS). Problematic above that.

            queueSize=2048
                → The queue can hold 2048 unwritten log events.
                  If the queue fills up (I/O can't keep up), the application thread
                  blocks until there's space (controlled by discardingThreshold).
                  WHY 2048: Empirically safe for most enterprise applications.
                  For very high throughput, increase to 8192 or 16384.
                  WHAT HAPPENS IF TOO SMALL: Under traffic spikes, queue fills → threads block
                  → back to synchronous-like behavior → latency spikes.

            discardingThreshold=0
                → 0 means: NEVER drop log events, even when queue is near full.
                  Default value is 20, which means: when queue is 80% full,
                  Logback SILENTLY DROPS all TRACE, DEBUG, INFO events.
                  WHY THIS IS DANGEROUS WITH DEFAULT: During an incident, you add verbose
                  DEBUG logging. Traffic is high. Queue fills. Your DEBUG logs silently
                  vanish — exactly when you need them most.
                  WHAT HAPPENS IF LEFT AT DEFAULT (20): Incident investigation fails
                  because critical debug logs were silently dropped.
                  ALWAYS SET TO 0 IN PRODUCTION.
        -->
        <appender name="ASYNC_JSON_FILE" class="ch.qos.logback.classic.AsyncAppender">
            <queueSize>2048</queueSize>
            <discardingThreshold>0</discardingThreshold>
            <appender-ref ref="JSON_FILE"/>
        </appender>

        <root level="INFO">
            <appender-ref ref="ASYNC_JSON_FILE"/>
        </root>

        <!--
            Suppress noisy Spring/library framework logs in production.
            These libraries log at DEBUG/INFO constantly — you don't need that in prod.
            Setting them to WARN means only actual problems surface.

            WHAT HAPPENS IF REMOVED: Framework INFO logs mix with your business logs,
            increasing storage cost and making incident queries slower (more noise).

            ADAPT: Add any other chatty library packages here.
            Common ones: io.lettuce (Redis), org.apache.kafka, com.amazonaws
        -->
        <logger name="org.springframework" level="WARN"/>
        <logger name="org.hibernate"       level="WARN"/>
        <logger name="com.zaxxer.hikari"   level="WARN"/>

    </springProfile>

</configuration>
```

---

## 5. Step 4 — Create MdcRequestFilter

**Location**: Create in a `filter` package inside your base package.

- MVC architecture:       `com.example.myapp.filter.MdcRequestFilter`
- Hexagonal architecture: `com.example.adapter.in.web.filter.MdcRequestFilter`
  (it is an inbound adapter concern — HTTP layer)

```java
package com.yourpackage.filter; // ADAPT: change to your base package

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/*
 * MdcRequestFilter
 * ================
 * PURPOSE: Attach request-scoped diagnostic context to every log line automatically.
 *
 * HOW IT WORKS:
 * This is a Servlet Filter — it runs on EVERY incoming HTTP request before any
 * Spring controller, security filter, or interceptor processes it.
 *
 * It puts key-value pairs into MDC (Mapped Diagnostic Context), which is a
 * ThreadLocal<Map<String, String>> inside SLF4J/Logback.
 *
 * Because it's ThreadLocal: every log statement on the same thread (the request
 * handling thread) automatically gets these values included in the output —
 * without passing them as method arguments.
 *
 * WHY A FILTER AND NOT AN INTERCEPTOR?
 * Filters (jakarta.servlet.Filter) run earlier in the pipeline than Spring
 * HandlerInterceptors. By setting MDC in a Filter, even Spring Security logs,
 * Spring MVC dispatch logs, and exception handler logs get the traceId.
 * An Interceptor would miss logs from the security layer and error dispatch.
 *
 * @Order(1): This filter runs first, before any other filter.
 * WHY: MDC must be populated before any other filter logs anything.
 * If another filter runs first and logs, those log lines won't have traceId.
 *
 * @Component: Registers this as a Spring bean, which automatically registers
 * it as a Servlet filter via FilterRegistrationBean auto-detection.
 * WHY NOT FilterRegistrationBean: @Component is simpler. Use FilterRegistrationBean
 * only if you need to specify URL patterns or control the filter order more precisely.
 */
@Component
@Order(1)
public class MdcRequestFilter implements Filter {

    /*
     * Header name constants
     * =====================
     * These are the HTTP header names we READ from the incoming request.
     *
     * X-Trace-Id:
     *   Standard header used by API Gateways (Kong, NGINX, AWS ALB) to pass a
     *   trace ID that spans multiple services. If the upstream system generates
     *   this, we use it — this is how cross-service log correlation works.
     *   Example: API Gateway generates traceId=abc123, passes it to Service A,
     *   Service A passes it to Service B. All three services log with same traceId.
     *
     * X-Request-Id:
     *   Unique ID for this specific HTTP request (not the same as traceId).
     *   traceId spans a business transaction across services.
     *   requestId identifies one specific HTTP call.
     *
     * X-User-Id:
     *   Set by your auth service / API Gateway after validating the JWT.
     *   The filter reads it so every log line includes who made the request.
     *   WHY HERE and not in security layer: Filter runs before Spring Security
     *   processes the JWT, so we read from the header directly.
     *
     * ADAPT FOR YOUR PROJECT:
     *   If using Keycloak: user ID is in "X-Keycloak-User-Id" or extract from JWT.
     *   If using custom auth: match the header your API Gateway sets.
     *   If not using a gateway: generate all IDs locally (as done below).
     */
    private static final String TRACE_ID_HEADER   = "X-Trace-Id";
    private static final String REQUEST_ID_HEADER  = "X-Request-Id";
    private static final String USER_ID_HEADER     = "X-User-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        /*
         * traceId resolution strategy:
         * 1. If upstream (API Gateway) already set X-Trace-Id header → use it
         *    (enables cross-service correlation — all services share same traceId)
         * 2. If not present → generate a new short ID for this service's logs
         *
         * WHY SHORT UUID (16 chars not 36):
         * Full UUID: "3f8a9c2d-e1b4-4a2c-8f71-9d2e3c4b5a6f" (36 chars)
         * Short:     "3f8a9c2de1b44a2c"                      (16 chars)
         * In high-volume logs, shorter IDs reduce storage cost.
         * Still unique enough for correlation within a single service.
         * If using distributed tracing (OpenTelemetry/Jaeger), they will
         * inject their own W3C TraceContext header — use that instead.
         */
        String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        String userId = httpRequest.getHeader(USER_ID_HEADER);

        try {
            /*
             * MDC.put(key, value)
             * ===================
             * Writes to the ThreadLocal map for the current request thread.
             * From this point, every log.info(), log.debug(), log.error() call
             * on this thread will include "traceId=xxx" in the output automatically.
             *
             * The keys used here MUST MATCH:
             * 1. The %X{keyName} tokens in logback-spring.xml pattern (dev profile)
             * 2. The <includeMdcKeyName>keyName</includeMdcKeyName> in prod profile
             *
             * If you add a new MDC key here, add it in logback-spring.xml too.
             * If you remove a key here, remove its <includeMdcKeyName> from logback-spring.xml.
             *
             * ADAPT FOR YOUR PROJECT:
             * Add domain-specific context that your team needs in every log:
             *   MDC.put("tenantId",    extractTenantId(httpRequest));
             *   MDC.put("customerId",  httpRequest.getHeader("X-Customer-Id"));
             *   MDC.put("sessionId",   httpRequest.getSession(false)?.getId());
             */
            MDC.put("traceId",   traceId);
            MDC.put("requestId", requestId);

            if (userId != null && !userId.isBlank()) {
                /*
                 * userId is set only if present — not all endpoints are authenticated.
                 * Health checks (/actuator/health), public APIs won't have this header.
                 * Setting it conditionally avoids "userId=null" noise in logs.
                 */
                MDC.put("userId", userId);
            }

            /*
             * chain.doFilter() passes the request to the next filter in the chain,
             * and ultimately to the DispatcherServlet → Controller → Service.
             *
             * Everything that happens between this call and its return runs on
             * the SAME THREAD, so MDC values are available throughout.
             */
            chain.doFilter(request, response);

        } finally {
            /*
             * MDC.clear() — THE MOST CRITICAL LINE IN THIS FILE
             * ===================================================
             * WHY finally: Guarantees this runs even if an exception propagates
             * through chain.doFilter(). Without finally, an unhandled exception
             * would skip this line.
             *
             * WHY CLEAR IS MANDATORY:
             * Tomcat and Jetty use a thread pool. After this request is handled,
             * the thread returns to the pool and handles the NEXT request.
             *
             * WITHOUT MDC.clear():
             * Thread-7 handles Request A (userId=user-42) → MDC has userId=user-42
             * Thread-7 returns to pool
             * Thread-7 handles Request B (no auth header) → filter doesn't set userId
             * But MDC STILL HAS userId=user-42 from Request A!
             * Request B's logs will falsely show userId=user-42
             * → Security incident: wrong user appears in audit logs
             * → Compliance violation in financial/government systems
             *
             * WHAT HAPPENS IF THIS LINE IS REMOVED:
             * - Stale MDC context leaks between requests
             * - False attribution in audit logs (serious compliance issue)
             * - Difficult to reproduce — depends on thread pool scheduling
             * - One of the hardest bugs to diagnose in production
             */
            MDC.clear();
        }
    }
}
```

---

## 6. Step 5 — Use Logging Correctly in Your Code

### In any class (Controller, Service, Repository, Component)

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CertificateService {

    /*
     * Logger declaration rules:
     *
     * private — no other class should use this class's logger
     * static  — one Logger instance per CLASS, not per object instance.
     *            Without static: every new CertificateService() creates a new Logger.
     *            At 1000 RPS, that's potentially 1000 Logger objects for the same class.
     *            Logger creation is not cheap — it involves hierarchy lookup.
     * final   — prevents accidental reassignment (logger = null → NullPointerException)
     *
     * LoggerFactory.getLogger(ThisClass.class)
     *    → Creates/retrieves Logger named after the fully qualified class name.
     *      This name is used in the logger hierarchy for level inheritance.
     *      Always pass the CURRENT class, not a parent or some other class.
     *      Passing the wrong class makes log filtering by package name unreliable.
     */
    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);

    public Certificate issueCertificate(String citizenId, String type) {

        /*
         * INFO level: Normal business operations that always happen.
         * Log at entry of significant operations — helps reconstruct timeline.
         *
         * Parameterized logging: log.info("msg {}", value)
         * WHY NOT string concatenation ("msg " + value):
         *   - If INFO is disabled, "msg " + value still evaluates and allocates a String
         *   - Parameterized: the concatenation ONLY happens if INFO is enabled
         *   - At 10,000 RPS with 10 log calls/request = 100,000 wasted allocations/second
         *   - GC pressure → stop-the-world pauses → latency spikes
         *
         * ADAPT: Log the identifiers that matter for your domain.
         * The goal: if this log line is the ONLY thing you see, can you identify
         * WHAT happened and FOR WHOM? If yes, the log is good.
         */
        log.info("Issuing certificate. citizenId={}, type={}", citizenId, type);

        try {
            Certificate cert = generateCertificate(citizenId, type);

            /*
             * INFO level on success: confirms operation completed.
             * Include the output ID — useful for "where is my certificate?" queries.
             */
            log.info("Certificate issued successfully. certId={}, citizenId={}", cert.getId(), citizenId);
            return cert;

        } catch (PdfGenerationException e) {
            /*
             * ERROR level: Something failed that needs engineer attention.
             * The third argument (e) is the Exception — Logback automatically
             * appends the full stack trace after the message.
             *
             * WHY pass the exception as argument (not e.getMessage()):
             * e.getMessage() gives you "Font file not found"
             * passing e gives you the full stack trace — you know WHICH line threw it.
             * In production diagnosis, the stack trace is the difference between
             * 5 minutes and 2 hours to find the root cause.
             *
             * WRONG:  log.error("PDF generation failed: " + e.getMessage());
             *          → loses stack trace, does string concat unconditionally
             * RIGHT:  log.error("PDF generation failed. citizenId={}", citizenId, e);
             *          → structured, parameterized, full stack trace
             */
            log.error("PDF generation failed. citizenId={}, type={}", citizenId, type, e);
            throw e;

        } catch (DatabaseException e) {
            log.error("Database error during certificate issuance. citizenId={}", citizenId, e);
            throw e;
        }
    }

    /*
     * DEBUG level: Detailed internal steps. Only visible when that package's
     * log level is set to DEBUG in application properties.
     * Never visible in production (root=INFO, your package=INFO).
     * Use for cache hits/misses, algorithm steps, internal state.
     */
    private Certificate generateCertificate(String citizenId, String type) {
        log.debug("Generating PDF for citizenId={}, type={}", citizenId, type);
        // ... implementation
    }
}
```

### Adding domain-specific MDC context inside a service

Sometimes you want to add more context mid-request — for example, after looking up
an entity from the DB, you now know the `orderId` or `districtCode`.

```java
public Certificate issueCertificate(String citizenId, String type) {

    // Load entity from DB
    CitizenProfile profile = citizenRepository.findById(citizenId)
        .orElseThrow(() -> new ResourceNotFoundException("Citizen not found"));

    /*
     * Add district to MDC AFTER loading it from DB.
     * From this point, ALL subsequent log lines in this request (even in
     * called services) will include district automatically.
     *
     * WHY ADD MID-REQUEST: You don't always have all context at filter level.
     * The filter only sees HTTP headers. Domain context (district, account type,
     * subscription tier) comes from your DB or domain layer.
     *
     * IMPORTANT: You do NOT need to remove this — MdcRequestFilter's
     * MDC.clear() in the finally block clears EVERYTHING at request end.
     */
    MDC.put("district", profile.getDistrict());

    log.info("Processing certificate for verified citizen. district={}", profile.getDistrict());
    // ...
}
```

### In Exception Handlers (`@ControllerAdvice`)

```java
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        /*
         * WARN not ERROR for 404s.
         * WHY: A 404 means the client asked for something that doesn't exist.
         * This is the CLIENT's fault, not the server's fault.
         * If you log 404s as ERROR, your error rate metric spikes every time
         * a client sends a bad request — masking real server errors.
         *
         * Rule of thumb:
         * ERROR = Something broke on the SERVER side (DB down, NPE, config missing)
         * WARN  = Something unexpected but handled (404, 400, rate limit exceeded)
         * INFO  = Normal operations
         */
        log.warn("Resource not found. message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        /*
         * ERROR for 5xx — genuine server failures.
         * The exception (ex) as last argument → full stack trace in the log.
         *
         * NEVER use ex.printStackTrace() here.
         * WHY: printStackTrace() writes to System.err (separate stream),
         * bypasses SLF4J entirely, loses MDC context, and produces unstructured
         * text that log aggregators cannot parse.
         */
        log.error("Unhandled exception. type={}", ex.getClass().getSimpleName(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("Internal server error"));
    }
}
```

---

## 7. Verification Checklist

After setup, run the application and verify each item:

```
□ Application starts without errors mentioning "logback" or "encoder"

□ DEV: Console output shows colored log lines with traceId field
  Example: 2024-07-26 15:14:41 INFO  [my-service] [traceId=3f8a9c2da1b2c3d4] ...

□ Make an HTTP request and verify traceId appears in ALL log lines for that request
  (Controller log AND Service log should have the same traceId)

□ Make a request WITHOUT X-Trace-Id header → traceId should be auto-generated (not empty)

□ Make a request WITH X-Trace-Id: my-custom-trace → that exact value appears in logs

□ Trigger a 404 → logged as WARN (not ERROR)

□ Trigger a 500 (e.g., force a NullPointerException) → logged as ERROR with stack trace

□ Stack trace appears inline in the log, not on a separate line (no ex.printStackTrace output on stderr)

□ PROD profile test: run with -Dspring.profiles.active=prod
  → logs/your-service-name.log file is created
  → File contains valid JSON (one JSON object per line)
  → JSON contains: timestamp, level, message, service, traceId fields
```

**Verify JSON output** (prod profile):
```bash
# Run app with prod profile, make a request, then:
cat logs/your-service-name.log | python -m json.tool
# Should pretty-print without errors — confirms valid JSON
```

---

## 8. Common Mistakes and What Goes Wrong

| Mistake | Symptom | Fix |
|---------|---------|-----|
| `logback.xml` instead of `logback-spring.xml` | `<springProfile>` has no effect, `${spring.application.name}` prints literally | Rename file to `logback-spring.xml` |
| Forgot `MDC.clear()` in filter | Wrong traceId/userId appears in logs after first request | Add `try/finally` block, `MDC.clear()` in finally |
| `additivity=true` (default) on package logger | Every log line appears twice in console | Add `additivity="false"` to your `<logger>` tag |
| `discardingThreshold` not set (defaults to 20) | DEBUG/INFO logs vanish under load during incidents | Add `<discardingThreshold>0</discardingThreshold>` |
| `ex.printStackTrace()` in exception handler | Stack traces go to stderr, missing from log files, no MDC context | Replace with `log.error("msg", ex)` |
| Non-static Logger: `private Logger log = ...` | New Logger object per class instance — memory waste | Make it `private static final Logger log = ...` |
| String concatenation: `log.info("x=" + x)` | String allocated even when log level is disabled → GC pressure | Use `log.info("x={}", x)` parameterized form |
| Wrong class in `getLogger()`: `getLogger(Object.class)` | Logger hierarchy broken — level config for your package has no effect | Always use `getLogger(ThisClass.class)` |
| Logging PII (Aadhaar, phone, bank account) | Compliance violation, legal liability | Mask before logging: `maskAadhaar(aadhaar)` |
| ERROR level for 4xx client errors | False error rate spike in metrics, alert fatigue | Use WARN for 4xx, ERROR only for 5xx |

---

## 9. Architecture Adaptation Notes

### Standard Layered (MVC) Architecture

```
filter package location: com.example.app.filter.MdcRequestFilter
Logger in:               Controller, ServiceImpl, Repository implementations
MDC domain context:      Add in ServiceImpl after loading entity from DB
```

### Hexagonal Architecture

```
filter location: com.example.adapter.in.web.filter.MdcRequestFilter
                 (it is an inbound adapter concern — web/HTTP layer)
Logger in:       Inbound adapter (Controller) — for HTTP-level context
                 Application service — for business operation logging
                 Outbound adapter — for persistence/external call logging
                 Domain model — NO logging (pure domain, no infrastructure)
MDC domain context: Add in Application Service after domain object is loaded
```

### Multi-Module Gradle/Maven Projects

```
logback-spring.xml: Place in the module that contains the Spring Boot main class
                    (the runnable module / boot module)
MdcRequestFilter:   Same module as logback-spring.xml OR a shared web-commons module
Dependencies:       Add logstash-logback-encoder to the boot module's build file
                    (or to a shared BOM if you have one)
Logger hierarchy:   Configure per-module package in logback-spring.xml:
                    <logger name="com.example.certificate" level="DEBUG" .../>
                    <logger name="com.example.payment"     level="DEBUG" .../>
```

### Replacing the Logging Implementation (Log4j2 instead of Logback)

If your project uses Log4j2 instead of Logback (hexagonal setup or high-throughput):
1. Exclude `spring-boot-starter-logging` from `spring-boot-starter`
2. Add `spring-boot-starter-log4j2`
3. Replace `logback-spring.xml` with `log4j2-spring.xml`
4. Replace `LogstashEncoder` with `JsonLayout` (Log4j2 built-in) or keep logstash encoder with Log4j2 adapter
5. `MdcRequestFilter` code is IDENTICAL — MDC is an SLF4J API, same for both

---

*Document maintained alongside codebase. Update whenever logging configuration changes.*
