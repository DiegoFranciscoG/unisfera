package ec.unisfera.evaluation.application;

import ec.unisfera.evaluation.domain.GradeMath;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Esquemas de evaluación por período (y opcionalmente por carrera). Al crearse copian la escala,
 * la nota mínima y la asistencia mínima vigentes: cambiar el parámetro después no altera las
 * actas de ese período (LEY-1 sin romper el histórico).
 */
@Service
public class SchemeService {

    public record ComponentInput(String name, String kind, BigDecimal weight) {
    }

    public record SchemeView(UUID id, UUID periodId, UUID careerId, GradeMath.Scale scale, List<GradeMath.Component> components) {
    }

    private final JdbcClient jdbc;
    private final ParameterService parameters;
    private final Clock clock;

    public SchemeService(JdbcClient jdbc, ParameterService parameters, Clock clock) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.clock = clock;
    }

    @Transactional
    public SchemeView create(UUID periodId, UUID careerId, List<ComponentInput> components) {
        BigDecimal maxWeight = parameters.decimal(ParameterKeys.MAX_COMPONENT_WEIGHT);
        GradeMath.validateWeights(components.stream().map(ComponentInput::weight).toList(), maxWeight, parameters.integer(ParameterKeys.MIN_COMPONENTS));
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into evaluation_scheme (id, period_id, career_id, scale_min, scale_max, passing_grade, min_attendance_pct, max_component_weight)
                        values (:id, :p, :c, :min, :max, :pass, :att, :mw)""")
                .param("id", id).param("p", periodId).param("c", careerId)
                .param("min", parameters.decimal(ParameterKeys.SCALE_MIN)).param("max", parameters.decimal(ParameterKeys.SCALE_MAX))
                .param("pass", parameters.decimal(ParameterKeys.PASSING_GRADE)).param("att", parameters.decimal(ParameterKeys.MIN_ATTENDANCE))
                .param("mw", maxWeight).update();
        int order = 1;
        for (ComponentInput c : components) {
            jdbc.sql("insert into evaluation_component (id, scheme_id, name, kind, weight, sort_order) values (:id, :s, :n, :k, :w, :o)")
                    .param("id", UuidV7.next(clock)).param("s", id).param("n", c.name()).param("k", c.kind()).param("w", c.weight())
                    .param("o", order++).update();
        }
        return byId(id);
    }

    /** El esquema de la carrera si existe; si no, el general del período. */
    public SchemeView forSection(UUID sectionId) {
        UUID id = jdbc.sql("""
                        select es.id from section s join subject sb on sb.id = s.subject_id join curriculum cu on cu.id = sb.curriculum_id
                        join evaluation_scheme es on es.period_id = s.period_id and (es.career_id = cu.career_id or es.career_id is null)
                        where s.id = :s order by es.career_id nulls last limit 1""")
                .param("s", sectionId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("El esquema de evaluación del período"));
        return byId(id);
    }

    public SchemeView byId(UUID id) {
        var header = jdbc.sql("select period_id, career_id, scale_min, scale_max, passing_grade, min_attendance_pct from evaluation_scheme where id = :id")
                .param("id", id)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        new GradeMath.Scale(rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6))})
                .optional().orElseThrow(() -> new NotFoundException("El esquema"));
        List<GradeMath.Component> components = jdbc.sql("select id, name, weight from evaluation_component where scheme_id = :s order by sort_order")
                .param("s", id).query((rs, n) -> new GradeMath.Component(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3))).list();
        return new SchemeView(id, (UUID) header[0], (UUID) header[1], (GradeMath.Scale) header[2], components);
    }
}
