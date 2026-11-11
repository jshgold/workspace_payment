package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.domain.ProductStatus;
import com.payment.product.dto.CreateProductRequest;
import com.payment.product.dto.ProductResponse;
import com.payment.product.dto.UpdateProductRequest;
import com.payment.product.dto.UpdateProductStatusRequest;
import com.payment.support.IntegrationTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ProductServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ProductService productService;

    @Autowired
    ProductRepository productRepository;

    @AfterEach
    void tearDown() {
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("수정 후 다시 조회하면 변경 내용이 DB에 반영되어 있고, save() 없이 UPDATE 쿼리가 나간다")
    void updateIsPersistedByDirtyChecking(CapturedOutput output) {
        // given
        Long id = productService.create(new CreateProductRequest("키보드", 10_000L, 5)).id();

        // when
        ProductResponse updated = productService.update(id, new UpdateProductRequest("마우스", 20_000L, 7));
        ProductResponse found = productService.getProduct(id);

        // then
        assertThat(found.name()).isEqualTo("마우스");
        assertThat(found.price()).isEqualTo(20_000L);
        assertThat(found.stock()).isEqualTo(7);
        assertThat(updated.updatedAt()).isEqualTo(found.updatedAt());
        assertThat(normalize(output.getAll())).contains("update product set");
    }

    @Test
    @DisplayName("상태를 변경한 뒤 조회하면 DB에 반영되어 있다")
    void changeStatusIsPersisted() {
        // given
        Long id = productService.create(new CreateProductRequest("키보드", 10_000L, 5)).id();

        // when
        productService.changeStatus(id, new UpdateProductStatusRequest(ProductStatus.STOPPED));

        // then
        assertThat(productService.getProduct(id).status()).isEqualTo(ProductStatus.STOPPED);
    }

    @Test
    @DisplayName("값이 바뀌지 않은 채 조회만 하면 UPDATE 쿼리가 나가지 않는다")
    void noUpdateWithoutChange(CapturedOutput output) {
        // given
        Long id = productService.create(new CreateProductRequest("키보드", 10_000L, 5)).id();
        int updatesBefore = countUpdates(output.getAll());

        // when
        productService.getProduct(id);

        // then
        assertThat(countUpdates(output.getAll())).isEqualTo(updatesBefore);
    }

    @Test
    @DisplayName("삭제하면 조회·수정·재삭제는 404지만, DB 행은 남고 deleted_at이 기록된다 (소프트 삭제)")
    void softDelete() {
        // given
        Long id = productService.create(new CreateProductRequest("키보드", 10_000L, 5)).id();

        // when
        productService.delete(id);

        // then
        assertThatThrownBy(() -> productService.getProduct(id))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
        assertThatThrownBy(() -> productService.update(id, new UpdateProductRequest("마우스", 1_000L, 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> productService.delete(id))
                .isInstanceOf(BusinessException.class);

        Product row = productRepository.findById(id).orElseThrow();
        assertThat(row.getDeletedAt()).isNotNull();
        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제한 상품과 같은 이름으로 다시 등록할 수 있다 (상품명 UNIQUE를 걸지 않기로 결정)")
    void sameNameAfterDelete() {
        // given
        Long oldId = productService.create(new CreateProductRequest("키보드", 10_000L, 5)).id();
        productService.delete(oldId);

        // when
        ProductResponse recreated = productService.create(new CreateProductRequest("키보드", 12_000L, 3));

        // then
        assertThat(recreated.id()).isNotEqualTo(oldId);
        assertThat(productService.getProduct(recreated.id()).name()).isEqualTo("키보드");
    }

    /** format_sql 설정으로 SQL이 여러 줄로 출력되므로, 연속된 공백·개행을 한 칸으로 줄여 비교한다. */
    private String normalize(String log) {
        return log.toLowerCase().replaceAll("\\s+", " ");
    }

    private int countUpdates(String log) {
        return normalize(log).split("update product set", -1).length - 1;
    }
}
