package com.payment.payment.controller;

import com.payment.payment.domain.IdempotencyKey;
import com.payment.payment.dto.ApprovePaymentRequest;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentApprovalFacade;

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
public class PaymentController {

    private final PaymentApprovalFacade paymentApprovalFacade;

    /** 같은 결제 시도의 재전송은 같은 {@code Idempotency-Key}를 보내야 하고, 새 시도는 새 키를 만든다. */
    @PostMapping("/approve")
    public ResponseEntity<PaymentResponse> approve(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key 헤더는 필수입니다.")
            @Size(max = IdempotencyKey.KEY_MAX_LENGTH, message = "Idempotency-Key는 100자 이하여야 합니다.")
            String idempotencyKey,
            @Valid @RequestBody ApprovePaymentRequest request) {
        return ResponseEntity.ok(
                paymentApprovalFacade.approve(orderId, idempotencyKey, request.amount(), request.paymentKey()));
    }
}
