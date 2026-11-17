package com.payment.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 결제 승인 요청.
 * <ul>
 *   <li>amount: 클라이언트가 결제창에서 본 금액. 믿지 않고, 서버가 주문에서 계산해 둔 금액과 비교하는 용도로만 쓴다.</li>
 *   <li>paymentKey: 결제창 인증 후 PG가 발급한 결제 식별자. PG 승인 호출에 그대로 쓴다. (최대 200자)</li>
 * </ul>
 */
public record ApprovePaymentRequest(
        @NotNull(message = "결제 금액은 필수입니다.")
        @Positive(message = "결제 금액은 0보다 커야 합니다.")
        Long amount,

        @NotBlank(message = "결제 키(paymentKey)는 필수입니다.")
        @Size(max = 200, message = "결제 키는 200자 이하여야 합니다.")
        String paymentKey
) {
}
