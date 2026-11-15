package com.payment.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 클라이언트가 결제창에서 본 금액. 믿지 않고, 서버가 주문에서 계산해 둔 금액과 비교하는 용도로만 쓴다. */
public record ApprovePaymentRequest(
        @NotNull(message = "결제 금액은 필수입니다.")
        @Positive(message = "결제 금액은 0보다 커야 합니다.")
        Long amount
) {
}
