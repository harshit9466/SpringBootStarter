package com.SpringBootStarter.security.principal;

import java.util.List;
import java.util.UUID;

public record CurrentPrincipal(
        UUID userId,
        String employeeId,
        String email,
        String firstName,
        String lastName,
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
