package ec.unisfera.reporting.application;

import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.reporting.domain.ReportTable;
import ec.unisfera.shared.domain.NotFoundException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reportes institucionales con filtros y fecha de corte. Ninguno toca welfare_attention
 * (verificado por un test de arquitectura) y los agregados de becas suprimen celdas pequeñas (RN-23).
 */
@Service
public class ReportService {

    public record Definition(String code, String title, String description, boolean needsPeriod, boolean personalData) {
    }

    public static final List<Definition> CATALOG = List.of(
            new Definition("enrolled", "Matriculados por carrera, nivel y período", "Conteo de estudiantes con matrícula vigente", true, false),
            new Definition("approval", "Aprobación y reprobación por asignatura y docente", "Desde actas cerradas", true, false),
            new Definition("dropout", "Deserción y alertas tempranas", "Alertas por nivel de riesgo y carrera", true, false),
            new Definition("aging", "Cartera vencida por antigüedad", "Saldos vencidos en tramos de 30 días a la fecha de corte", false, false),
            new Definition("collection", "Recaudación por medio de pago", "Pagos confirmados por mes y medio", false, false),
            new Definition("scholarships", "Becas otorgadas, montos y cumplimiento", "Incluye el indicador de la LOES art. 77", true, false),
            new Definition("occupancy", "Ocupación de paralelos", "Cupos usados frente a capacidad", true, false),
            new Definition("cohorts", "Rendimiento por cohorte", "Promedio ponderado y créditos por cohorte de ingreso", false, false),
            new Definition("control", "Listado para organismos de control", "Matriculados con código SENESCYT de carrera (datos personales)", true, true));

    private final JdbcClient jdbc;
    private final ParameterService parameters;

    public ReportService(JdbcClient jdbc, ParameterService parameters) {
        this.jdbc = jdbc;
        this.parameters = parameters;
    }

    @Transactional(readOnly = true)
    public ReportTable run(String code, UUID periodId, LocalDate cutoff) {
        Definition def = CATALOG.stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow(() -> new NotFoundException("El reporte " + code));
        LocalDate at = cutoff == null ? LocalDate.now() : cutoff;
        if (def.needsPeriod() && periodId == null) {
            throw new ec.unisfera.shared.domain.BusinessRuleException("REPORT", "Seleccione un período");
        }
        int threshold = parameters.integer(ParameterKeys.SMALL_CELL_THRESHOLD);
        return switch (code) {
            case "enrolled" -> table(def, at, """
                            select c.name carrera, l.number nivel, count(distinct e.student_id) matriculados,
                                   count(d.id) asignaturas_tomadas
                            from enrollment e join student s on s.id = e.student_id join career c on c.id = s.career_id
                            join enrollment_detail d on d.enrollment_id = e.id and d.status = 'ENROLLED'
                            join subject sb on sb.id = d.subject_id join curriculum_level l on l.id = sb.level_id
                            where e.period_id = :p and e.status = 'CONFIRMED' and e.enrolled_at::date <= :at
                            group by c.name, l.number order by c.name, l.number""", Map.of("p", periodId, "at", at));
            case "approval" -> table(def, at, """
                            select sb.code codigo, sb.name asignatura, coalesce(pt.first_names || ' ' || pt.last_names, 'Sin docente') docente,
                                   count(*) estudiantes, count(*) filter (where l.result = 'APPROVED') aprobados,
                                   count(*) filter (where l.result = 'FAILED') reprobados, count(*) filter (where l.result = 'FAILED_ATTENDANCE') por_asistencia,
                                   round(100.0 * count(*) filter (where l.result = 'APPROVED') / count(*), 1) tasa_aprobacion
                            from grade_act a join grade_act_line l on l.act_id = a.id join section s on s.id = a.section_id
                            join subject sb on sb.id = s.subject_id left join teacher t on t.id = s.teacher_id left join person pt on pt.id = t.person_id
                            where a.period_id = :p and a.status = 'CLOSED' and a.closed_at::date <= :at
                            group by sb.code, sb.name, pt.first_names, pt.last_names order by tasa_aprobacion""", Map.of("p", periodId, "at", at));
            case "dropout" -> table(def, at, """
                            select c.name carrera, a.risk_level riesgo, count(*) estudiantes,
                                   count(*) filter (where a.status = 'IN_FOLLOW_UP') en_seguimiento, count(*) filter (where a.status = 'CLOSED') cerradas
                            from dropout_alert a join student s on s.id = a.student_id join career c on c.id = s.career_id
                            where a.period_id = :p and a.detected_at::date <= :at group by c.name, a.risk_level order by c.name, a.risk_level""",
                    Map.of("p", periodId, "at", at)).suppressSmallCells(List.of(2, 3, 4), threshold);
            case "aging" -> table(def, at, """
                            select tramo, count(distinct student_id) estudiantes, sum(saldo) saldo_vencido from (
                              select i.student_id, i.amount_due + i.late_fee_accrued - i.amount_paid saldo,
                                     case when :at - i.due_date <= 30 then '1-30 días' when :at - i.due_date <= 60 then '31-60 días'
                                          when :at - i.due_date <= 90 then '61-90 días' else 'Más de 90 días' end tramo
                              from installment i where i.status = 'PENDING' and i.due_date < :at and i.amount_due + i.late_fee_accrued - i.amount_paid > 0) x
                            group by tramo order by min(saldo) is null, tramo""", Map.of("at", at));
            case "collection" -> table(def, at, """
                            select to_char(confirmed_at at time zone 'America/Guayaquil', 'YYYY-MM') mes, method medio, count(*) pagos, sum(amount) recaudado
                            from payment where status = 'CONFIRMED' and (confirmed_at at time zone 'America/Guayaquil')::date <= :at
                            group by 1, 2 order by 1 desc, 2""", Map.of("at", at));
            case "scholarships" -> scholarships(def, periodId, at, threshold);
            case "occupancy" -> table(def, at, """
                            select sb.code codigo, sb.name asignatura, s.code paralelo, s.capacity capacidad, s.enrolled_count matriculados,
                                   round(100.0 * s.enrolled_count / s.capacity, 1) ocupacion_pct
                            from section s join subject sb on sb.id = s.subject_id where s.period_id = :p order by ocupacion_pct desc""",
                    Map.of("p", periodId));
            case "cohorts" -> table(def, at, """
                            select p.code cohorte, c.name carrera, count(distinct s.id) estudiantes, round(avg(a.weighted_average), 2) promedio_ponderado,
                                   round(avg(a.approved_credits), 2) creditos_aprobados_promedio
                            from student s join academic_period p on p.id = s.cohort_period_id join career c on c.id = s.career_id
                            left join v_student_average a on a.student_id = s.id
                            group by p.code, c.name order by p.code desc, c.name""", Map.of()).suppressSmallCells(List.of(2), threshold);
            case "control" -> table(def, at, """
                            select pe.id_type tipo_id, pe.id_number identificacion, pe.last_names apellidos, pe.first_names nombres, c.senescyt_code codigo_carrera,
                                   c.name carrera, s.code codigo_estudiante, e.type tipo_matricula, max(l.number) nivel_maximo, count(d.id) asignaturas
                            from enrollment e join student s on s.id = e.student_id join person pe on pe.id = s.person_id join career c on c.id = s.career_id
                            join enrollment_detail d on d.enrollment_id = e.id and d.status = 'ENROLLED' join subject sb on sb.id = d.subject_id
                            join curriculum_level l on l.id = sb.level_id
                            where e.period_id = :p and e.status = 'CONFIRMED'
                            group by pe.id_type, pe.id_number, pe.last_names, pe.first_names, c.senescyt_code, c.name, s.code, e.type
                            order by c.name, pe.last_names""", Map.of("p", periodId));
            default -> throw new NotFoundException("El reporte " + code);
        };
    }

    private ReportTable scholarships(Definition def, UUID periodId, LocalDate at, int threshold) {
        ReportTable table = table(def, at, """
                        select t.name tipo, count(distinct a.id) becas, count(distinct a.student_id) becarios,
                               coalesce(sum(da.amount), 0) monto_descontado,
                               count(f.id) filter (where f.outcome = 'MAINTAINED') mantienen,
                               count(f.id) filter (where f.outcome <> 'MAINTAINED') no_cumplen
                        from scholarship_award a join scholarship_type t on t.id = a.type_id
                        left join discount_applied da on da.origin_id = a.id and da.status = 'ACTIVE' and da.period_id = :p
                        left join scholarship_followup f on f.award_id = a.id and f.period_id = :p
                        where a.status in ('ACTIVE', 'SUSPENDED') group by t.name order by becas desc""", Map.of("p", periodId))
                .suppressSmallCells(List.of(1, 2, 4, 5), threshold);
        long regular = jdbc.sql("select count(distinct e.student_id) from enrollment e where e.period_id = :p and e.status = 'CONFIRMED'")
                .param("p", periodId).query(Long.class).single();
        long holders = jdbc.sql("select count(distinct student_id) from scholarship_award where status = 'ACTIVE'").query(Long.class).single();
        List<String> notes = new ArrayList<>(table.notes());
        if (regular > 0) {
            double pct = 100.0 * holders / regular;
            notes.add(String.format("Becarios: %.1f %% de los estudiantes matriculados (mínimo LOES art. 77: %s %%)", pct,
                    parameters.decimal(ParameterKeys.MIN_SCHOLARSHIP_COVERAGE).stripTrailingZeros().toPlainString()));
        }
        return new ReportTable(table.code(), table.title(), table.cutoff(), table.headers(), table.rows(), notes);
    }

    private ReportTable table(Definition def, LocalDate at, String sql, Map<String, Object> params) {
        List<String> headers = new ArrayList<>();
        var spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<List<Object>> rows = spec.query((ResultSet rs, int n) -> {
            ResultSetMetaData md = rs.getMetaData();
            if (headers.isEmpty()) {
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    headers.add(md.getColumnLabel(i).replace('_', ' '));
                }
            }
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.add(rs.getObject(i));
            }
            return row;
        }).list();
        if (headers.isEmpty()) {
            headers.add("Sin datos");
        }
        return new ReportTable(def.code(), def.title(), at.toString(), headers, rows, List.of());
    }
}
