package com.SpringBootStarter.security.jwt;

import com.SpringBootStarter.security.principal.CurrentPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        UUID userId = extractUserId(jwt);
        String employeeId = extractClaim(jwt, "employeeId", jwt.getClaimAsString("preferred_username"));
        String email = jwt.getClaimAsString("email");
        String firstName = extractClaim(jwt, "given_name", jwt.getClaimAsString("firstName"));
        String lastName = extractClaim(jwt, "family_name", jwt.getClaimAsString("lastName"));
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
            // sub is not a UUID (e.g. username-based Keycloak config) — generate deterministic UUID
            return UUID.nameUUIDFromBytes(rawId.getBytes());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        List<String> realmRoles = new ArrayList<>();

        // realm_access.roles — Keycloak standard
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
            roles.forEach(r -> realmRoles.add(String.valueOf(r)));
        }

        // user_roles — custom Keycloak mapper (BiharOne app-specific roles)
        realmRoles.addAll(extractStringList(jwt, "user_roles"));

        // flat "roles" claim (fallback for services that use a simple array)
        realmRoles.addAll(extractStringList(jwt, "roles"));

        return realmRoles.stream().distinct().toList();
    }

    @SuppressWarnings("unchecked")
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
                .collect(java.util.stream.Collectors.toList());
    }
}
