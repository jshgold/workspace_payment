package com.payment.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.product.domain.Product;
import com.payment.product.domain.ProductStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderTest {

    private Product product(int stock) {
        Product product = Product.create("키보드", 10_000, stock);
        ReflectionTestUtils.setField(product, "id", 7L);
        return product;
    }

    @Test
    @DisplayName("판매중이고 재고가 충분하면 PENDING 주문이 만들어지고 금액은 가격×수량으로 서버가 계산한다")
    void createSuccess() {
        // given
        Product product = product(5);

        // when
        Order order = Order.create(1L, product, 3);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalPrice()).isEqualTo(30_000L);
        assertThat(order.getProductId()).isEqualTo(7L);
        assertThat(order.getQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("주문을 만들어도 상품 재고는 차감되지 않는다")
    void createDoesNotDeductStock() {
        // given
        Product product = product(5);

        // when
        Order.create(1L, product, 5);

        // then
        assertThat(product.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("재고보다 많이 주문하면 IllegalStateException이 발생한다")
    void createFailsWhenStockShort() {
        // given
        Product product = product(2);

        // when // then
        assertThatThrownBy(() -> Order.create(1L, product, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고가 부족합니다");
    }

    @Test
    @DisplayName("품절 상품은 주문할 수 없다")
    void createFailsWhenSoldOut() {
        // given
        Product product = product(0);

        // when // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThatThrownBy(() -> Order.create(1L, product, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("판매중인 상품이 아닙니다");
    }

    @Test
    @DisplayName("판매중지 상품은 재고가 있어도 주문할 수 없다")
    void createFailsWhenStopped() {
        // given
        Product product = product(5);
        product.changeStatus(ProductStatus.STOPPED);

        // when // then
        assertThatThrownBy(() -> Order.create(1L, product, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("판매중인 상품이 아닙니다");
    }

    @Test
    @DisplayName("삭제된 상품은 주문할 수 없다")
    void createFailsWhenDeleted() {
        // given
        Product product = product(5);
        product.delete();

        // when // then
        assertThatThrownBy(() -> Order.create(1L, product, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("수량이 0 이하이거나 회원 ID가 없으면 IllegalArgumentException이 발생한다")
    void createFailsWithInvalidArguments() {
        // given
        Product product = product(5);

        // when // then
        assertThatThrownBy(() -> Order.create(1L, product, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.create(null, product, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
