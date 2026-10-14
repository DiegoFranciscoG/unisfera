package ec.unisfera.finance.application;

import ec.unisfera.finance.domain.LateFeeCalculator;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.UuidV7;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devengo diario de mora (RN-26): para cada cuota vencida usa la tasa y los días de gracia
 * vigentes el día de SU vencimiento. Cada incremento queda como movimiento LATE_FEE en el libro.
 */
@Service
public class LateFeeService {

    private static final Logger log = LoggerFactory.getLogger(LateFeeService.class);

    private final JdbcClient jdbc;
    private final ParameterService parameters;
    private final Clock clock;
    private final AppProperties props;

    public LateFeeService(JdbcClient jdbc, ParameterService parameters, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(cron = "0 15 1 * * *", zone = "America/Guayaquil")
    public void nightly() {
        if (!props.workersEnabled()) {
            return;
        }
        int touched = accrue(LocalDate.now(clock.withZone(props.zoneId())));
        log.info("Mora devengada en {} cuotas", touched);
    }

    @Transactional
    public int accrue(LocalDate asOf) {
        Currency currency = parameters.currency();
        List<Object[]> due = jdbc.sql("""
                        select i.id, i.student_id, i.period_id, i.due_date, i.amount_due, i.late_fee_accrued,
                               coalesce((select sum(a.amount) from payment_allocation a join payment p on p.id = a.payment_id
                                         where a.installment_id = i.id and a.kind = 'PRINCIPAL' and p.status = 'CONFIRMED'), 0) principal_paid
                        from installment i where i.status = 'PENDING' and i.due_date < :asOf for update of i""")
                .param("asOf", asOf)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getDate(4).toLocalDate(), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)})
                .list();
        int touched = 0;
        for (Object[] i : due) {
            LocalDate dueDate = (LocalDate) i[3];
            Instant dueInstant = dueDate.atStartOfDay(props.zoneId()).toInstant();
            LateFeeCalculator.RuleAtDueDate rule = new LateFeeCalculator.RuleAtDueDate(
                    parameters.decimal(ParameterKeys.LATE_FEE_MONTHLY_RATE, dueInstant), parameters.integer(ParameterKeys.GRACE_DAYS, dueInstant));
            BigDecimal principal = ((BigDecimal) i[4]).subtract((BigDecimal) i[6]);
            BigDecimal accrued = LateFeeCalculator.accruedAt(dueDate, asOf, principal, rule, currency);
            BigDecimal increment = LateFeeCalculator.increment((BigDecimal) i[5], accrued);
            if (increment.signum() > 0) {
                jdbc.sql("update installment set late_fee_accrued = late_fee_accrued + :inc, last_late_fee_on = :d where id = :id")
                        .param("inc", increment).param("d", asOf).param("id", i[0]).update();
                jdbc.sql("""
                                insert into account_entry (id, student_id, period_id, entry_type, direction, amount, installment_id, description)
                                values (:id, :s, :p, 'LATE_FEE', 1, :a, :i, :d)""")
                        .param("id", UuidV7.next(clock)).param("s", i[1]).param("p", i[2]).param("a", increment).param("i", i[0])
                        .param("d", "Interés por mora (tasa vigente al " + dueDate + ")").update();
                touched++;
            }
        }
        return touched;
    }
}
