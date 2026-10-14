package ec.unisfera.shared.web;

import ec.unisfera.shared.domain.BusinessRuleException;

/** Paginación obligatoria: ningún endpoint devuelve listas sin límite. */
public record PageQuery(int page, int size) {

    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new BusinessRuleException("PAGINATION", "La página debe ser ≥ 0 y el tamaño entre 1 y " + MAX_SIZE);
        }
    }

    public static PageQuery of(int page, int size) {
        return new PageQuery(page, size);
    }

    public int offset() {
        return page * size;
    }
}
