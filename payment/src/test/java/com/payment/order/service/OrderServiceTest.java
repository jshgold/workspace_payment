package com.payment.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.order.dto.CreateOrderRequest;
import com.payment.order.dto.OrderResponse;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.domain.ProductStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    OrderRepository orderRepository;

    @Mock
    ProductRepository productRepository;

    @InjectMocks
    OrderService orderService;

    @Test
    @DisplayName("주문을 생성하면 서버가 계산한 금액의 PENDING 주문을 저장하고 응답한다")
    void create() {
        // given
        given(productRepository.findById(1L)).willReturn(Optional.of(Product.create("키보드", 10_000, 5)));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        OrderResponse response = orderService.create(new CreateOrderRequest(100L, 1L, 2));

        // then
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalPrice()).isEqualTo(20_000L);
        assertThat(response.memberId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("없는 상품이면 PRODUCT_NOT_FOUND 예외가 발생하고 저장하지 않는다")
    void createFailsWhenProductMissing() {
        // given
        given(productRepository.findById(1L)).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(100L, 1L, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("소프트 삭제된 상품은 없는 상품으로 취급한다")
    void createFailsWhenProductDeleted() {
        // given
        Product product = Product.create("키보드", 10_000, 5);
        product.delete();
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when // then
        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(100L, 1L, 1)))
                .isInstanceOf(BusinessException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("판매중이 아닌 상품이면 주문이 실패하고 저장하지 않는다")
    void createFailsWhenNotOnSale() {
        // given
        Product product = Product.create("키보드", 10_000, 5);
        product.changeStatus(ProductStatus.STOPPED);
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when // then
        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(100L, 1L, 1)))
                .isInstanceOf(IllegalStateException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("재고가 부족하면 주문이 실패하고 저장하지 않는다")
    void createFailsWhenStockShort() {
        // given
        given(productRepository.findById(1L)).willReturn(Optional.of(Product.create("키보드", 10_000, 1)));

        // when // then
        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(100L, 1L, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고가 부족합니다");
        verify(orderRepository, never()).save(any());
    }
}
