package com.payment.payment.controller;

import com.payment.payment.domain.Payment;
import com.payment.payment.dto.CancelPaymentRequest;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentCancelFacade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/{orderId}/payments")
@RequiredArgsConstructor
public class PaymentCancelController {

    private final PaymentCancelFacade paymentCancelFacade;

    /**
     * 같은 취소 요청의 재전송은 같은 {@code Idempotency-Key}를 보내야 하고, 새 취소 시도는 새 키를 만든다.
     * 중복 PG 호출은 주문 상태 선점이 막고, 키는 재전송에 같은 결과를 돌려주고 같은 키로 내용이 바뀐 요청을 거절한다. (phase-04 4-3)
     */
    @PostMapping("/cancel")
    public ResponseEntity<PaymentResponse> cancel(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key 헤더는 필수입니다.")
            @Size(max = Payment.IDEMPOTENCY_KEY_MAX_LENGTH, message = "Idempotency-Key는 100자 이하여야 합니다.")
            String idempotencyKey,
            @Valid @RequestBody CancelPaymentRequest request) {
        return ResponseEntity.ok(paymentCancelFacade.cancel(orderId, idempotencyKey, request.reason()));
    }
}
