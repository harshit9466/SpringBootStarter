package com.SpringBootStarter.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/*
 * DatabaseHealthIndicator — Custom Health Check
 * ==============================================
 * WHY THIS EXISTS:
 * Spring Boot's built-in DataSourceHealthIndicator already checks if the DB
 * connection is alive by running "SELECT 1". But in production, that is not
 * enough. We want to know:
 *   1. Can we actually READ data (not just connect)?
 *   2. Is the latency acceptable? (A DB that responds in 5 seconds is "up" but useless)
 *   3. Are there any business-level DB concerns (e.g., migration pending)?
 *
 * This custom indicator adds response-time measurement on top of connection check.
 *
 * HOW IT INTEGRATES WITH ACTUATOR:
 * Spring Boot auto-detects any bean implementing HealthIndicator.
 * The bean name ("databaseHealthIndicator") becomes the component key
 * in the /actuator/health response:
 *
 * {
 *   "status": "UP",
 *   "components": {
 *     "databaseHealthIndicator": {       ← this class
 *       "status": "UP",
 *       "details": { "responseTimeMs": 12, "query": "SELECT 1" }
 *     },
 *     "db": { ... },                     ← Spring Boot's built-in DB check
 *     "diskSpace": { ... }               ← Spring Boot's built-in disk check
 *   }
 * }
 *
 * KUBERNETES INTEGRATION:
 * Kubernetes liveness probe hits /actuator/health/liveness
 * Kubernetes readiness probe hits /actuator/health/readiness
 * If ANY HealthIndicator returns DOWN, the aggregate status becomes DOWN,
 * and Kubernetes may restart the pod (liveness) or stop routing traffic (readiness).
 *
 * ADAPT FOR YOUR PROJECT:
 * Replace the DB check with whatever your service depends on:
 * - External API: check if third-party service responds
 * - Kafka: check if broker is reachable
 * - Redis: check if cache is connected
 * - File system: check if required directory exists and is writable
 */
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    /*
     * Threshold in milliseconds above which we consider DB response "slow".
     * A slow DB is still UP but we report it as a warning in the details.
     * ADAPT: Set based on your DB's expected SLA.
     * Typical values: 100ms for same-datacenter, 500ms for cross-region.
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
             * Execute a lightweight query to verify DB connectivity and measure latency.
             * "SELECT 1" is the standard health-check query — no table access needed,
             * minimal DB load, works on PostgreSQL, MySQL, H2, Oracle.
             *
             * queryForObject returns the result — we discard it (just need no exception).
             */
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);

            long responseTimeMs = System.currentTimeMillis() - startTime;

            if (responseTimeMs > SLOW_QUERY_THRESHOLD_MS) {
                /*
                 * DB is reachable but responding slowly.
                 * Status is still UP — but we include the warning in details.
                 * DevOps can see this in Grafana and investigate before it becomes an outage.
                 *
                 * WHY NOT return DOWN here:
                 * A slow DB does not mean we should stop serving traffic immediately.
                 * DOWN would cause Kubernetes to stop routing requests — making things worse.
                 * Instead, log a warning and report it as a detail. Alerting rules in
                 * Grafana can trigger on "responseTimeMs > 200" separately.
                 */
                log.warn("Database responding slowly. responseTimeMs={}", responseTimeMs);

                return Health.up()
                        .withDetail("status", "SLOW")
                        .withDetail("responseTimeMs", responseTimeMs)
                        .withDetail("threshold", SLOW_QUERY_THRESHOLD_MS)
                        .withDetail("query", "SELECT 1")
                        .build();
            }

            log.debug("Database health check passed. responseTimeMs={}", responseTimeMs);

            /*
             * Health.up() — builds a Health object with status=UP.
             * withDetail() — adds key-value pairs visible in /actuator/health response.
             * These details help DevOps understand the health context without needing
             * to dig into logs.
             */
            return Health.up()
                    .withDetail("responseTimeMs", responseTimeMs)
                    .withDetail("query", "SELECT 1")
                    .build();

        } catch (Exception e) {
            long responseTimeMs = System.currentTimeMillis() - startTime;

            /*
             * ERROR level: DB is unreachable — this is a genuine service-level failure.
             * This will cause the aggregate health status to become DOWN,
             * which Kubernetes will detect and restart the pod.
             */
            log.error("Database health check failed. responseTimeMs={}", responseTimeMs, e);

            /*
             * Health.down() — status=DOWN.
             * withException() — includes the exception class and message in the response.
             * withDetail() — adds any additional context.
             *
             * WARNING: In production, exception details may reveal internal information.
             * Consider using withDetail("error", e.getMessage()) instead of withException(e)
             * if your /actuator/health endpoint is externally accessible.
             */
            return Health.down()
                    .withException(e)
                    .withDetail("responseTimeMs", responseTimeMs)
                    .withDetail("query", "SELECT 1")
                    .build();
        }
    }
}
