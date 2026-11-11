package com.payment.product.dto;

import java.time.LocalDateTime;

import com.payment.product.domain.Product;
import com.payment.product.domain.ProductStatus;

public record ProductResponse(
        Long id,
        String name,
        long price,
        int stock,
        ProductStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStock(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
