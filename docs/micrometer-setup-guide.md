# Production Micrometer & Prometheus Setup Guide
### Reusable across any Spring Boot project — Maven or Gradle, MVC or Hexagonal

> **What this document is**: A step-by-step implementation guide for application telemetry, custom business metrics,
> percentile latency tracking (p50/p95/p99), and Prometheus integration using Micrometer in Spring Boot 3.x.
> Every dependency, property, and code block is explained with structural rationale and failure impact.
>
> **Who should follow this**: Backend developers adding metrics to services, AI assistants automating instrumentation,
> and SREs setting up Service Level Objectives (SLOs) and alerting rules.

---

## Table of Contents

1. [Conceptual Overview — Read Before Coding](#1-conceptual-overview--read-before-coding)
2. [Step 1 — Add Dependencies](#2-step-1--add-dependencies)
3. [Step 2 — Configure application.properties](#3-step-2--configure-applicationproperties)
4. [Step 3 — Implement Custom Business Metrics (Counters & Timers)](#4-step-3--implement-custom-business-metrics-counters--timers)
5. [Step 4 — Understand Metric Cardinality & Tagging Rules](#5-step-4--understand-metric-cardinality--tagging-rules)
6. [Step 5 — Prometheus Scraping & PromQL Integration](#6-step-5--prometheus-scraping--promql-integration)
7. [Verification Checklist](#7-verification-checklist)
8. [Common Mistakes and What Goes Wrong](#8-common-mistakes-and-what-goes-wrong)
9. [Architecture Adaptation Notes](#9-architecture-adaptation-notes)

---

## 1. Conceptual Overview — Read Before Coding

While logging records **discrete events** ("User X created Product Y at 10:04 AM"), metrics record **aggregatable numeric data over time** ("We are currently creating 45 products per second with a 95th percentile latency of 120ms").

### Micrometer is the "SLF4J of Metrics"
Just as SLF4J decouples your code from Logback or Log4j2, **Micrometer** decouples your instrumentation code from the backend monitoring system (Prometheus, Datadog, CloudWatch, New Relic). You instrument your code once using Micrometer interfaces; changing the telemetry backend only requires changing a dependency and property configuration.

```
Application Code (Service / Controller Layer)
     │
     ├─► Counter.increment() ──┐
     ├─► Timer.record() ───────┼─► MeterRegistry (In-Memory Core)
     └─► Gauge.builder() ──────┘         │
                                         ▼
                             PrometheusMeterRegistry
                                         │
                                         ▼ (Scraped every 15s via HTTP GET)
                             Prometheus Server (/actuator/prometheus)
                                         │
                                         ▼
                             Grafana Dashboards & AlertManager
```

---

## 2. Step 1 — Add Dependencies

### For Maven projects (`pom.xml`)

```xml
<!--
  1. spring-boot-starter-actuator
  WHY: Required as the base host for all metrics endpoints and auto-configuration.
-->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!--
  2. micrometer-registry-prometheus
  =================================
  WHY: Micrometer collects metrics in memory. This dependency translates those in-memory metrics
  into the Prometheus Text Exposition Format and exposes them at /actuator/prometheus.
  
  WHAT HAPPENS IF REMOVED: Your custom counters and timers will still compile and run in memory,
  but Prometheus server will receive a 404 Not Found when trying to scrape /actuator/prometheus.
-->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### For Gradle projects (`build.gradle` — Groovy DSL)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    // Prometheus registry bridge for Micrometer
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

### For Gradle projects (`build.gradle.kts` — Kotlin DSL)

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
}
```

---

## 3. Step 2 — Configure application.properties

### `src/main/resources/application-dev.properties` (or common properties)

```properties
# =============================================================================
# MICROMETER & PROMETHEUS METRICS CONFIGURATION
# =============================================================================

# management.metrics.tags.application
# → Attaches a global tag 'application="your-app-name"' to every single metric emitted.
# → WHY CRITICAL: In a microservices ecosystem where Prometheus scrapes 50 different services,
#   this tag allows PromQL queries to filter metrics by service: http_server_requests_seconds_count{application="product-service"}.
# → WHAT HAPPENS IF REMOVED: All metrics from all services pool together anonymously in Prometheus.
management.metrics.tags.application=${spring.application.name}

# Enforce client-side percentile computation (p50, p95, p99) for HTTP request timers
# → Why not average? Average latency conceals tail-latency spikes. If 99 requests take 10ms and 1 takes 5,000ms,
#   the average is ~60ms (looks fine), but your 99th percentile (p99) is 5,000ms (reveals the outage).
management.metrics.distribution.percentiles.http.server.requests=0.5,0.95,0.99

# Publish histogram buckets for Prometheus server-side quantile computation
# → Allows using PromQL histogram_quantile() across aggregated multi-instance pods.
management.metrics.distribution.percentiles-histogram.http.server.requests=true

# Ensure Prometheus endpoint is exposed in Actuator web configuration
# In PROD, ensure this is restricted to internal monitoring VLANs or scrapers.
management.endpoints.web.exposure.include=health,info,metrics,prometheus
```

---

## 4. Step 3 — Implement Custom Business Metrics (Counters & Timers)

Avoid relying solely on generic HTTP request metrics. Production observability requires understanding business throughput and domain-specific failure modes.

### Best Practice: Pre-build meters in the Constructor
Do not call `Counter.builder(...).register(registry)` inside hot method loops. While registry lookups are cached, pre-building metrics in the constructor guarantees zero allocation overhead during user requests.

```java
package com.yourpackage.service; // ADAPT: change to your base package

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ProductServiceImpl implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ProductRepository productRepository;
    private final MeterRegistry meterRegistry;

    // 1. Counters: Monotonically increasing values (never decrease except on app restart)
    private final Counter productsCreatedCounter;
    private final Counter productsDeletedCounter;
    private final Counter productNotFoundCounter;

    // 2. Timers: Track execution duration, rate, and latency distributions (p50/p95/p99)
    private final Timer getByIdTimer;
    private final Timer saveTimer;

    /*
     * Constructor Injection
     * Spring automatically injects the auto-configured MeterRegistry bean.
     */
    public ProductServiceImpl(ProductRepository productRepository, MeterRegistry meterRegistry) {
        this.productRepository = productRepository;
        this.meterRegistry = meterRegistry;

        // Initialize Counters
        this.productsCreatedCounter = Counter.builder("products.created.total")
                .description("Total number of products successfully created")
                .register(meterRegistry);

        this.productsDeletedCounter = Counter.builder("products.deleted.total")
                .description("Total number of products successfully deleted")
                .register(meterRegistry);

        /*
         * Business Error Tracking Counter
         * Why track "not found" separately from HTTP 404 metrics?
         * HTTP 404 can occur from random bot scanners hitting unmapped URLs (/favicon.ico, /wp-admin).
         * This custom counter increments ONLY when a valid business API lookup fails to find a domain entity,
         * indicating potential data consistency bugs or stale client caches.
         */
        this.productNotFoundCounter = Counter.builder("products.not_found.total")
                .description("Total number of product lookups that failed to find an entity")
                .register(meterRegistry);

        // Initialize Timers with Percentiles
        this.getByIdTimer = Timer.builder("product.operation.duration")
                .description("Duration of database product lookup operations")
                .tag("operation", "getById") // Dimension tagging
                .publishPercentiles(0.5, 0.95, 0.99) // Enforce p50, p95, p99 tracking
                .register(meterRegistry);

        this.saveTimer = Timer.builder("product.operation.duration")
                .description("Duration of product persistence operations")
                .tag("operation", "save")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    public Product getProductById(Long id) {
        /*
         * Timer.record(Supplier<T>)
         * Automatically starts timing, executes the lambda expression, records the elapsed time
         * into percentiles/histograms, and returns the result. Guarantees timing stops even if exceptions throw.
         */
        return getByIdTimer.record(() -> {
            return productRepository.findById(id)
                    .orElseThrow(() -> {
                        // Record business failure event before raising exception
                        productNotFoundCounter.increment();
                        log.warn("Product lookup failed. productId={}", id);
                        return new ProductNotFoundException("Product not found: " + id);
                    });
        });
    }

    @Override
    public Product saveProduct(Product product) {
        return saveTimer.record(() -> {
            Product saved = productRepository.save(product);
            // Increment creation counter only upon successful database persistence
            productsCreatedCounter.increment();
            log.info("Product created successfully. productId={}", saved.getId());
            return saved;
        });
    }

    @Override
    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
        productsDeletedCounter.increment();
        log.info("Product deleted successfully. productId={}", id);
    }
}
```

---

## 5. Step 4 — Understand Metric Cardinality & Tagging Rules

Tags (also called dimensions or labels) allow slicing and dicing metrics in Grafana dashboards (e.g., comparing latency of `operation="save"` vs `operation="getById"`).

### ⚠️ THE HIGH CARDINALITY TRAP (CRITICAL PRODUCTION RULE)
**Never use unbounded or unique identifiers as tag values!**

#### ❌ WRONG (Will crash Prometheus and exhaust JVM Heap):
```java
// DO NOT DO THIS!
Counter.builder("api.requests")
       .tag("userId", user.getId())       // Millions of unique IDs
       .tag("orderId", order.getOrderNum()) // Infinite unique values
       .tag("timestamp", Instant.now().toString()) // Infinite values
       .register(meterRegistry);
```
* **Why it breaks**: Every unique combination of tag values creates a **brand new time-series object** in memory. With 1,000,000 users, you create 1,000,000 metric objects in JVM memory, causing `OutOfMemoryError: Java heap space` and crashing the Prometheus database during scraping!

#### ✅ RIGHT (Bounded, enumerable tag values):
```java
Counter.builder("api.requests")
       .tag("status", "success")       // Only 2 possible values (success/failure)
       .tag("userRole", user.getRole()) // Only 3 possible values (ADMIN/USER/GUEST)
       .tag("region", "US-EAST")       // Only a few datacenter regions
       .register(meterRegistry);
```
* **Rule**: Total possible unique values for a tag should be finite and small (typically under 50 values across your entire application).

---

## 6. Step 5 — Prometheus Scraping & PromQL Integration

When Prometheus server hits `GET /actuator/prometheus`, Micrometer outputs text like this:

```prometheus
# HELP products_created_total Total number of products successfully created
# TYPE products_created_total counter
products_created_total{application="spring-boot-starter"} 45.0

# HELP product_operation_duration_seconds Duration of database product lookup operations
# TYPE product_operation_duration_seconds summary
product_operation_duration_seconds{application="spring-boot-starter",operation="getById",quantile="0.5"} 0.0084
product_operation_duration_seconds{application="spring-boot-starter",operation="getById",quantile="0.95"} 0.0421
product_operation_duration_seconds{application="spring-boot-starter",operation="getById",quantile="0.99"} 0.1852
product_operation_duration_seconds_count{application="spring-boot-starter",operation="getById"} 1240.0
product_operation_duration_seconds_sum{application="spring-boot-starter",operation="getById"} 14.82
```

### Essential PromQL Queries for DevOps / Grafana Dashboards:

#### 1. Product Creation Rate (Products per second over a 5-minute rolling window):
```promql
rate(products_created_total{application="spring-boot-starter"}[5m])
```

#### 2. Business Error Ratio (Percentage of lookups failing with Not Found):
```promql
sum(rate(products_not_found_total[5m])) 
/ 
sum(rate(product_operation_duration_seconds_count{operation="getById"}[5m])) * 100
```

#### 3. 95th Percentile Latency for Product Saves (in milliseconds):
```promql
product_operation_duration_seconds{operation="save", quantile="0.95"} * 1000
```

---

## 7. Verification Checklist

Execute these verifications against your running application:

```
□ Verify Prometheus text format endpoint is active and returning HTTP 200:
  curl -i http://localhost:8082/actuator/prometheus

□ Check if custom counters exist in output (should appear with 0.0 initial value or post-traffic count):
  curl -s http://localhost:8082/actuator/prometheus | grep "products_created_total"

□ Trigger API traffic to generate metric counts:
  curl -X POST http://localhost:8082/api/products -H "Content-Type: application/json" -d '{"name":"Test","price":99.9}'

□ Re-verify that counter incremented in Prometheus output:
  curl -s http://localhost:8082/actuator/prometheus | grep "products_created_total"

□ Check if percentiles (quantile="0.95") are generated for operation timers:
  curl -s http://localhost:8082/actuator/prometheus | grep "product_operation_duration_seconds"
```

---

## 8. Common Mistakes and What Goes Wrong

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Missing `micrometer-registry-prometheus` dependency | `/actuator/prometheus` returns 404 Not Found | Add `micrometer-registry-prometheus` to `pom.xml` / `build.gradle` |
| Putting User IDs or UUIDs in `.tag("id", uuid)` | JVM memory spikes; Prometheus server crashes due to high cardinality | Remove dynamic ID tags; only tag bounded categories (status, type, role) |
| Using `Counter` to measure active DB connections | Number keeps growing indefinitely without ever decreasing | Use `Gauge` instead of `Counter` for values that go up and down |
| Using Average latency instead of Percentiles | Dashboard shows 15ms avg, but users report timeouts | Configure and alert on `quantile="0.95"` and `quantile="0.99"` |
| Registering metrics repeatedly inside a loop | High CPU utilization and redundant memory allocation | Declare and register `Counter` / `Timer` once inside class constructor |

---

## 9. Architecture Adaptation Notes

### Standard Layered (MVC) Architecture
* **Instrumentation points**: Add custom Counters and Timers primarily inside `@Service` implementation classes where business rules execute.
* **Controller layer**: Rely on Spring Boot's automatic `http.server.requests` instrumentation; avoid writing manual timers in controllers unless measuring specific serialization overhead.

### Hexagonal / Ports & Adapters Architecture
* **Application Services (Use Cases)**: This is the **primary location** for business metrics (`products.created.total`, use case execution timers). The Application Service orchestrates domain logic and represents the true business boundary.
* **Outbound Adapters (Persistence / REST Clients)**: Add architectural Timers here to measure external latency (e.g., `adapter.payment.gateway.duration` or `adapter.db.query.duration`).
* **Domain Model (Pure Java Records/Entities)**: **ZERO METRICS HERE**. The domain layer must remain pure Java with no dependencies on Micrometer, Spring, or infrastructure libraries.

---

*Document maintained alongside codebase. Update whenever new business telemetry requirements are introduced.*
