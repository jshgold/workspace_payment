package com.payment.order.dto;

import java.time.LocalDateTime;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderStatus;

public record OrderResponse(
        Long id,
        /** 외부 노출용 주문번호. 결제창을 열 때 PG에 넘기는 orderId와 같은 값이어야 한다. */
        String orderNo,
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
                order.getOrderNo(),
                order.getMemberId(),
                order.getProductId(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}
