package ec.unisfera.identity.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Datos inmutables de la cuenta (persona, usuario) para construir el principal sin ir a la base en cada petición. */
@Component
public class UserDirectory {

    public record Entry(UUID personId, String username, String status) {
    }

    private final JdbcClient jdbc;
    private final Cache<UUID, Optional<Entry>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(50_000).build();

    public UserDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(UUID userId) {
        return cache.get(userId, id -> jdbc.sql("select person_id, username, status from user_account where id = :id")
                .param("id", id)
                .query((rs, n) -> new Entry(rs.getObject("person_id", UUID.class), rs.getString("username"), rs.getString("status")))
                .optional());
    }

    public void evict(UUID userId) {
        cache.invalidate(userId);
    }
}
