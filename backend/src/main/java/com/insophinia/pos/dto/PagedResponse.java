package com.insophinia.pos.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedResponse<T>(boolean success, List<T> data, Pagination pagination) {

    public record Pagination(int page, int limit, long total, int pages) {}

    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(true, page.getContent(),
                new Pagination(page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }
}
