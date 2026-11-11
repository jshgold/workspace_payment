package com.payment.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 숫자 필드를 래퍼 타입(Long, Integer)으로 받는 이유: 원시 타입이면 필드가 누락됐을 때 0으로 채워져
 * "값을 안 보냄"과 "0을 보냄"을 구분할 수 없다. 래퍼 타입 + @NotNull로 누락을 잡는다.
 */
public record CreateProductRequest(
        @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 100, message = "상품명은 100자 이하여야 합니다.")
        String name,

        @NotNull(message = "가격은 필수입니다.")
        @Positive(message = "가격은 0보다 커야 합니다.")
        Long price,

        @NotNull(message = "재고는 필수입니다.")
        @PositiveOrZero(message = "재고는 0 이상이어야 합니다.")
        Integer stock
) {
}
