package ec.unisfera.reporting.application;

import ec.unisfera.reporting.domain.ReportTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

/** Exportación de reportes a Excel (streaming, memoria acotada) y PDF. */
@Service
public class ExportService {

    private static final int WINDOW = 200;

    public byte[] xlsx(ReportTable table) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(table.code());
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            int r = 0;
            sheet.createRow(r++).createCell(0).setCellValue(table.title());
            sheet.createRow(r++).createCell(0).setCellValue("Fecha de corte: " + table.cutoff());
            Row header = sheet.createRow(r++);
            for (int i = 0; i < table.headers().size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(table.headers().get(i));
                c.setCellStyle(bold);
            }
            for (var row : table.rows()) {
                Row x = sheet.createRow(r++);
                for (int i = 0; i < row.size(); i++) {
                    Object v = row.get(i);
                    Cell c = x.createCell(i);
                    if (v instanceof Number n) {
                        c.setCellValue(n instanceof BigDecimal b ? b.doubleValue() : n.doubleValue());
                    } else {
                        c.setCellValue(v == null ? "" : v.toString());
                    }
                }
            }
            for (String note : table.notes()) {
                sheet.createRow(r++).createCell(0).setCellValue(note);
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] pdf(ReportTable table) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(table.headers().size() > 6 ? PageSize.A4.rotate() : PageSize.A4);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("UNISFERA · " + table.title(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        doc.add(new Paragraph("Fecha de corte: " + table.cutoff(), FontFactory.getFont(FontFactory.HELVETICA, 9)));
        doc.add(new Paragraph(" "));
        PdfPTable pdfTable = new PdfPTable(table.headers().size());
        pdfTable.setWidthPercentage(100);
        org.openpdf.text.Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        org.openpdf.text.Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);
        table.headers().forEach(h -> pdfTable.addCell(new PdfPCell(new Phrase(h, head))));
        table.rows().forEach(row -> row.forEach(v -> pdfTable.addCell(new PdfPCell(new Phrase(v == null ? "" : v.toString(), body)))));
        doc.add(pdfTable);
        for (String note : table.notes()) {
            doc.add(new Paragraph(note, body));
        }
        doc.close();
        return out.toByteArray();
    }
}
