package com.payment.order.dto;

import java.time.LocalDateTime;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderStatus;

public record OrderResponse(
        Long id,
        Long memberId,
        Long productId,
        int quantity,
        long totalPrice,
        OrderStatus status,
        LocalDateTime createdAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getMemberId(),
                order.getProductId(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}
