package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.domain.ProductStatus;
import com.payment.product.dto.CreateProductRequest;
import com.payment.product.dto.ProductResponse;
import com.payment.product.dto.UpdateProductRequest;
import com.payment.product.dto.UpdateProductStatusRequest;

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

    @Test
    @DisplayName("존재하는 상품을 조회하면 상품 정보를 반환한다")
    void getProduct() {
        // given
        Product product = Product.create("키보드", 10_000, 5);
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when
        ProductResponse response = productService.getProduct(1L);

        // then
        assertThat(response.name()).isEqualTo("키보드");
        assertThat(response.price()).isEqualTo(10_000L);
        assertThat(response.stock()).isEqualTo(5);
    }

    @Test
    @DisplayName("존재하지 않는 상품을 조회하면 PRODUCT_NOT_FOUND 예외가 난다")
    void getProductNotFound() {
        // given
        given(productRepository.findById(anyLong())).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> productService.getProduct(999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("삭제된 상품을 조회하면 존재하지 않는 것과 똑같이 PRODUCT_NOT_FOUND 예외가 난다")
    void getProductDeleted() {
        // given
        Product product = Product.create("키보드", 10_000, 5);
        product.delete();
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when // then
        assertThatThrownBy(() -> productService.getProduct(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("상품을 수정하면 값이 바뀌고, 명시적인 save() 호출 없이 더티 체킹에 맡긴다")
    void update() {
        // given
        Product product = Product.create("키보드", 10_000, 5);
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when
        ProductResponse response = productService.update(1L, new UpdateProductRequest("마우스", 20_000L, 0));

        // then
        assertThat(response.name()).isEqualTo("마우스");
        assertThat(response.price()).isEqualTo(20_000L);
        assertThat(response.stock()).isEqualTo(0);
        assertThat(response.status()).isEqualTo(ProductStatus.SOLD_OUT);
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("없는 상품을 수정하면 PRODUCT_NOT_FOUND 예외가 난다")
    void updateNotFound() {
        // given
        given(productRepository.findById(anyLong())).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> productService.update(999L, new UpdateProductRequest("마우스", 20_000L, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("삭제된 상품을 수정하면 PRODUCT_NOT_FOUND 예외가 난다")
    void updateDeleted() {
        // given
        Product product = Product.create("키보드", 10_000, 5);
        product.delete();
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when // then
        assertThatThrownBy(() -> productService.update(1L, new UpdateProductRequest("마우스", 20_000L, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("판매 상태를 변경하면 상태가 바뀐다")
    void changeStatus() {
        // given
        Product product = Product.create("키보드", 10_000, 5);
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when
        ProductResponse response = productService.changeStatus(1L, new UpdateProductStatusRequest(ProductStatus.STOPPED));

        // then
        assertThat(response.status()).isEqualTo(ProductStatus.STOPPED);
    }

    @Test
    @DisplayName("재고가 없는 상품을 판매중으로 바꾸면 도메인 상태 예외가 난다")
    void changeStatusConflict() {
        // given
        Product product = Product.create("키보드", 10_000, 0);
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when // then
        assertThatThrownBy(() -> productService.changeStatus(1L, new UpdateProductStatusRequest(ProductStatus.ON_SALE)))
                .isInstanceOf(IllegalStateException.class);
    }
}
