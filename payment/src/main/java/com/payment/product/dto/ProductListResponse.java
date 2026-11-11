package com.payment.product.dto;

import com.payment.product.domain.ProductStatus;

/** 목록 화면용 요약 DTO. 엔티티를 거치지 않고 필요한 컬럼만 직접 조회(프로젝션)한다. */
public record ProductListResponse(
        Long id,
        String name,
        long price,
        int stock,
        ProductStatus status
) {
}
