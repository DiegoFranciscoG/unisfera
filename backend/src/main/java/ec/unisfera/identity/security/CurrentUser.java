package ec.unisfera.identity.security;

import java.util.Set;
import java.util.UUID;

/** Principal autenticado. Los permisos se resuelven desde la base a partir de los roles. */
public record CurrentUser(UUID userId, UUID personId, String username, Set<String> roles, Set<String> permissions, UUID sessionId) {

    public boolean has(String permission) {
        return permissions.contains(permission);
    }
}
