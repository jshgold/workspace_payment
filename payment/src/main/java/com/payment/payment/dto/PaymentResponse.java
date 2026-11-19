package com.payment.payment.dto;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;

public record PaymentResponse(
        Long orderId,
        Long paymentId,
        String paymentKey,
        long amount,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus
) {

    public static PaymentResponse of(Order order, Payment payment) {
        return new PaymentResponse(
                order.getId(),
                payment.getId(),
                payment.getPaymentKey(),
                payment.getAmount(),
                order.getStatus(),
                payment.getStatus()
        );
    }
}
