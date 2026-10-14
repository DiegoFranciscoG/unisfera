package ec.unisfera.finance.application;

import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Estado de cuenta (RN-29), tarifario versionado y conciliación de extractos. */
@Service
public class FinanceQueryService {

    public record Summary(BigDecimal charges, BigDecimal lateFees, BigDecimal discounts, BigDecimal payments, BigDecimal creditNotes,
                          BigDecimal reversals, BigDecimal balance, boolean balances) {
    }

    public record InstallmentView(UUID id, String periodCode, int number, LocalDate dueDate, BigDecimal amountDue, BigDecimal lateFee,
                                  BigDecimal paid, BigDecimal outstanding, String status, boolean overdue) {
    }

    public record EntryView(UUID id, Instant occurredAt, String type, int direction, BigDecimal amount, String description) {
    }

    public record Statement(Summary summary, List<InstallmentView> installments, PageResponse<EntryView> entries) {
    }

    public record TariffView(UUID id, String conceptCode, String conceptName, UUID careerId, String careerName, Integer levelNumber,
                             UUID periodId, String periodCode, String calcMode, BigDecimal amount, int tariffVersion, String status) {
    }

    public record ReconciliationResult(UUID id, int totalRows, int matched, int discrepancies, List<String> notes) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public FinanceQueryService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Statement statement(UUID studentId, PageQuery page) {
        Summary summary = jdbc.sql("select * from v_account_statement where student_id = :s").param("s", studentId)
                .query((rs, n) -> {
                    BigDecimal charges = rs.getBigDecimal("charges");
                    BigDecimal lateFees = rs.getBigDecimal("late_fees");
                    BigDecimal discounts = rs.getBigDecimal("discounts");
                    BigDecimal payments = rs.getBigDecimal("payments");
                    BigDecimal credits = rs.getBigDecimal("credit_notes");
                    BigDecimal reversals = rs.getBigDecimal("reversals");
                    BigDecimal balance = rs.getBigDecimal("balance");
                    boolean ok = charges.add(lateFees).subtract(discounts).subtract(payments).subtract(credits).add(reversals).compareTo(balance) == 0;
                    return new Summary(charges, lateFees, discounts, payments, credits, reversals, balance, ok);
                })
                .optional()
                .orElse(new Summary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true));
        List<InstallmentView> installments = jdbc.sql("""
                        select i.id, p.code, i.number, i.due_date, i.amount_due, i.late_fee_accrued, i.amount_paid, i.status
                        from installment i join academic_period p on p.id = i.period_id
                        where i.student_id = :s and i.status <> 'VOID' order by i.due_date desc, i.number limit 40""")
                .param("s", studentId)
                .query((rs, n) -> {
                    BigDecimal outstanding = rs.getBigDecimal(5).add(rs.getBigDecimal(6)).subtract(rs.getBigDecimal(7));
                    LocalDate due = rs.getDate(4).toLocalDate();
                    return new InstallmentView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), due, rs.getBigDecimal(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7), outstanding, rs.getString(8),
                            "PENDING".equals(rs.getString(8)) && due.isBefore(LocalDate.now(clock)));
                })
                .list();
        List<EntryView> entries = jdbc.sql("""
                        select id, occurred_at, entry_type, direction, amount, description from account_entry
                        where student_id = :s order by occurred_at desc limit :l offset :o""")
                .param("s", studentId).param("l", page.size()).param("o", page.offset())
                .query((rs, n) -> new EntryView(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getString(6)))
                .list();
        long total = jdbc.sql("select count(*) from account_entry where student_id = :s").param("s", studentId).query(Long.class).single();
        return new Statement(summary, installments, PageResponse.of(entries, page, total));
    }

    @Transactional(readOnly = true)
    public PageResponse<TariffView> tariffs(UUID periodId, PageQuery page) {
        var items = jdbc.sql("""
                        select t.id, c.code, c.name, t.career_id, ca.name career, t.level_number, t.period_id, p.code period_code,
                               t.calc_mode, t.amount, t.tariff_version, t.status
                        from tariff t join charge_concept c on c.id = t.concept_id join academic_period p on p.id = t.period_id
                        left join career ca on ca.id = t.career_id
                        where (cast(:p as uuid) is null or t.period_id = :p) order by p.code desc, c.code, ca.name, t.level_number, t.tariff_version desc
                        limit :l offset :o""")
                .param("p", periodId).param("l", page.size()).param("o", page.offset())
                .query((rs, n) -> new TariffView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), (Integer) rs.getObject(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9), rs.getBigDecimal(10),
                        rs.getInt(11), rs.getString(12)))
                .list();
        long total = jdbc.sql("select count(*) from tariff t where (cast(:p as uuid) is null or t.period_id = :p)").param("p", periodId)
                .query(Long.class).single();
        return PageResponse.of(items, page, total);
    }

    /** Publica un precio: la versión vigente pasa a SUPERSEDED y queda en el historial. Matrículas ya cobradas no cambian. */
    @Transactional
    public UUID publishTariff(String conceptCode, UUID careerId, Integer levelNumber, UUID periodId, String calcMode, BigDecimal amount, String reason) {
        UUID conceptId = jdbc.sql("select id from charge_concept where code = :c").param("c", conceptCode).query(UUID.class).optional()
                .orElseThrow(() -> new BusinessRuleException("LEY-1", "Concepto de cobro desconocido"));
        var current = jdbc.sql("""
                        select id, tariff_version from tariff where concept_id = :c and career_id is not distinct from :car
                          and level_number is not distinct from :lvl and period_id = :p and status = 'ACTIVE' for update""")
                .param("c", conceptId).param("car", careerId).param("lvl", levelNumber).param("p", periodId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getInt(2)}).optional();
        UUID id = UuidV7.next(clock);
        int version = current.map(c -> (int) c[1] + 1).orElse(1);
        // La versión vigente queda reemplazada por la nueva (FK diferida): nunca hay dos activas ni se sobrescribe un precio.
        current.ifPresent(c -> jdbc.sql("update tariff set status = 'SUPERSEDED', superseded_by_id = :n where id = :old")
                .param("n", id).param("old", c[0]).update());
        jdbc.sql("insert into tariff (id, concept_id, career_id, level_number, period_id, calc_mode, amount, tariff_version, change_reason) "
                        + "values (:id, :c, :car, :lvl, :p, :m, :a, :v, :r)")
                .param("id", id).param("c", conceptId).param("car", careerId).param("lvl", levelNumber).param("p", periodId).param("m", calcMode)
                .param("a", amount).param("v", version).param("r", reason).update();
        return id;
    }

    /** Conciliación: cada línea del extracto (referencia;monto;fecha) se cruza con los pagos confirmados. */
    @Transactional
    public ReconciliationResult reconcile(String source, LocalDate statementDate, InputStream csv) {
        List<String[]> lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            lines = reader.lines().filter(l -> !l.isBlank()).skip(1).map(l -> l.split("[;,]")).toList();
        } catch (IOException e) {
            throw new BusinessRuleException("RN-29", "No se pudo leer el extracto");
        }
        record Item(int line, String reference, BigDecimal amount, UUID paymentId, String outcome) {
        }
        List<Item> items = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int lineNumber = 1;
        for (String[] cols : lines) {
            lineNumber++;
            String reference = cols[0].trim();
            BigDecimal amount;
            try {
                amount = new BigDecimal(cols.length > 1 ? cols[1].trim() : "0");
            } catch (NumberFormatException e) {
                throw new BusinessRuleException("RN-29", "Línea " + lineNumber + ": monto inválido");
            }
            var payment = jdbc.sql("select id, amount from payment where gateway_reference = :r and status = 'CONFIRMED'").param("r", reference)
                    .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBigDecimal(2)}).optional();
            String outcome = !seen.add(reference) ? "DUPLICATE"
                    : payment.isEmpty() ? "NOT_FOUND"
                    : ((BigDecimal) payment.get()[1]).compareTo(amount) != 0 ? "AMOUNT_MISMATCH" : "MATCHED";
            items.add(new Item(lineNumber, reference, amount, payment.map(p -> (UUID) p[0]).orElse(null), outcome));
        }
        int matched = (int) items.stream().filter(i -> "MATCHED".equals(i.outcome())).count();
        UUID id = UuidV7.next(clock);
        jdbc.sql("insert into reconciliation (id, source, statement_date, total_rows, matched, discrepancies, processed_by) values (:id, :s, :d, :t, :m, :x, app_actor())")
                .param("id", id).param("s", source).param("d", statementDate).param("t", items.size()).param("m", matched)
                .param("x", items.size() - matched).update();
        for (Item i : items) {
            jdbc.sql("""
                            insert into reconciliation_item (id, reconciliation_id, line_number, external_reference, amount, payment_id, outcome)
                            values (:id, :r, :ln, :ref, :a, :p, :o)""")
                    .param("id", UuidV7.next(clock)).param("r", id).param("ln", i.line()).param("ref", i.reference()).param("a", i.amount())
                    .param("p", i.paymentId()).param("o", i.outcome()).update();
        }
        List<String> notes = items.stream().filter(i -> !"MATCHED".equals(i.outcome()))
                .map(i -> "Línea " + i.line() + ": " + i.outcome() + " (" + i.reference() + ")").toList();
        return new ReconciliationResult(id, items.size(), matched, items.size() - matched, notes);
    }
}
