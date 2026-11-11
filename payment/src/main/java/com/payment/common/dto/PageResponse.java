package com.payment.common.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Page(PageImpl)를 그대로 JSON으로 직렬화하면 응답 구조가 Spring Data 내부 구현에 묶인다.
 * API 응답은 우리가 정한 형태의 record로 내려준다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
