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

    @Test
    @DisplayName("PENDING 주문은 결제를 시작하면 PAYING이 되고, PAYING이 PAID로 바뀐다")
    void paymentTransitions() {
        // given
        Order order = Order.create(1L, product(5), 1);

        // when
        order.startPaying();
        OrderStatus afterStart = order.getStatus();
        order.markPaid();

        // then
        assertThat(afterStart).isEqualTo(OrderStatus.PAYING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("이미 PAYING이거나 PAID인 주문은 결제를 다시 시작할 수 없다")
    void startPayingFailsWhenNotPending() {
        // given
        Order paying = Order.create(1L, product(5), 1);
        paying.startPaying();
        Order paid = Order.create(1L, product(5), 1);
        paid.startPaying();
        paid.markPaid();

        // when // then
        assertThatThrownBy(paying::startPaying).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(paid::startPaying).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PAYING이 아닌 주문은 결제 완료·실패·결과 모름으로 바꿀 수 없다")
    void payingTransitionsFailWhenNotPaying() {
        // given
        Order pending = Order.create(1L, product(5), 1);

        // when // then
        assertThatThrownBy(pending::markPaid).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(pending::markFailed).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(pending::markPaymentUnknown).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PAID 주문은 취소를 시작하면 CANCELING이 되고, CANCELING이 CANCELED로 바뀐다")
    void cancelTransitions() {
        // given
        Order order = paidOrder();

        // when
        order.startCanceling();
        OrderStatus afterStart = order.getStatus();
        order.markCanceled();

        // then
        assertThat(afterStart).isEqualTo(OrderStatus.CANCELING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("PAID가 아닌 주문(PENDING, CANCELING, CANCELED)은 취소를 시작할 수 없다")
    void startCancelingFailsWhenNotPaid() {
        // given
        Order pending = Order.create(1L, product(5), 1);
        Order canceling = paidOrder();
        canceling.startCanceling();
        Order canceled = paidOrder();
        canceled.startCanceling();
        canceled.markCanceled();

        // when // then
        assertThatThrownBy(pending::startCanceling).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(canceling::startCanceling).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(canceled::startCanceling).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CANCELING이 아닌 주문은 취소 완료 처리하거나 PAID로 되돌릴 수 없다")
    void markCanceledAndRevertFailWhenNotCanceling() {
        // given
        Order paid = paidOrder();

        // when // then
        assertThatThrownBy(paid::markCanceled).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(paid::revertToPaid).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CANCELING 주문은 PAID로 되돌려 다시 취소를 시작할 수 있다")
    void revertToPaid() {
        // given
        Order order = paidOrder();
        order.startCanceling();

        // when
        order.revertToPaid();
        order.startCanceling();

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELING);
    }

    private Order paidOrder() {
        Order order = Order.create(1L, product(5), 1);
        order.startPaying();
        order.markPaid();
        return order;
    }

    @Test
    @DisplayName("PAYING 주문이 실패로 확정되면 FAILED가 되고, 결제 시도 이전(PENDING)으로 돌아가지 않으므로 다시 결제를 시작할 수 없다")
    void failedOrderCannotStartPayingAgain() {
        // given
        Order order = Order.create(1L, product(5), 1);
        order.startPaying();

        // when
        order.markFailed();

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThatThrownBy(order::startPaying).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PAYING 주문의 결과를 모르면 PAYMENT_UNKNOWN이 되고, 그 상태에서 다시 결제를 시작할 수 없다")
    void paymentUnknown() {
        // given
        Order order = Order.create(1L, product(5), 1);
        order.startPaying();

        // when
        order.markPaymentUnknown();

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
        assertThatThrownBy(order::startPaying).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PENDING 주문은 PG 호출 전에 거절되면 바로 FAILED가 되고, PENDING이 아니면 거절할 수 없다")
    void rejectBeforePaying() {
        // given
        Order pending = Order.create(1L, product(5), 1);
        Order paying = Order.create(1L, product(5), 1);
        paying.startPaying();

        // when
        pending.rejectBeforePaying();

        // then
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThatThrownBy(paying::rejectBeforePaying).isInstanceOf(IllegalStateException.class);
    }
}
