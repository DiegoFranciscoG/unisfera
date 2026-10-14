package ec.unisfera.welfare.application;

import ec.unisfera.audit.application.AuditService;
import ec.unisfera.identity.security.CryptoService;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.RequestContext;
import ec.unisfera.welfare.domain.WelfareRules;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RN-23 · Atenciones de salud, psicología y orientación: dato sensible (LOPDP art. 26).
 * Solo con consentimiento explícito vigente, cifrado AES-256-GCM, accesible únicamente con el
 * permiso WELFARE_SENSITIVE y cada lectura queda en la auditoría. También: consentimientos y
 * alertas tempranas de deserción.
 */
@Service
public class WelfareCareService {

    public record Attention(UUID id, String category, Instant attendedAt, String summary, String details, boolean followUpRequired, String professional) {
    }

    public record Consent(String purpose, String textVersion, Instant grantedAt, Instant revokedAt) {
    }

    public record Alert(UUID id, UUID studentId, String studentCode, String studentName, String careerName, String risk, String reasons, String status,
                        Instant detectedAt) {
    }

    private static final String SUMMARY_CONTEXT = "welfare_attention.summary";
    private static final String DETAILS_CONTEXT = "welfare_attention.details";
    private static final String CONSENT_TEXT_VERSION = "LOPDP-2026-1";

    private final JdbcClient jdbc;
    private final CryptoService crypto;
    private final AuditService audit;
    private final ParameterService parameters;
    private final Clock clock;

    public WelfareCareService(JdbcClient jdbc, CryptoService crypto, AuditService audit, ParameterService parameters, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
        this.parameters = parameters;
        this.clock = clock;
    }

    // ---------------------------------------------------------- consentimiento
    @Transactional
    public void grantConsent(UUID personId, String purpose) {
        jdbc.sql("""
                        insert into consent (id, person_id, purpose, text_version, granted_ip) values (:id, :p, :pu, :v, :ip)
                        on conflict (person_id, purpose) where revoked_at is null do nothing""")
                .param("id", UuidV7.next(clock)).param("p", personId).param("pu", purpose).param("v", CONSENT_TEXT_VERSION)
                .param("ip", RequestContext.ip()).update();
    }

    /** Revocar no borra: el consentimiento queda con su fecha de revocación (LOPDP: derecho del titular). */
    @Transactional
    public void revokeConsent(UUID personId, String purpose) {
        jdbc.sql("update consent set revoked_at = now() where person_id = :p and purpose = :pu and revoked_at is null")
                .param("p", personId).param("pu", purpose).update();
    }

    @Transactional(readOnly = true)
    public List<Consent> consents(UUID personId) {
        return jdbc.sql("select purpose, text_version, granted_at, revoked_at from consent where person_id = :p order by granted_at desc")
                .param("p", personId)
                .query((rs, n) -> new Consent(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()))
                .list();
    }

    // ---------------------------------------------------------- atenciones
    @Transactional
    public UUID record(UUID studentId, String category, Instant attendedAt, String summary, String details, boolean followUp) {
        UUID consent = jdbc.sql("""
                        select c.id from consent c join student s on s.person_id = c.person_id
                        where s.id = :s and c.purpose = 'WELFARE_SENSITIVE' and c.revoked_at is null""")
                .param("s", studentId).query(UUID.class).optional()
                .orElseThrow(() -> new BusinessRuleException("RN-23", "El estudiante no ha otorgado consentimiento explícito para datos sensibles"));
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into welfare_attention (id, student_id, category, attended_at, professional_user_id, summary_enc, details_enc,
                                                       follow_up_required, consent_id)
                        values (:id, :s, :c, :at, :pro, :sum, :det, :f, :consent)""")
                .param("id", id).param("s", studentId).param("c", category).param("at", Timestamp.from(attendedAt))
                .param("pro", RequestContext.actorId().orElseThrow()).param("sum", crypto.encrypt(summary, SUMMARY_CONTEXT))
                .param("det", details == null ? null : crypto.encrypt(details, DETAILS_CONTEXT)).param("f", followUp).param("consent", consent).update();
        return id;
    }

    /** Cada lectura queda auditada como READ_SENSITIVE, fuera de la transacción de la consulta. */
    @Transactional(readOnly = true)
    public List<Attention> attentions(UUID studentId) {
        List<Attention> list = jdbc.sql("""
                        select w.id, w.category, w.attended_at, w.summary_enc, w.details_enc, w.follow_up_required, p.first_names || ' ' || p.last_names
                        from welfare_attention w join user_account u on u.id = w.professional_user_id join person p on p.id = u.person_id
                        where w.student_id = :s order by w.attended_at desc limit 50""")
                .param("s", studentId)
                .query((rs, n) -> new Attention(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        crypto.decrypt(rs.getBytes(4), SUMMARY_CONTEXT), rs.getBytes(5) == null ? null : crypto.decrypt(rs.getBytes(5), DETAILS_CONTEXT),
                        rs.getBoolean(6), rs.getString(7)))
                .list();
        audit.recordSensitiveRead("welfare_attention", studentId, "Lectura de " + list.size() + " atenciones del estudiante");
        return list;
    }

    // ---------------------------------------------------- alertas de deserción
    @Transactional
    public int detectDropoutRisk(UUID periodId) {
        int maxFailed = parameters.integer(ParameterKeys.DROPOUT_MAX_FAILED);
        BigDecimal threshold = parameters.decimal(ParameterKeys.DROPOUT_ATTENDANCE);
        LocalDate today = LocalDate.now(clock);
        List<Object[]> rows = jdbc.sql("""
                        select e.student_id,
                               (select count(*) from grade_act_line l join grade_act g on g.id = l.act_id and g.status = 'CLOSED'
                                where l.student_id = e.student_id and g.period_id = :p and l.result <> 'APPROVED') failed,
                               (select round(avg(l.attendance_pct), 2) from grade_act_line l join grade_act g on g.id = l.act_id and g.status = 'CLOSED'
                                where l.student_id = e.student_id and g.period_id = :p) attendance,
                               exists (select 1 from installment i where i.student_id = e.student_id and i.status = 'PENDING' and i.due_date < :today
                                       and i.amount_due + i.late_fee_accrued - i.amount_paid > 0) debt
                        from enrollment e where e.period_id = :p and e.status = 'CONFIRMED'""")
                .param("p", periodId).param("today", today)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3), rs.getBoolean(4)})
                .list();
        int alerts = 0;
        for (Object[] r : rows) {
            WelfareRules.RiskAssessment risk = WelfareRules.dropoutRisk(new WelfareRules.RiskInput((int) r[1], (BigDecimal) r[2], (boolean) r[3],
                    maxFailed, threshold));
            if (risk.level() == WelfareRules.Risk.LOW) {
                continue;
            }
            String reasons = "[" + String.join(",", risk.reasons().stream().map(s -> "\"" + s.replace("\"", "'") + "\"").toList()) + "]";
            jdbc.sql("""
                            insert into dropout_alert (id, student_id, period_id, risk_level, reasons) values (:id, :s, :p, :r, cast(:reasons as jsonb))
                            on conflict (student_id, period_id) do update set risk_level = excluded.risk_level, reasons = excluded.reasons""")
                    .param("id", UuidV7.next(clock)).param("s", r[0]).param("p", periodId).param("r", risk.level().name()).param("reasons", reasons).update();
            alerts++;
        }
        return alerts;
    }

    @Transactional(readOnly = true)
    public List<Alert> alerts(UUID periodId, String status) {
        return jdbc.sql("""
                        select a.id, a.student_id, s.code, p.first_names || ' ' || p.last_names, c.name, a.risk_level, a.reasons::text, a.status, a.detected_at
                        from dropout_alert a join student s on s.id = a.student_id join person p on p.id = s.person_id join career c on c.id = s.career_id
                        where a.period_id = :p and (cast(:st as text) is null or a.status = :st)
                        order by case a.risk_level when 'HIGH' then 0 when 'MEDIUM' then 1 else 2 end, a.detected_at desc limit 200""")
                .param("p", periodId).param("st", status)
                .query((rs, n) -> new Alert(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant()))
                .list();
    }

    @Transactional
    public void updateAlert(UUID alertId, String status, String note) {
        jdbc.sql("""
                        update dropout_alert set status = :s, note = :n, closed_at = case when :s = 'CLOSED' then now() end,
                               closed_by = case when :s = 'CLOSED' then app_actor() end where id = :id""")
                .param("s", status).param("n", note).param("id", alertId).update();
    }
}
