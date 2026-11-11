package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.domain.ProductStatus;
import com.payment.product.dto.CreateProductRequest;
import com.payment.product.dto.ProductResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    ProductRepository productRepository;

    @InjectMocks
    ProductService productService;

    @Test
    @DisplayName("상품을 등록하면 저장하고 판매중 상태의 응답을 반환한다")
    void create() {
        // given
        CreateProductRequest request = new CreateProductRequest("키보드", 10_000L, 5);
        given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        ProductResponse response = productService.create(request);

        // then
        assertThat(response.name()).isEqualTo("키보드");
        assertThat(response.price()).isEqualTo(10_000L);
        assertThat(response.stock()).isEqualTo(5);
        assertThat(response.status()).isEqualTo(ProductStatus.ON_SALE);
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("재고 0으로 등록하면 품절 상태로 저장된다")
    void createWithZeroStock() {
        // given
        CreateProductRequest request = new CreateProductRequest("키보드", 10_000L, 0);
        given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        ProductResponse response = productService.create(request);

        // then
        assertThat(response.status()).isEqualTo(ProductStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("도메인 규칙을 위반한 요청이면 저장하지 않고 예외가 난다")
    void createFailsWithoutSaving() {
        // given
        CreateProductRequest request = new CreateProductRequest("키보드", 0L, 5);

        // when // then
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productRepository, never()).save(any());
    }
}
