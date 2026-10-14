package ec.unisfera.identity.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Roles y permisos leídos de la base (role_permission), con caché corta e invalidable. */
@Component
public class PermissionResolver {

    private static final Duration TTL = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Cache<String, Set<String>> byRole = Caffeine.newBuilder().expireAfterWrite(TTL).build();

    public PermissionResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> permissionsFor(Collection<String> roles) {
        Set<String> result = new HashSet<>();
        for (String role : roles) {
            result.addAll(byRole.get(role, r -> Set.copyOf(jdbc.sql("""
                            select p.code from role_permission rp
                            join role r on r.id = rp.role_id join permission p on p.id = rp.permission_id
                            where r.code = :role""").param("role", r).query(String.class).list())));
        }
        return Set.copyOf(result);
    }

    public List<String> activeRoles(UUID userId) {
        return jdbc.sql("""
                        select r.code from user_role ur join role r on r.id = ur.role_id
                        where ur.user_id = :user and ur.revoked_at is null order by r.code""")
                .param("user", userId).query(String.class).list();
    }

    public boolean anyRequiresMfa(Collection<String> roles) {
        if (roles.isEmpty()) {
            return false;
        }
        return jdbc.sql("select count(*) from role where code in (:roles) and requires_mfa")
                .param("roles", roles).query(Long.class).single() > 0;
    }

    public void invalidate() {
        byRole.invalidateAll();
    }
}
