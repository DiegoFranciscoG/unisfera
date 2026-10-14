package ec.unisfera.evaluation.application;

import ec.unisfera.evaluation.domain.GradeMath;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kardex (RN-17: solo actas cerradas) y "mis notas" del período en curso con lo que falta para
 * aprobar y el semáforo de asistencia (aviso antes del cierre, RN-15).
 */
@Service
public class KardexService {

    public record KardexEntry(UUID subjectId, String subjectCode, String subjectName, int level, int totalHours, BigDecimal credits,
                              String periodCode, Integer attempt, BigDecimal finalGrade, BigDecimal attendancePct, String result, String source,
                              Instant recordedAt) {
    }

    public record Kardex(UUID studentId, BigDecimal weightedAverage, BigDecimal approvedCredits, long approvedSubjects, long failedAttempts,
                         List<KardexEntry> entries) {
    }

    public record ComponentGrade(UUID componentId, String name, BigDecimal weight, BigDecimal value) {
    }

    public record CurrentSubject(UUID detailId, String subjectCode, String subjectName, String sectionCode, int attempt, List<ComponentGrade> components,
                                 BigDecimal accumulated, GradeMath.Needed needed, BigDecimal attendancePct, BigDecimal minAttendance,
                                 boolean attendanceWarning, boolean provisional, String actStatus) {
    }

    private final JdbcClient jdbc;
    private final SchemeService schemes;
    private final TeachingService teaching;
    private final ParameterService parameters;

    public KardexService(JdbcClient jdbc, SchemeService schemes, TeachingService teaching, ParameterService parameters) {
        this.jdbc = jdbc;
        this.schemes = schemes;
        this.teaching = teaching;
        this.parameters = parameters;
    }

    @Transactional(readOnly = true)
    public Kardex kardex(UUID studentId) {
        List<KardexEntry> entries = jdbc.sql("""
                        select subject_id, subject_code, subject_name, level_number, total_hours, credits, period_code, attempt_number,
                               final_grade, attendance_pct, result, source, recorded_at
                        from v_kardex where student_id = :s order by level_number, subject_code, recorded_at""")
                .param("s", studentId)
                .query((rs, n) -> new KardexEntry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getString(7), (Integer) rs.getObject(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11),
                        rs.getString(12), rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant()))
                .list();
        BigDecimal average = jdbc.sql("select weighted_average from v_student_average where student_id = :s").param("s", studentId)
                .query(BigDecimal.class).optional().orElse(null);
        BigDecimal credits = entries.stream().filter(e -> "APPROVED".equals(e.result())).map(KardexEntry::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Kardex(studentId, average, credits, entries.stream().filter(e -> "APPROVED".equals(e.result())).count(),
                entries.stream().filter(e -> !"APPROVED".equals(e.result())).count(), entries);
    }

    /** Notas del período vigente: provisionales hasta que el acta se cierre (no cuentan para el kardex). */
    @Transactional(readOnly = true)
    public List<CurrentSubject> currentGrades(UUID studentId) {
        BigDecimal margin = parameters.decimal(ParameterKeys.ATTENDANCE_WARNING_MARGIN);
        List<Object[]> details = jdbc.sql("""
                        select d.id, d.section_id, sb.code, sb.name, s.code, d.attempt_number,
                               coalesce((select a.status from grade_act a where a.section_id = s.id and a.type = 'ORIGINAL'), 'OPEN')
                        from enrollment_detail d join section s on s.id = d.section_id join subject sb on sb.id = d.subject_id
                        join academic_period p on p.id = d.period_id
                        where d.student_id = :st and d.status = 'ENROLLED' and p.status in ('ENROLLMENT', 'IN_PROGRESS') order by sb.code""")
                .param("st", studentId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getString(7)})
                .list();
        return details.stream().map(d -> {
            UUID detailId = (UUID) d[0];
            UUID sectionId = (UUID) d[1];
            SchemeService.SchemeView scheme = schemes.forSection(sectionId);
            Map<UUID, BigDecimal> values = new HashMap<>();
            jdbc.sql("select component_id, value from grade where enrollment_detail_id = :d").param("d", detailId)
                    .query((rs, n) -> values.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
            List<ComponentGrade> components = scheme.components().stream()
                    .map(c -> new ComponentGrade(c.id(), c.name(), c.weight(), values.get(c.id()))).toList();
            BigDecimal attendance = teaching.attendanceBySection(sectionId).getOrDefault(detailId, GradeMath.attendancePct(0, 0));
            BigDecimal minAttendance = scheme.scale().minAttendance();
            return new CurrentSubject(detailId, (String) d[2], (String) d[3], (String) d[4], (int) d[5], components,
                    GradeMath.weighted(scheme.components(), values), GradeMath.needed(scheme.components(), values, scheme.scale()),
                    attendance, minAttendance, attendance.compareTo(minAttendance.add(margin)) < 0, "OPEN".equals(d[6]), (String) d[6]);
        }).toList();
    }
}
