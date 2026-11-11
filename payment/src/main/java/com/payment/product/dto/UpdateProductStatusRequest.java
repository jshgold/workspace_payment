package com.payment.product.dto;

import com.payment.product.domain.ProductStatus;

import jakarta.validation.constraints.NotNull;

public record UpdateProductStatusRequest(
        @NotNull(message = "판매 상태는 필수입니다.")
        ProductStatus status
) {
}
