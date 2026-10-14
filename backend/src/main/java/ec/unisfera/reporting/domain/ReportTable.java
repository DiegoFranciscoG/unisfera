package ec.unisfera.reporting.domain;

import java.util.ArrayList;
import java.util.List;

/** Tabla genérica de un reporte (encabezados + filas) lista para mostrarse o exportarse. */
public record ReportTable(String code, String title, String cutoff, List<String> headers, List<List<Object>> rows, List<String> notes) {

    /**
     * RN-23: supresión de celdas pequeñas. En reportes agregados, un conteo menor al umbral
     * permitiría identificar personas: se reemplaza por "&lt; n".
     */
    public ReportTable suppressSmallCells(List<Integer> countColumns, int threshold) {
        List<List<Object>> safe = new ArrayList<>();
        boolean suppressed = false;
        for (List<Object> row : rows) {
            List<Object> copy = new ArrayList<>(row);
            for (int col : countColumns) {
                Object v = copy.get(col);
                if (v instanceof Number n && n.longValue() > 0 && n.longValue() < threshold) {
                    copy.set(col, "< " + threshold);
                    suppressed = true;
                }
            }
            safe.add(copy);
        }
        List<String> newNotes = new ArrayList<>(notes);
        if (suppressed) {
            newNotes.add("Se suprimieron celdas con menos de " + threshold + " personas para proteger datos personales (LOPDP).");
        }
        return new ReportTable(code, title, cutoff, headers, safe, newNotes);
    }
}
