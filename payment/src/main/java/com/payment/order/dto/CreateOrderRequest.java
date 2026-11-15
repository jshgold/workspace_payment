package com.payment.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 금액 필드는 의도적으로 없다. 결제 금액은 서버가 상품 가격 × 수량으로 계산한다. */
public record CreateOrderRequest(
        @NotNull(message = "회원 ID는 필수입니다.")
        @Positive(message = "회원 ID는 0보다 커야 합니다.")
        Long memberId,

        @NotNull(message = "상품 ID는 필수입니다.")
        @Positive(message = "상품 ID는 0보다 커야 합니다.")
        Long productId,

        @NotNull(message = "주문 수량은 필수입니다.")
        @Positive(message = "주문 수량은 0보다 커야 합니다.")
        Integer quantity
) {
}
