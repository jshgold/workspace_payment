package com.payment.payment.controller;

import com.payment.payment.dto.CancelPaymentRequest;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentCancelFacade;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/{orderId}/payments")
@RequiredArgsConstructor
public class PaymentCancelController {

    private final PaymentCancelFacade paymentCancelFacade;

    /** 취소는 PAID 주문에서만 한 번 성공한다. 상태 선점이 멱등성 장치이므로 Idempotency-Key는 받지 않는다. */
    @PostMapping("/cancel")
    public ResponseEntity<PaymentResponse> cancel(
            @PathVariable Long orderId,
            @Valid @RequestBody CancelPaymentRequest request) {
        return ResponseEntity.ok(paymentCancelFacade.cancel(orderId, request.reason()));
    }
}
