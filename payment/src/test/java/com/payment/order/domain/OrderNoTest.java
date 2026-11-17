package com.payment.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.payment.product.domain.Product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderNoTest {

    private Product product() {
        Product product = Product.create("키보드", 10_000, 5);
        ReflectionTestUtils.setField(product, "id", 7L);
        return product;
    }

    @Test
    @DisplayName("주문을 만들면 UUIDv7 형식의 주문번호가 부여된다")
    void createAssignsOrderNo() {
        // when
        Order order = Order.create(1L, product(), 1);

        // then
        assertThat(order.getOrderNo()).hasSize(36);
        assertThat(UUID.fromString(order.getOrderNo()).version()).isEqualTo(7);
    }

    @Test
    @DisplayName("주문마다 서로 다른 주문번호가 부여된다")
    void orderNoIsUniquePerOrder() {
        // given
        Product product = product();

        // when
        Order first = Order.create(1L, product, 1);
        Order second = Order.create(1L, product, 1);

        // then
        assertThat(first.getOrderNo()).isNotEqualTo(second.getOrderNo());
    }
}
