package ec.unisfera.finance.application;

import ec.unisfera.academic.application.AcademicEvents;
import ec.unisfera.academic.application.EnrollmentQuotePort;
import ec.unisfera.finance.domain.ChargeCalculator;
import ec.unisfera.finance.domain.InstallmentPlanner;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.UuidV7;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RN-09 y RN-10: genera los rubros de la matrícula (tarifario vigente + descuentos en orden de
 * prelación + plan de cuotas + libro mayor) y los revierte con movimientos de reverso al anularla.
 * Escucha los eventos académicos dentro de la misma transacción.
 */
@Service
public class BillingService implements EnrollmentQuotePort {

    private final JdbcClient jdbc;
    private final ParameterService parameters;
    private final Clock clock;
    private final AppProperties props;

    public BillingService(JdbcClient jdbc, ParameterService parameters, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.clock = clock;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public Quote quote(UUID studentId, UUID periodId, String enrollmentType, List<UUID> subjectIds) {
        Currency currency = parameters.currency();
        List<ChargeCalculator.ChargeLine> lines = ChargeCalculator.calculate(input(studentId, periodId, enrollmentType, subjectIds));
        BigDecimal total = lines.stream().map(ChargeCalculator.ChargeLine::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        int count = parameters.integer(ParameterKeys.INSTALLMENTS);
        List<InstallmentPlanner.Planned> plan = total.signum() > 0
                ? InstallmentPlanner.plan(total, count, today(), parameters.integer(ParameterKeys.INSTALLMENT_INTERVAL_DAYS), currency) : List.of();
        return new Quote(currency.getCurrencyCode(), lines.stream().map(l -> new QuoteLine(l.conceptCode(), l.description(), l.base(),
                l.discountTotal(), l.net())).toList(), total, plan.size(), plan.isEmpty() ? BigDecimal.ZERO : plan.getFirst().amount());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEnrollmentConfirmed(AcademicEvents.EnrollmentConfirmed event) {
        Currency currency = parameters.currency();
        List<ChargeCalculator.ChargeLine> lines = ChargeCalculator.calculate(
                input(event.studentId(), event.periodId(), event.type(), event.subjectIds()));
        BigDecimal total = BigDecimal.ZERO;
        for (ChargeCalculator.ChargeLine line : lines) {
            UUID chargeId = UuidV7.next(clock);
            jdbc.sql("""
                            insert into charge (id, student_id, period_id, enrollment_id, concept_id, tariff_id, description, quantity,
                                                unit_amount, base_amount, discount_amount, tax_rate)
                            values (:id, :s, :p, :e, :c, :t, :d, :q, :u, :b, :disc, :tax)""")
                    .param("id", chargeId).param("s", event.studentId()).param("p", event.periodId()).param("e", event.enrollmentId())
                    .param("c", line.conceptId()).param("t", line.tariffId()).param("d", line.description()).param("q", line.quantity())
                    .param("u", line.unitAmount()).param("b", line.base()).param("disc", line.discountTotal()).param("tax", line.taxRate()).update();
            if (line.base().signum() > 0) {
                entry(event.studentId(), event.periodId(), "CHARGE", 1, line.base(), chargeId, null, line.description());
            }
            for (ChargeCalculator.DiscountLine d : line.discounts()) {
                jdbc.sql("""
                                insert into discount_applied (id, charge_id, student_id, period_id, origin_type, origin_id, percentage, amount, precedence)
                                values (:id, :c, :s, :p, :o, :oid, :pct, :a, :pr)""")
                        .param("id", UuidV7.next(clock)).param("c", chargeId).param("s", event.studentId()).param("p", event.periodId())
                        .param("o", d.originType()).param("oid", d.originId()).param("pct", d.percentage()).param("a", d.amount())
                        .param("pr", d.precedence()).update();
                entry(event.studentId(), event.periodId(), "DISCOUNT", -1, d.amount(), chargeId, null,
                        "Descuento " + d.originType().toLowerCase() + " · " + line.description());
            }
            total = total.add(line.net());
        }
        createPlan(event.enrollmentId(), event.studentId(), event.periodId(), total, currency);
    }

    private void createPlan(UUID enrollmentId, UUID studentId, UUID periodId, BigDecimal total, Currency currency) {
        boolean free = total.signum() == 0;
        int count = free ? 1 : parameters.integer(ParameterKeys.INSTALLMENTS);
        UUID planId = UuidV7.next(clock);
        jdbc.sql("insert into payment_plan (id, student_id, period_id, enrollment_id, installments_count, total) values (:id, :s, :p, :e, :n, :t)")
                .param("id", planId).param("s", studentId).param("p", periodId).param("e", enrollmentId).param("n", count).param("t", total).update();
        LocalDate firstDue = today().plusDays(parameters.integer(ParameterKeys.FIRST_INSTALLMENT_DAYS));
        for (InstallmentPlanner.Planned p : InstallmentPlanner.plan(total, count, firstDue, parameters.integer(ParameterKeys.INSTALLMENT_INTERVAL_DAYS), currency)) {
            jdbc.sql("""
                            insert into installment (id, plan_id, student_id, period_id, number, due_date, amount_due, status)
                            values (:id, :plan, :s, :p, :n, :due, :amt, :st)""")
                    .param("id", UuidV7.next(clock)).param("plan", planId).param("s", studentId).param("p", periodId).param("n", p.number())
                    .param("due", p.dueDate()).param("amt", p.amount()).param("st", free ? "PAID" : "PENDING").update();
        }
    }

    /** RN-10: reverso exacto de cargos, descuentos y mora; las cuotas pendientes quedan anuladas. Nada se borra. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEnrollmentAnnulled(AcademicEvents.EnrollmentAnnulled event) {
        List<Object[]> entries = jdbc.sql("""
                        select e.id, e.direction, e.amount, e.description from account_entry e
                        where e.entry_type in ('CHARGE', 'DISCOUNT', 'LATE_FEE')
                          and (e.charge_id in (select id from charge where enrollment_id = :en)
                               or e.installment_id in (select i.id from installment i join payment_plan pp on pp.id = i.plan_id where pp.enrollment_id = :en))
                          and not exists (select 1 from account_entry r where r.reverses_entry_id = e.id)""")
                .param("en", event.enrollmentId())
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3), rs.getString(4)})
                .list();
        for (Object[] e : entries) {
            jdbc.sql("""
                            insert into account_entry (id, student_id, period_id, entry_type, direction, amount, reverses_entry_id, description)
                            values (:id, :s, :p, 'REVERSAL', :d, :a, :r, :desc)""")
                    .param("id", UuidV7.next(clock)).param("s", event.studentId()).param("p", event.periodId())
                    .param("d", -((int) e[1])).param("a", e[2]).param("r", e[0]).param("desc", "Reverso por anulación: " + e[3]).update();
        }
        jdbc.sql("update charge set status = 'REVERSED', reversed_at = now(), reversed_by = app_actor(), reversal_reason = :r where enrollment_id = :e and status = 'ACTIVE'")
                .param("r", event.reason()).param("e", event.enrollmentId()).update();
        jdbc.sql("update discount_applied set status = 'REVERSED' where charge_id in (select id from charge where enrollment_id = :e) and status = 'ACTIVE'")
                .param("e", event.enrollmentId()).update();
        jdbc.sql("update installment set status = 'VOID' where plan_id in (select id from payment_plan where enrollment_id = :e) and status = 'PENDING'")
                .param("e", event.enrollmentId()).update();
        jdbc.sql("update payment_plan set status = 'CANCELLED' where enrollment_id = :e").param("e", event.enrollmentId()).update();
    }

    void entry(UUID studentId, UUID periodId, String type, int direction, BigDecimal amount, UUID chargeId, UUID installmentId, String description) {
        jdbc.sql("""
                        insert into account_entry (id, student_id, period_id, entry_type, direction, amount, charge_id, installment_id, description)
                        values (:id, :s, :p, :t, :d, :a, :c, :i, :desc)""")
                .param("id", UuidV7.next(clock)).param("s", studentId).param("p", periodId).param("t", type).param("d", direction)
                .param("a", amount).param("c", chargeId).param("i", installmentId)
                .param("desc", ec.unisfera.shared.domain.TechnicalLimits.truncate(description, ec.unisfera.shared.domain.TechnicalLimits.DESCRIPTION)).update();
    }

    private ChargeCalculator.Input input(UUID studentId, UUID periodId, String type, List<UUID> subjectIds) {
        Currency currency = parameters.currency();
        var student = jdbc.sql("select career_id from student where id = :s").param("s", studentId).query(UUID.class).single();
        var subjectInfo = jdbc.sql("""
                        select coalesce(sum(s.credits), 0), count(*), coalesce(min(l.number), 1)
                        from subject s join curriculum_level l on l.id = s.level_id where s.id in (:ids)""")
                .param("ids", subjectIds.isEmpty() ? List.of(UUID.randomUUID()) : subjectIds)
                .query((rs, n) -> new Object[]{rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3)}).single();
        BigDecimal educationTax = parameters.decimal(ParameterKeys.IVA_EDUCATION);
        BigDecimal generalTax = parameters.decimal(ParameterKeys.IVA_GENERAL);
        Map<String, ChargeCalculator.Tariff> byConcept = new HashMap<>();
        Map<String, Integer> specificity = new HashMap<>();
        jdbc.sql("""
                        select t.id, c.id concept_id, c.code, c.name, c.kind, c.discountable, c.tax_category, t.calc_mode, t.amount,
                               (case when t.career_id is not null then 2 else 0 end) + (case when t.level_number is not null then 1 else 0 end) spec
                        from tariff t join charge_concept c on c.id = t.concept_id
                        where t.period_id = :p and t.status = 'ACTIVE' and c.active and c.kind in ('ENROLLMENT', 'TUITION')
                          and (t.career_id is null or t.career_id = :car) and (t.level_number is null or t.level_number = :lvl)""")
                .param("p", periodId).param("car", student).param("lvl", subjectInfo[2])
                .query((rs, n) -> {
                    String code = rs.getString("code");
                    int spec = rs.getInt("spec");
                    if (spec >= specificity.getOrDefault(code, -1)) {
                        specificity.put(code, spec);
                        byConcept.put(code, new ChargeCalculator.Tariff(rs.getObject("id", UUID.class), rs.getObject("concept_id", UUID.class), code,
                                rs.getString("name"), rs.getString("kind"), ChargeCalculator.CalcMode.valueOf(rs.getString("calc_mode")),
                                rs.getBigDecimal("amount"), rs.getBoolean("discountable"),
                                "EDUCATION".equals(rs.getString("tax_category")) ? educationTax : generalTax));
                    }
                    return code;
                }).list();
        ChargeCalculator.Tariff surcharge = jdbc.sql("select id, code, name, discountable, tax_category from charge_concept where kind = 'SURCHARGE' and active limit 1")
                .query((rs, n) -> new ChargeCalculator.Tariff(null, rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        "SURCHARGE", ChargeCalculator.CalcMode.FLAT, BigDecimal.ZERO, rs.getBoolean("discountable"),
                        "EDUCATION".equals(rs.getString("tax_category")) ? educationTax : generalTax))
                .optional().orElse(null);
        List<ChargeCalculator.DiscountSource> discounts = scholarshipDiscounts(studentId, periodId);
        List<ChargeCalculator.Tariff> tariffs = new ArrayList<>(byConcept.values());
        tariffs.sort(java.util.Comparator.comparing(ChargeCalculator.Tariff::kind));
        return new ChargeCalculator.Input(tariffs, (BigDecimal) subjectInfo[0], (int) subjectInfo[1], "EXTRAORDINARY".equals(type),
                parameters.decimal(ParameterKeys.EXTRAORDINARY_SURCHARGE), surcharge, discounts,
                parameters.list(ParameterKeys.DISCOUNT_PRECEDENCE), currency);
    }

    /** RN-21: becas activas cuyo período de inicio no es posterior al período cobrado (nunca retroactivas por defecto). */
    private List<ChargeCalculator.DiscountSource> scholarshipDiscounts(UUID studentId, UUID periodId) {
        Map<UUID, Set<String>> covered = new HashMap<>();
        List<Object[]> awards = jdbc.sql("""
                        select a.id, a.mode, a.value, a.type_id from scholarship_award a
                        join academic_period sp on sp.id = a.start_period_id join academic_period cp on cp.id = :p
                        left join academic_period ep on ep.id = a.end_period_id
                        where a.student_id = :s and a.status = 'ACTIVE' and sp.classes_start <= cp.classes_start
                          and (ep.id is null or ep.classes_start >= cp.classes_start)""")
                .param("s", studentId).param("p", periodId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getObject(4, UUID.class)})
                .list();
        for (Object[] a : awards) {
            covered.put((UUID) a[0], new HashSet<>(jdbc.sql("""
                            select c.code from scholarship_type_concept tc join charge_concept c on c.id = tc.concept_id where tc.scholarship_type_id = :t""")
                    .param("t", a[3]).query(String.class).list()));
        }
        return awards.stream().map(a -> new ChargeCalculator.DiscountSource("SCHOLARSHIP", (UUID) a[0], (String) a[1], (BigDecimal) a[2],
                Set.copyOf(covered.get((UUID) a[0])))).toList();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(props.zoneId()));
    }
}
