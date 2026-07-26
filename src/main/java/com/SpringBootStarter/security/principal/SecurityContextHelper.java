package com.SpringBootStarter.security.principal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityContextHelper {

    private SecurityContextHelper() {}

    public static Optional<CurrentPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof CurrentPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public static CurrentPrincipal requireCurrentPrincipal() {
        return currentPrincipal()
                .orElseThrow(() -> new IllegalStateException("No authenticated principal in SecurityContext"));
    }
}
