package ec.unisfera.reporting.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

/** Tablero institucional: indicadores agregados (sin datos personales). */
@Service
public class DashboardService {

    public record Kpi(String key, String label, BigDecimal value, String unit) {
    }

    public record Series(String label, List<Map<String, Object>> points) {
    }

    public record Dashboard(String periodCode, List<Kpi> kpis, Series enrolledByCareer, Series collectionByMonth, Series approvalByLevel) {
    }

    private final JdbcClient jdbc;

    public DashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        var period = jdbc.sql("select id, code from academic_period where status in ('ENROLLMENT', 'IN_PROGRESS') order by classes_start desc limit 1")
                .query((rs, n) -> new Object[]{rs.getObject(1, java.util.UUID.class), rs.getString(2)}).optional().orElse(null);
        java.util.UUID p = period == null ? null : (java.util.UUID) period[0];
        List<Kpi> kpis = List.of(
                kpi("students", "Estudiantes activos", "select count(*) from student where status in ('ENROLLED', 'REGULAR')", null, ""),
                kpi("enrolled", "Matriculados en el período", "select count(*) from enrollment where period_id = :p and status = 'CONFIRMED'", p, ""),
                kpi("occupancy", "Ocupación de paralelos", "select coalesce(round(100.0 * sum(enrolled_count) / nullif(sum(capacity), 0), 1), 0) from section where period_id = :p", p, "%"),
                kpi("approval", "Aprobación histórica", "select coalesce(round(100.0 * count(*) filter (where result = 'APPROVED') / nullif(count(*), 0), 1), 0) from v_kardex where source = 'ACT'", null, "%"),
                kpi("overdue", "Cartera vencida", "select coalesce(sum(amount_due + late_fee_accrued - amount_paid), 0) from installment where status = 'PENDING' and due_date < current_date", null, "USD"),
                kpi("collected", "Recaudado este mes", "select coalesce(sum(amount), 0) from payment where status = 'CONFIRMED' and date_trunc('month', confirmed_at) = date_trunc('month', now())", null, "USD"),
                kpi("scholars", "Becarios activos", "select count(distinct student_id) from scholarship_award where status = 'ACTIVE'", null, ""),
                kpi("alerts", "Alertas de deserción abiertas", "select count(*) from dropout_alert where status <> 'CLOSED'", null, ""),
                kpi("requests", "Solicitudes pendientes", "select count(*) from student_request where status in ('SUBMITTED', 'IN_REVIEW')", null, ""));
        Series enrolled = series("Matriculados por carrera", """
                select c.name label, count(*) value from enrollment e join student s on s.id = e.student_id join career c on c.id = s.career_id
                where e.period_id = :p and e.status = 'CONFIRMED' group by c.name order by value desc""", p);
        Series collection = series("Recaudación mensual", """
                select to_char(confirmed_at at time zone 'America/Guayaquil', 'YYYY-MM') label, sum(amount) value from payment
                where status = 'CONFIRMED' and confirmed_at > now() - interval '12 months' group by 1 order by 1""", null);
        Series approval = series("Aprobación por nivel", """
                select 'Nivel ' || level_number label, round(100.0 * count(*) filter (where result = 'APPROVED') / count(*), 1) value
                from v_kardex where source = 'ACT' group by level_number order by level_number""", null);
        return new Dashboard(period == null ? null : (String) period[1], kpis, enrolled, collection, approval);
    }

    private Kpi kpi(String key, String label, String sql, java.util.UUID period, String unit) {
        var spec = jdbc.sql(sql);
        if (sql.contains(":p")) {
            spec = spec.param("p", period);
        }
        BigDecimal value = spec.query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        return new Kpi(key, label, value, unit);
    }

    private Series series(String label, String sql, java.util.UUID period) {
        var spec = jdbc.sql(sql);
        if (sql.contains(":p")) {
            spec = spec.param("p", period);
        }
        List<Map<String, Object>> points = spec.query((rs, n) -> Map.<String, Object>of("label", rs.getString("label"), "value", rs.getBigDecimal("value")))
                .list();
        return new Series(label, points);
    }
}
