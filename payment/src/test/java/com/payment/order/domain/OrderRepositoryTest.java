package com.payment.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.support.IntegrationTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Flyway V3 스키마가 ddl-auto: validate를 통과하고, 주문이 실제 DB에 저장되는지 확인한다. */
@Transactional
class OrderRepositoryTest extends IntegrationTestSupport {

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    ProductRepository productRepository;

    @Test
    @DisplayName("주문을 저장하면 PENDING 상태와 서버 계산 금액이 DB에 기록된다")
    void saveOrder() {
        // given
        Product product = productRepository.save(Product.create("키보드", 10_000, 5));

        // when
        Order saved = orderRepository.saveAndFlush(Order.create(100L, product, 3));

        // then
        Order found = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(found.getTotalPrice()).isEqualTo(30_000L);
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
