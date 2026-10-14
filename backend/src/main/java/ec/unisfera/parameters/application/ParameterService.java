package ec.unisfera.parameters.application;

import ec.unisfera.parameters.domain.ParameterTimeline;
import ec.unisfera.parameters.domain.ParameterType;
import ec.unisfera.parameters.domain.ParameterVersion;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * LEY-1: única puerta de entrada a los valores de negocio. Caché en memoria de todas las
 * versiones (vigentes e históricas) que se invalida al publicar, también en las demás
 * instancias mediante un mensaje en Redis/Valkey.
 */
@Service
public class ParameterService {

    public static final String INVALIDATION_CHANNEL = "unisfera:parameters";
    private static final Logger log = LoggerFactory.getLogger(ParameterService.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final AtomicReference<Map<String, ParameterTimeline>> cache = new AtomicReference<>();

    public ParameterService(JdbcClient jdbc, Clock clock, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.redis = redis;
    }

    public BigDecimal decimal(String key) {
        return decimal(key, clock.instant());
    }

    public BigDecimal decimal(String key, Instant at) {
        return new BigDecimal(raw(key, at));
    }

    public int integer(String key) {
        return integer(key, clock.instant());
    }

    public int integer(String key, Instant at) {
        return Integer.parseInt(raw(key, at));
    }

    public boolean bool(String key) {
        return Boolean.parseBoolean(raw(key, clock.instant()));
    }

    public String text(String key) {
        return raw(key, clock.instant());
    }

    public List<String> list(String key) {
        return Arrays.stream(raw(key, clock.instant()).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Currency currency() {
        return Currency.getInstance(text(ParameterKeys.CURRENCY));
    }

    public ParameterVersion version(String key, Instant at) {
        return timeline(key).at(at);
    }

    public PageResponse<ParameterVersion> current(PageQuery page) {
        Instant now = clock.instant();
        List<ParameterVersion> all = timelines().values().stream()
                .map(t -> t.versions().stream().filter(v -> v.isValidAt(now)).findFirst().orElse(null))
                .filter(v -> v != null)
                .sorted(Comparator.comparing(ParameterVersion::key))
                .toList();
        return PageResponse.of(all.stream().skip(page.offset()).limit(page.size()).toList(), page, all.size());
    }

    public PageResponse<ParameterVersion> history(String key, PageQuery page) {
        List<ParameterVersion> versions = timeline(key).versions().reversed();
        return PageResponse.of(versions.stream().skip(page.offset()).limit(page.size()).toList(), page, versions.size());
    }

    /**
     * Publica un nuevo valor: cierra la versión vigente en validFrom e inserta la nueva. Nada se
     * sobrescribe; el historial completo queda en la tabla y en la auditoría.
     */
    @Transactional
    public ParameterVersion publish(String key, String value, Instant validFrom, String reason) {
        ParameterTimeline timeline = timeline(key);
        Instant from = validFrom == null ? clock.instant() : validFrom;
        if (from.isBefore(clock.instant().minusSeconds(1))) {
            throw new BusinessRuleException("LEY-1", "Un parámetro no se publica con vigencia en el pasado");
        }
        ParameterVersion current = timeline.at(clock.instant());
        current.type().validate(value);
        int closed = jdbc.sql("update parameter set valid_to = :from where key = :key and valid_to is null and valid_from < :from")
                .param("from", Timestamp.from(from)).param("key", key).update();
        if (closed != 1) {
            throw new BusinessRuleException("LEY-1", "Ya hay una versión programada para " + key + "; espere a que entre en vigencia");
        }
        jdbc.sql("""
                        insert into parameter (id, key, value, data_type, unit, description, valid_from, change_reason)
                        values (:id, :key, :value, :type, :unit, :description, :from, :reason)
                        """)
                .param("id", UuidV7.next(clock)).param("key", key).param("value", value)
                .param("type", current.type().name()).param("unit", current.unit())
                .param("description", current.description()).param("from", Timestamp.from(from)).param("reason", reason)
                .update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateEverywhere(key);
            }
        });
        invalidateLocal();
        return timeline(key).at(from);
    }

    public void invalidateLocal() {
        cache.set(null);
    }

    private void invalidateEverywhere(String key) {
        invalidateLocal();
        try {
            redis.convertAndSend(INVALIDATION_CHANNEL, key);
        } catch (RuntimeException e) {
            log.warn("No se pudo difundir la invalidación del parámetro {}: {}", key, e.getMessage());
        }
    }

    private String raw(String key, Instant at) {
        return timeline(key).at(at).value();
    }

    private ParameterTimeline timeline(String key) {
        ParameterTimeline t = timelines().get(key);
        if (t == null) {
            throw new NotFoundException("El parámetro " + key);
        }
        return t;
    }

    private Map<String, ParameterTimeline> timelines() {
        Map<String, ParameterTimeline> current = cache.get();
        if (current == null) {
            List<ParameterVersion> rows = jdbc.sql("select * from parameter").query(ParameterService::map).list();
            current = rows.stream().collect(Collectors.groupingBy(ParameterVersion::key)).entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> new ParameterTimeline(e.getKey(), e.getValue())));
            cache.set(current);
        }
        return current;
    }

    private static ParameterVersion map(ResultSet rs, int row) throws SQLException {
        Timestamp to = rs.getTimestamp("valid_to");
        return new ParameterVersion(rs.getObject("id", java.util.UUID.class), rs.getString("key"), rs.getString("value"),
                ParameterType.valueOf(rs.getString("data_type")), rs.getString("unit"), rs.getString("description"),
                rs.getTimestamp("valid_from").toInstant(), to == null ? null : to.toInstant(), rs.getString("change_reason"));
    }
}
