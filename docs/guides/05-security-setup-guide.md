# Production Spring Boot Security Setup Guide
### Reusable across any Spring Boot project — Maven or Gradle, MVC or Hexagonal

> **What this document is**: A step-by-step guide for securing a Spring Boot REST API using
> Spring OAuth2 Resource Server with Keycloak as the Identity Provider.
> Every file, every configuration line, and every architectural decision is explained — what it does,
> why it exists, and what breaks if you change it.
>
> **Who should follow this**: Backend engineers adding JWT security to any Spring Boot service,
> AI assistants replicating this setup in a new project, or engineers standardizing security
> across a multi-service system.

---

## Table of Contents

1. [Conceptual Overview — Read Before Coding](#1-conceptual-overview--read-before-coding)
2. [Step 1 — Add Dependencies](#2-step-1--add-dependencies)
3. [Step 2 — Configure Application Properties](#3-step-2--configure-application-properties)
4. [Step 3 — Copy the Three Core Security Files](#4-step-3--copy-the-three-core-security-files)
5. [Step 4 — Create SecurityConfig](#5-step-4--create-securityconfig)
6. [Step 5 — Use Security in Your Code](#6-step-5--use-security-in-your-code)
7. [Step 6 — Swagger Bearer Auth Integration](#7-step-6--swagger-bearer-auth-integration)
8. [Verification Checklist](#8-verification-checklist)
9. [Common Mistakes and What Goes Wrong](#9-common-mistakes-and-what-goes-wrong)
10. [Architecture Adaptation Notes](#10-architecture-adaptation-notes)

---

## 1. Conceptual Overview — Read Before Coding

### Why NOT Custom JWT Code

The wrong approach (common in legacy projects) is to write a custom `JwtAuthenticationFilter` that:
- Manually extracts the `Authorization: Bearer xxx` header
- Calls a custom `JwtTokenValidator` that parses the token using `io.jsonwebtoken:jjwt`
- Manually verifies the HMAC-SHA signature using a shared secret

**Why this is wrong:**

| Problem | Detail |
|---|---|
| Shared secret (HMAC) | The same secret must be on Keycloak AND every service. Rotation = update all services simultaneously |
| ~200 lines of custom code | Spring provides this for free. Every line you write is a line you must test, maintain, and fix when CVEs hit |
| Non-standard | Keycloak is designed for RS256 (asymmetric). HMAC integration is a workaround |
| Manual key rotation | Keycloak automatically rotates RSA keys. With shared HMAC secret you must manually coordinate |

### The Correct Approach — Spring OAuth2 Resource Server

```
Keycloak                          Your Service
────────                          ────────────
Issues JWT (signed with           Spring's BearerTokenAuthenticationFilter
RSA private key)         →        extracts "Bearer xxx" from header
                                           ↓
Publishes public key at  →        NimbusJwtDecoder fetches public key,
JWK Set URI endpoint              verifies RS256 signature, checks expiry
                                           ↓
                                  KeycloakJwtConverter maps claims
                                  to your typed CurrentPrincipal record
                                           ↓
                                  SecurityContext holds CurrentPrincipal
                                           ↓
                                  @PreAuthorize checks roles/permissions
```

**What you write: ~60 lines total** (SecurityConfig + converter config)
**What Spring does automatically:** token extraction, signature verification, expiry check, 401 responses, key caching, key rotation

---

## 2. Step 1 — Add Dependencies

### Maven (pom.xml)

```xml
<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- OAuth2 Resource Server — includes NimbusJwtDecoder, BearerTokenAuthenticationFilter -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<!-- For tests -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

### Gradle (build.gradle)

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'

testImplementation 'org.springframework.security:spring-security-test'
```

**What NOT to add:**
```groovy
// DO NOT ADD — NimbusJwtDecoder uses Nimbus internally, not jjwt
// io.jsonwebtoken:jjwt-api
// io.jsonwebtoken:jjwt-impl
// io.jsonwebtoken:jjwt-jackson
```

---

## 3. Step 2 — Configure Application Properties

### application-dev.properties

```properties
# =============================================================================
# SECURITY — OAuth2 Resource Server
# =============================================================================

# management.server.port — FIRST LINE OF DEFENSE
# Moves ALL /actuator/* endpoints to a separate port (9090).
# Spring Security's filter chain only applies to the main port (8080).
# In production: firewall port 9090 to internal VPN only.
# Without this: any internet user can POST /actuator/loggers and change log levels.
management.server.port=9090

# jwk-set-uri — Where Spring fetches Keycloak's RSA public key
# NimbusJwtDecoder fetches this LAZILY (not at startup).
# App starts even if Keycloak is temporarily unavailable.
# After first fetch, the key is CACHED — subsequent requests don't call Keycloak.
# Keycloak rotates keys automatically — Spring detects rotation and re-fetches.
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=\
  ${KEYCLOAK_BASE_URL:http://localhost:8080}/realms/${KEYCLOAK_REALM:your-realm}/protocol/openid-connect/certs
```

### application-prod.properties

```properties
# In production, never expose all actuator endpoints
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=never

# JWK URI — env vars must be set in deployment config (K8s, Docker, Railway)
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=\
  ${KEYCLOAK_BASE_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs
```

### Environment Variables (set in .env / docker-compose.yml / K8s Secret)

```bash
KEYCLOAK_BASE_URL=http://your-keycloak-host:8080
KEYCLOAK_REALM=your-realm-name
```

**What breaks if JWK URI is wrong:**
- App still starts (lazy fetch)
- First authenticated request returns 401 with error in logs: `Unable to resolve the Configuration with the provided Issuer`
- Verify with: `curl http://your-keycloak:8080/realms/your-realm/protocol/openid-connect/certs`
- Should return JSON with a `keys` array

---

## 4. Step 3 — Copy the Three Core Security Files

These three files are infrastructure — copy them into every new service unchanged.
Only the **package declaration** changes per project.

### 4.1 CurrentPrincipal.java

**Package:** `com.example.yourservice.security.principal`

```java
package com.example.yourservice.security.principal;

import java.util.List;
import java.util.UUID;

public record CurrentPrincipal(
        UUID userId,        // Keycloak's internal user ID (from "sub" claim)
        String employeeId,  // Employee code or username (from "employeeId" or "preferred_username")
        String email,       // User's email
        String firstName,   // From "given_name" claim
        String lastName,    // From "family_name" claim
        boolean isSuperAdmin,
        List<String> roles,
        List<String> permissions
) {
    public String getFullName() {
        return firstName + " " + lastName;
    }

    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
```

**Why a record?** Immutable — principal can never be accidentally mutated after it's set in the SecurityContext.
**Why not Spring's Jwt object?** Typed access. `principal.email()` vs `jwt.getClaimAsString("email")` everywhere in service layer.

---

### 4.2 SecurityContextHelper.java

**Package:** `com.example.yourservice.security.principal`

```java
package com.example.yourservice.security.principal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityContextHelper {

    private SecurityContextHelper() {}

    // Safe — returns Optional.empty() if not authenticated (e.g. in tests, batch jobs)
    public static Optional<CurrentPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof CurrentPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    // Strict — throws if called from unauthenticated context (fail fast)
    public static CurrentPrincipal requireCurrentPrincipal() {
        return currentPrincipal()
                .orElseThrow(() -> new IllegalStateException(
                        "No authenticated principal in SecurityContext"));
    }
}
```

**Why not just `SecurityContextHolder.getContext()` directly in every service?**
That's 4 lines of boilerplate repeated everywhere + casting + null check. One utility = one place to change if Spring's SecurityContext API changes.

---

### 4.3 KeycloakJwtConverter.java

**Package:** `com.example.yourservice.security.jwt`

```java
package com.example.yourservice.security.jwt;

import com.example.yourservice.security.principal.CurrentPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        CurrentPrincipal principal = buildPrincipal(jwt);
        List<GrantedAuthority> authorities = buildAuthorities(principal.roles(), principal.permissions());
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private CurrentPrincipal buildPrincipal(Jwt jwt) {
        UUID userId     = extractUserId(jwt);
        String employeeId = extractClaim(jwt, "employeeId", jwt.getClaimAsString("preferred_username"));
        String email    = jwt.getClaimAsString("email");
        String firstName = extractClaim(jwt, "given_name", jwt.getClaimAsString("firstName"));
        String lastName  = extractClaim(jwt, "family_name", jwt.getClaimAsString("lastName"));
        boolean isSuperAdmin = Boolean.TRUE.equals(jwt.getClaim("isSuperAdmin"));
        List<String> roles = extractRoles(jwt);
        List<String> permissions = extractStringList(jwt, "permissions");

        return new CurrentPrincipal(userId, employeeId, email, firstName, lastName,
                isSuperAdmin, roles, permissions);
    }

    private UUID extractUserId(Jwt jwt) {
        String rawId = extractClaim(jwt, "userId", jwt.getSubject());
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            // sub is not a UUID (username-based Keycloak config) — deterministic UUID
            return UUID.nameUUIDFromBytes(rawId.getBytes());
        }
    }

    private List<String> extractRoles(Jwt jwt) {
        List<String> roles = new ArrayList<>();

        // realm_access.roles — Keycloak's standard realm roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> realmRoles) {
            realmRoles.forEach(r -> roles.add(String.valueOf(r)));
        }

        // user_roles — custom Keycloak Protocol Mapper for app-specific roles
        roles.addAll(extractStringList(jwt, "user_roles"));

        // flat "roles" — fallback for services using a simple array claim
        roles.addAll(extractStringList(jwt, "roles"));

        return roles.stream().distinct().toList();
    }

    private List<String> extractStringList(Jwt jwt, String claimName) {
        Object claim = jwt.getClaim(claimName);
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return Collections.emptyList();
    }

    private String extractClaim(Jwt jwt, String preferred, String fallback) {
        String value = jwt.getClaimAsString(preferred);
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private List<GrantedAuthority> buildAuthorities(List<String> roles, List<String> permissions) {
        Stream<String> roleAuthorities = roles.stream().map(r -> "ROLE_" + r);
        Stream<String> permissionAuthorities = permissions.stream();
        return Stream.concat(roleAuthorities, permissionAuthorities)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
```

**Claim mapping this converter handles:**

| JWT Claim | CurrentPrincipal Field | Notes |
|---|---|---|
| `sub` | `userId` | Keycloak's user ID. Parsed as UUID, falls back to deterministic UUID if username-based |
| `email` | `email` | Standard OIDC claim |
| `given_name` | `firstName` | Falls back to `firstName` custom claim for backward compat |
| `family_name` | `lastName` | Falls back to `lastName` custom claim |
| `preferred_username` | `employeeId` | Falls back to `employeeId` custom claim |
| `realm_access.roles` | `roles` (merged) | Keycloak standard realm roles |
| `user_roles` | `roles` (merged) | Custom Protocol Mapper — app-specific roles |
| `permissions` | `permissions` | Custom claim — may be empty if not configured |
| `isSuperAdmin` | `isSuperAdmin` | Custom claim — defaults to false if absent |

---

## 5. Step 4 — Create SecurityConfig

**Package:** `com.example.yourservice.config`

```java
package com.example.yourservice.config;

import com.example.yourservice.security.jwt.KeycloakJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
// @EnableMethodSecurity — activates @PreAuthorize on methods.
// WITHOUT THIS: @PreAuthorize annotations are SILENTLY IGNORED.
// Every user gets in regardless of role. No error. No warning. Just no enforcement.
@EnableMethodSecurity
public class SecurityConfig {

    // Paths that don't require authentication.
    // /actuator/** is NOT here — it's on port 9090 (management.server.port).
    // Spring Security's filter chain only processes requests on the main port.
    private static final String[] OPEN_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
            // Add service-specific public paths here: "/api/v1/public/**"
    };

    private final KeycloakJwtConverter keycloakJwtConverter;

    public SecurityConfig(KeycloakJwtConverter keycloakJwtConverter) {
        this.keycloakJwtConverter = keycloakJwtConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF disabled — REST APIs are stateless, no browser session to protect.
            // CSRF only matters for cookie-based sessions. JWT in Authorization header = no CSRF risk.
            .csrf(AbstractHttpConfigurer::disable)

            // STATELESS — never create or use HTTP sessions.
            // Every request must carry its own JWT. No server-side session store.
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(OPEN_PATHS).permitAll()
                // Everything else requires a valid JWT.
                // anyRequest().authenticated() NOT permitAll() — common copy-paste mistake.
                .anyRequest().authenticated())

            // Wire the OAuth2 Resource Server with our converter.
            // This auto-configures: BearerTokenAuthenticationFilter + NimbusJwtDecoder.
            // The converter runs AFTER signature verification — it only maps claims, never validates.
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtConverter)));

        return http.build();
    }
}
```

---

## 6. Step 5 — Use Security in Your Code

### Protect Controller Endpoints

```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    // hasAuthority: exact string match against GrantedAuthority list
    // (which includes permissions AND "ROLE_" + roles)
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateOrderRequest request) { ... }

    // hasRole: Spring auto-prepends "ROLE_" prefix before checking
    // hasRole("ADMIN") checks for "ROLE_ADMIN" in authorities
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { ... }

    // hasAnyAuthority: OR condition — either permission grants access
    @PreAuthorize("hasAnyAuthority('ORDER_VIEW', 'ORDER_CREATE')")
    @GetMapping
    public ResponseEntity<?> getAll(Pageable pageable) { ... }
}
```

### Get Current User in Service Layer

```java
@Service
public class OrderServiceImpl implements OrderService {

    @Override
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        // requireCurrentPrincipal() throws IllegalStateException if not authenticated
        // Use this inside @PreAuthorize-protected methods where auth is guaranteed
        CurrentPrincipal user = SecurityContextHelper.requireCurrentPrincipal();

        log.info("Order created by: {} ({})", user.getFullName(), user.email());

        if (user.isSuperAdmin()) {
            // bypass certain business rules for super admins
        }

        if (user.hasPermission("ORDER_BULK_CREATE")) {
            // unlock bulk creation feature
        }
    }
}
```

### Audit Trail — Automatic (if using Spring Data Auditing)

```java
@Component
public class AuditorAwareImpl implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        // SecurityContextHelper reads from Spring's SecurityContext.
        // JPA automatically calls this when saving entities with @CreatedBy / @LastModifiedBy.
        return SecurityContextHelper.currentPrincipal()
                .map(CurrentPrincipal::employeeId);
    }
}
```

Result: `created_by` and `updated_by` columns automatically filled with the logged-in user's `employeeId`. No extra code in service layer.

---

## 7. Step 6 — Swagger Bearer Auth Integration

If using SpringDoc OpenAPI (Swagger UI), add Bearer auth to the `OpenAPI` bean:

```java
@Bean
public OpenAPI openApi() {
    return new OpenAPI()
            .info(new Info().title("Your Service API").version("1.0"))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components()
                    .addSecuritySchemes("bearerAuth", new SecurityScheme()
                            .name("bearerAuth")
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")));
}
```

This adds an **Authorize 🔒** button to Swagger UI. Paste your JWT (without `Bearer ` prefix) and all API calls from Swagger include the token automatically.

**Get a token for testing:**
```bash
curl -X POST http://your-keycloak:8080/realms/your-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=your-client-id" \
  -d "username=your-user" \
  -d "password=your-password"
# Response: { "access_token": "eyJ..." }
```

---

## 8. Verification Checklist

After setup, verify each of these manually:

```bash
# 1. Without token → 401 Unauthorized
curl -i http://localhost:8080/api/v1/your-endpoint
# Expected: HTTP/1.1 401 + WWW-Authenticate: Bearer

# 2. With valid token → 200 OK
curl -i http://localhost:8080/api/v1/your-endpoint \
  -H "Authorization: Bearer <your-keycloak-token>"
# Expected: HTTP/1.1 200

# 3. With expired/tampered token → 401
# (modify a character in the token)

# 4. Open paths — no token needed
curl -i http://localhost:8080/swagger-ui/index.html
# Expected: HTTP/1.1 200

# 5. Actuator on separate port — no Spring Security
curl -i http://localhost:9090/actuator/health
# Expected: HTTP/1.1 200 (Spring Security not involved here)

# 6. Protected with wrong role → 403 Forbidden
# (use a token that lacks the required @PreAuthorize role)
# Expected: HTTP/1.1 403

# 7. Verify Keycloak JWK endpoint is reachable
curl http://your-keycloak:8080/realms/your-realm/protocol/openid-connect/certs
# Expected: JSON with "keys" array
```

---

## 9. Common Mistakes and What Goes Wrong

### Mistake 1 — `anyRequest().permitAll()` left in code
**Symptom:** All endpoints accessible without token. No error, no warning.
**Fix:** Must be `.anyRequest().authenticated()`

### Mistake 2 — `@EnableMethodSecurity` missing
**Symptom:** `@PreAuthorize("hasRole('ADMIN')")` silently ignored. Any authenticated user gets in.
**Fix:** Add `@EnableMethodSecurity` to `SecurityConfig`

### Mistake 3 — JWK Set URI pointing to Keycloak root instead of certs endpoint
**Wrong:** `http://keycloak:8080/` or `http://keycloak:8080/realms/your-realm`
**Correct:** `http://keycloak:8080/realms/your-realm/protocol/openid-connect/certs`
**Symptom:** 401 on every request. Logs show `Unable to resolve configuration from issuer`

### Mistake 4 — Token claims don't match — CurrentPrincipal fields are null
**Cause:** Keycloak uses `given_name` (not `firstName`), `family_name` (not `lastName`), `sub` (not `userId`).
**Fix:** `KeycloakJwtConverter` already handles both formats (preferred + fallback). Check your Keycloak token at `jwt.io` to see actual claim names.

### Mistake 5 — `hasRole()` vs `hasAuthority()` confusion
- `hasRole('ADMIN')` checks for `ROLE_ADMIN` in authorities (Spring adds `ROLE_` prefix)
- `hasAuthority('ORDER_CREATE')` checks for exact string `ORDER_CREATE`
- Use `hasRole()` for roles, `hasAuthority()` for permissions (no prefix added)

### Mistake 6 — Token expiry (default 5 minutes in Keycloak)
**Symptom:** Token works immediately after login, then stops working after 5 minutes.
**Fix for dev/testing:** Increase Access Token Lifespan in Keycloak Admin Console → Realm Settings → Tokens

### Mistake 7 — Old JAR in Docker (Spring Boot + Docker)
**Symptom:** Security changes don't take effect in Docker.
**Cause:** Docker copies a pre-built JAR. If you only run `docker-compose up --build`, source changes are not compiled.
**Fix — Always rebuild the JAR first:**
```bash
# Maven
./mvnw package -DskipTests
docker-compose up --build

# Gradle
./gradlew bootJar
docker-compose up --build
```

---

## 10. Architecture Adaptation Notes

### Standard Layered (MVC)
```
com.example.yourservice/
├── config/
│   └── SecurityConfig.java              ← Here
├── security/
│   ├── jwt/
│   │   └── KeycloakJwtConverter.java    ← Here
│   └── principal/
│       ├── CurrentPrincipal.java        ← Here
│       └── SecurityContextHelper.java   ← Here
└── ...
```

### Hexagonal / Ports & Adapters
```
com.example.yourservice/
├── adapter/
│   └── in/web/
│       ├── config/
│       │   └── SecurityConfig.java      ← Inbound adapter concern (HTTP security)
│       └── security/
│           └── KeycloakJwtConverter.java ← Inbound adapter (JWT → domain principal)
├── application/
│   └── security/
│       └── SecurityContextHelper.java   ← Application layer (used by use cases)
└── domain/
    └── model/
        └── CurrentPrincipal.java        ← Domain record (pure Java — NO Spring imports)
```

**Note for Hexagonal:** `CurrentPrincipal` in the domain layer must have **zero Spring imports**. It's a plain Java record. `SecurityContextHelper` and `KeycloakJwtConverter` can have Spring imports since they live in application/adapter layers.

### Multi-Module Project (shared library)
If multiple services in the same repo, extract to a shared module:
```
common/common-security/
├── jwt/KeycloakJwtConverter.java
├── principal/CurrentPrincipal.java
└── principal/SecurityContextHelper.java
```
Each service depends on this module and only writes its own `SecurityConfig.java`.
