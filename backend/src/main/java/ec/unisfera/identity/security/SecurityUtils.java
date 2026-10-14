package ec.unisfera.identity.security;

import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<CurrentUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CurrentUser u ? Optional.of(u) : Optional.empty();
    }

    public static CurrentUser requireUser() {
        return currentUser().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Debe iniciar sesión"));
    }
}
