package ec.unisfera.finance.application;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import ec.unisfera.finance.domain.AccessKeyGenerator;
import ec.unisfera.parameters.application.ParameterKeys;
import ec.unisfera.parameters.application.ParameterService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Factura electrónica y nota de crédito según la ficha técnica off-line del SRI: secuencial sin
 * huecos por punto de emisión (bloqueo de fila), clave de acceso de 49 dígitos con módulo 11,
 * XML del comprobante y RIDE en PDF. En ambiente de pruebas la autorización se simula; en el
 * esquema off-line el número de autorización es la propia clave de acceso.
 */
@Service
public class InvoiceService {

    public record InvoiceView(UUID id, String number, String accessKey, LocalDate issueDate, String buyerName, BigDecimal total,
                              String sriStatus, String authorizationNumber) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVOICE = "01";
    private static final String CREDIT_NOTE = "04";
    private static final int NUMERIC_CODE_BOUND = 100_000_000;
    private static final DateTimeFormatter SRI_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcClient jdbc;
    private final ParameterService parameters;
    private final Clock clock;
    private final AppProperties props;

    public InvoiceService(JdbcClient jdbc, ParameterService parameters, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.clock = clock;
        this.props = props;
    }

    private record Issuer(String ruc, String legalName, String name, String address, String establishment, String emissionPoint) {
    }

    private record Buyer(String idType, String idNumber, String name, String email) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID issueForPayment(UUID paymentId) {
        var payment = jdbc.sql("select student_id, amount, status from payment where id = :id").param("id", paymentId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3)}).single();
        if (!"CONFIRMED".equals(payment[2])) {
            throw new BusinessRuleException("RN-28", "Solo se factura un pago confirmado");
        }
        Issuer issuer = issuer();
        Buyer buyer = buyer((UUID) payment[0]);
        LocalDate today = LocalDate.now(clock.withZone(props.zoneId()));
        long sequential = nextSequential(issuer, INVOICE);
        String environment = parameters.text(ParameterKeys.SRI_ENVIRONMENT);
        String accessKey = AccessKeyGenerator.generate(today, INVOICE, issuer.ruc(), environment, issuer.establishment(), issuer.emissionPoint(),
                sequential, numericCode());
        BigDecimal amount = (BigDecimal) payment[1];
        BigDecimal rate = parameters.decimal(ParameterKeys.IVA_EDUCATION);
        String xml = invoiceXml(issuer, buyer, today, sequential, accessKey, environment, amount, rate);
        UUID id = UuidV7.next(clock);
        boolean testEnvironment = "1".equals(environment);
        jdbc.sql("""
                        insert into invoice (id, payment_id, establishment, emission_point, sequential, access_key, environment, issue_date,
                                             buyer_id_type, buyer_id, buyer_name, subtotal_zero, subtotal_taxed, tax_amount, total,
                                             sri_status, authorization_number, authorized_at, xml)
                        values (:id, :pay, :est, :pe, :seq, :key, :env, :date, :bt, :bid, :bn, :sz, 0, 0, :total, :st, :auth, :authAt, :xml)""")
                .param("id", id).param("pay", paymentId).param("est", issuer.establishment()).param("pe", issuer.emissionPoint())
                .param("seq", sequential).param("key", accessKey).param("env", environment).param("date", today)
                .param("bt", buyer.idType()).param("bid", buyer.idNumber()).param("bn", buyer.name()).param("sz", amount).param("total", amount)
                .param("st", testEnvironment ? "AUTHORIZED" : "GENERATED").param("auth", testEnvironment ? accessKey : null)
                .param("authAt", testEnvironment ? java.sql.Timestamp.from(clock.instant()) : null).param("xml", xml).update();
        return id;
    }

    /** RN-28: la anulación de un pago facturado se respalda con una nota de crédito. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID creditNoteForPayment(UUID paymentId, String reason) {
        var invoice = jdbc.sql("select id, total, establishment, emission_point, sequential, issue_date from invoice where payment_id = :p")
                .param("p", paymentId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                        rs.getDate(6).toLocalDate()})
                .optional().orElse(null);
        if (invoice == null) {
            return null;
        }
        Issuer issuer = issuer();
        LocalDate today = LocalDate.now(clock.withZone(props.zoneId()));
        long sequential = nextSequential(issuer, CREDIT_NOTE);
        String environment = parameters.text(ParameterKeys.SRI_ENVIRONMENT);
        String accessKey = AccessKeyGenerator.generate(today, CREDIT_NOTE, issuer.ruc(), environment, issuer.establishment(), issuer.emissionPoint(),
                sequential, numericCode());
        String xml = "<notaCredito id=\"comprobante\" version=\"1.1.0\"><infoTributaria><ambiente>" + environment + "</ambiente><ruc>" + issuer.ruc()
                + "</ruc><claveAcceso>" + accessKey + "</claveAcceso><codDoc>04</codDoc><estab>" + issuer.establishment() + "</estab><ptoEmi>"
                + issuer.emissionPoint() + "</ptoEmi><secuencial>" + String.format("%09d", sequential) + "</secuencial></infoTributaria>"
                + "<infoNotaCredito><fechaEmision>" + today.format(SRI_DATE) + "</fechaEmision><codDocModificado>01</codDocModificado>"
                + "<numDocModificado>" + invoice[2] + "-" + invoice[3] + "-" + String.format("%09d", (long) invoice[4]) + "</numDocModificado>"
                + "<valorModificacion>" + invoice[1] + "</valorModificacion><motivo>" + escape(reason) + "</motivo></infoNotaCredito></notaCredito>";
        UUID id = UuidV7.next(clock);
        jdbc.sql("""
                        insert into credit_note (id, invoice_id, payment_id, establishment, emission_point, sequential, access_key, issue_date,
                                                 reason, amount, sri_status, xml)
                        values (:id, :inv, :pay, :est, :pe, :seq, :key, :date, :r, :a, :st, :xml)""")
                .param("id", id).param("inv", invoice[0]).param("pay", paymentId).param("est", issuer.establishment())
                .param("pe", issuer.emissionPoint()).param("seq", sequential).param("key", accessKey).param("date", today).param("r", reason)
                .param("a", invoice[1]).param("st", "1".equals(environment) ? "AUTHORIZED" : "GENERATED").param("xml", xml).update();
        jdbc.sql("update invoice set sri_status = 'ANNULLED' where id = :id").param("id", invoice[0]).update();
        return id;
    }

    @Transactional(readOnly = true)
    public InvoiceView view(UUID invoiceId) {
        return jdbc.sql("""
                        select id, establishment || '-' || emission_point || '-' || lpad(sequential::text, 9, '0') num, access_key, issue_date,
                               buyer_name, total, sri_status, authorization_number from invoice where id = :id""")
                .param("id", invoiceId)
                .query((rs, n) -> new InvoiceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate(),
                        rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8)))
                .optional().orElseThrow(() -> new NotFoundException("La factura"));
    }

    public UUID studentOfInvoice(UUID invoiceId) {
        return jdbc.sql("select p.student_id from invoice i join payment p on p.id = i.payment_id where i.id = :id").param("id", invoiceId)
                .query(UUID.class).optional().orElseThrow(() -> new NotFoundException("La factura"));
    }

    /** RIDE: representación impresa del documento electrónico. */
    @Transactional(readOnly = true)
    public byte[] ride(UUID invoiceId) {
        InvoiceView v = view(invoiceId);
        Issuer issuer = issuer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
        doc.add(new Paragraph(issuer.legalName(), title));
        doc.add(new Paragraph("RUC: " + issuer.ruc() + "   ·   " + issuer.address(), normal));
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("FACTURA No. " + v.number(), title));
        doc.add(new Paragraph("Número de autorización / clave de acceso:", normal));
        doc.add(new Paragraph(v.accessKey(), FontFactory.getFont(FontFactory.COURIER_BOLD, 11)));
        doc.add(new Paragraph("Estado SRI: " + v.sriStatus() + "   ·   Fecha de emisión: " + v.issueDate().format(SRI_DATE), normal));
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Cliente: " + v.buyerName(), normal));
        PdfPTable table = new PdfPTable(new float[]{4, 1, 1});
        table.setWidthPercentage(100);
        table.addCell("Descripción");
        table.addCell("IVA");
        table.addCell("Total");
        table.addCell("Servicios educativos");
        table.addCell(parameters.decimal(ParameterKeys.IVA_EDUCATION).stripTrailingZeros().toPlainString() + " %");
        table.addCell(v.total().toPlainString());
        doc.add(table);
        Paragraph total = new Paragraph("TOTAL: " + parameters.text(ParameterKeys.CURRENCY) + " " + v.total().toPlainString(), title);
        total.setAlignment(Element.ALIGN_RIGHT);
        doc.add(total);
        doc.add(new Paragraph("Documento generado en ambiente de PRUEBAS: sin validez tributaria.", normal));
        doc.close();
        return out.toByteArray();
    }

    private long nextSequential(Issuer issuer, String docType) {
        jdbc.sql("insert into invoice_sequence (establishment, emission_point, doc_type, next_value) values (:e, :p, :d, 1) on conflict do nothing")
                .param("e", issuer.establishment()).param("p", issuer.emissionPoint()).param("d", docType).update();
        return jdbc.sql("""
                        update invoice_sequence set next_value = next_value + 1
                        where establishment = :e and emission_point = :p and doc_type = :d returning next_value - 1""")
                .param("e", issuer.establishment()).param("p", issuer.emissionPoint()).param("d", docType).query(Long.class).single();
    }

    private Issuer issuer() {
        return jdbc.sql("select ruc, legal_name, name, address, sri_establishment, sri_emission_point from institution limit 1")
                .query((rs, n) -> new Issuer(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional().orElseThrow(() -> new BusinessRuleException("RN-28", "Configure la institución emisora antes de facturar"));
    }

    private Buyer buyer(UUID studentId) {
        return jdbc.sql("""
                        select p.id_type, p.id_number, p.first_names || ' ' || p.last_names, s.institutional_email
                        from student s join person p on p.id = s.person_id where s.id = :s""")
                .param("s", studentId)
                .query((rs, n) -> new Buyer("CEDULA".equals(rs.getString(1)) ? "05" : "06", rs.getString(2), rs.getString(3), rs.getString(4)))
                .single();
    }

    private static String numericCode() {
        return String.format("%08d", RANDOM.nextInt(NUMERIC_CODE_BOUND));
    }

    private String invoiceXml(Issuer issuer, Buyer buyer, LocalDate date, long sequential, String accessKey, String environment,
                              BigDecimal amount, BigDecimal rate) {
        return "<factura id=\"comprobante\" version=\"1.1.0\"><infoTributaria><ambiente>" + environment + "</ambiente><tipoEmision>1</tipoEmision>"
                + "<razonSocial>" + escape(issuer.legalName()) + "</razonSocial><nombreComercial>" + escape(issuer.name()) + "</nombreComercial>"
                + "<ruc>" + issuer.ruc() + "</ruc><claveAcceso>" + accessKey + "</claveAcceso><codDoc>01</codDoc><estab>" + issuer.establishment()
                + "</estab><ptoEmi>" + issuer.emissionPoint() + "</ptoEmi><secuencial>" + String.format("%09d", sequential) + "</secuencial>"
                + "<dirMatriz>" + escape(issuer.address()) + "</dirMatriz></infoTributaria><infoFactura><fechaEmision>" + date.format(SRI_DATE)
                + "</fechaEmision><tipoIdentificacionComprador>" + buyer.idType() + "</tipoIdentificacionComprador><razonSocialComprador>"
                + escape(buyer.name()) + "</razonSocialComprador><identificacionComprador>" + buyer.idNumber() + "</identificacionComprador>"
                + "<totalSinImpuestos>" + amount + "</totalSinImpuestos><totalDescuento>0.00</totalDescuento><totalConImpuestos><totalImpuesto>"
                + "<codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje><baseImponible>" + amount + "</baseImponible><tarifa>"
                + rate.stripTrailingZeros().toPlainString() + "</tarifa><valor>0.00</valor></totalImpuesto></totalConImpuestos>"
                + "<importeTotal>" + amount + "</importeTotal><moneda>DOLAR</moneda></infoFactura><detalles><detalle>"
                + "<descripcion>Servicios educativos</descripcion><cantidad>1</cantidad><precioUnitario>" + amount + "</precioUnitario>"
                + "<descuento>0.00</descuento><precioTotalSinImpuesto>" + amount + "</precioTotalSinImpuesto></detalle></detalles></factura>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
