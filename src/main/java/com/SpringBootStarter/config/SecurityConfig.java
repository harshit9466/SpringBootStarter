package com.SpringBootStarter.config;

import com.SpringBootStarter.security.jwt.KeycloakJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.observation.SecurityObservationSettings;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Paths accessible without authentication.
    // /actuator/** included here — Spring Security filter chain applies to BOTH
    // main port (8082) and management port (9091). Network firewall protects 9091 in prod.
    private static final String[] OPEN_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/**"
    };

    private final KeycloakJwtConverter keycloakJwtConverter;

    public SecurityConfig(KeycloakJwtConverter keycloakJwtConverter) {
        this.keycloakJwtConverter = keycloakJwtConverter;
    }

    // PROD / default — active for every profile EXCEPT dev.
    // Full OAuth2 Resource Server security: only OPEN_PATHS are public, everything else needs a valid JWT.
    @Bean
    @Profile("!dev")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(OPEN_PATHS).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtConverter)));

        return http.build();
    }

    // DEV only — permit everything, NO oauth2 resource server.
    // WHY: the dev Keycloak (jwk-set-uri) is not reachable from local machines, so enforcing JWT
    // would 401 every /api/** request and business logic (and its metrics) would never run.
    // This chain omits oauth2ResourceServer entirely, so the app never tries to reach Keycloak in dev.
    // Safe because @Profile("!dev") above keeps full security on ALL non-dev profiles (staging, prod).
    @Bean
    @Profile("dev")
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    /*
     * Module 8 (Distributed Tracing) gotcha: once an ObservationRegistry bean exists
     * (added by micrometer-tracing-bridge-otel), Spring Security started emitting its
     * own "filterchain before/after" AND "authorize request" spans — both showed up in
     * Tempo as DISCONNECTED root traces, not child spans of the HTTP request trace. An
     * orphan span with no parent is worse than no span: it clutters trace search with
     * entries that can never be correlated back to the request that caused them.
     *
     * The "authorize request" spans specifically kept appearing every ~15s in Tempo —
     * matching Prometheus's own scrape_interval hitting /actuator/prometheus. That
     * traffic goes through the security filter chain's authorization check same as any
     * request (even though dev's chain is permitAll), so it never stops: this would
     * flood Tempo with meaningless entries continuously in any real deployment, not
     * just during this verification.
     *
     * SecurityObservationSettings (Spring Security 6.4+, package
     * org.springframework.security.config.observation) controls this — verified by
     * reading the actual 6.5.1 source, not assumed: the real API is
     * shouldObserveRequests(boolean) / shouldObserveAuthorizations(boolean), NOT
     * "shouldObserveFilterChains" (an earlier attempt used that name from a stale doc
     * source and failed to compile).
     *
     * Trade-off being made here: shouldObserveAuthorizations(false) turns off
     * authorization spans for EVERY request, not just actuator/scrape traffic — including
     * genuine /api/** business calls. A more surgical fix exists (filter by request path
     * via a custom ObservationPredicate against AuthorizationObservationContext), but that
     * context's authorized object is a generic <T> whose exact runtime type needs further
     * verification before relying on it. Given this project's authorization is simple
     * JWT role checks (not a slow custom AuthorizationManager), the diagnostic value of a
     * per-request authorization span is low — accepting the blanket disable now rather
     * than guessing at unverified internals a second time in the same session.
     */
    @Bean
    public SecurityObservationSettings securityObservationSettings() {
        return SecurityObservationSettings.withDefaults()
                .shouldObserveRequests(false)
                .shouldObserveAuthorizations(false)
                .build();
    }
}
