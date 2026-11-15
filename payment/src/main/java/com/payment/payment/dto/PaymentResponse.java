package com.payment.payment.dto;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;

public record PaymentResponse(
        Long orderId,
        Long paymentId,
        String pgTransactionId,
        long amount,
        OrderStatus orderStatus
) {

    public static PaymentResponse of(Order order, Payment payment) {
        return new PaymentResponse(
                order.getId(),
                payment.getId(),
                payment.getPgTransactionId(),
                payment.getAmount(),
                order.getStatus()
        );
    }
}
