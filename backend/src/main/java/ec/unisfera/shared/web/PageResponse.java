package ec.unisfera.shared.web;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResponse<T> of(List<T> items, PageQuery query, long total) {
        return new PageResponse<>(items, query.page(), query.size(), total);
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(items.stream().map(mapper).toList(), page, size, total);
    }
}
