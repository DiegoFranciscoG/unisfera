package ec.unisfera.identity.security;

import ec.unisfera.shared.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Autentica el token de acceso; los tokens de desafío y las sesiones revocadas no abren la API. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final TokenService tokens;
    private final PermissionResolver permissions;
    private final RevokedSessionStore revoked;
    private final UserDirectory directory;

    public JwtAuthenticationFilter(TokenService tokens, PermissionResolver permissions, RevokedSessionStore revoked, UserDirectory directory) {
        this.tokens = tokens;
        this.permissions = permissions;
        this.revoked = revoked;
        this.directory = directory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            tokens.parse(header.substring(BEARER.length()))
                    .filter(c -> c.scope() == TokenService.Scope.FULL && c.sessionId() != null)
                    .filter(c -> !revoked.isRevoked(c.sessionId()))
                    .ifPresent(c -> directory.find(c.userId())
                            .filter(e -> "ACTIVE".equals(e.status()))
                            .ifPresent(entry -> {
                                Set<String> perms = permissions.permissionsFor(c.roles());
                                CurrentUser user = new CurrentUser(c.userId(), entry.personId(), entry.username(),
                                        Set.copyOf(c.roles()), perms, c.sessionId());
                                Set<SimpleGrantedAuthority> authorities = new HashSet<>();
                                perms.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                                c.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                                SecurityContextHolder.getContext().setAuthentication(
                                        UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
                                RequestContext.setActor(c.userId());
                                MDC.put("userId", c.userId().toString());
                            }));
        }
        chain.doFilter(request, response);
    }
}
