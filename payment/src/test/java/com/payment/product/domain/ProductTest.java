package com.payment.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {

    @Nested
    class Create {

        @Test
        @DisplayName("재고가 있는 상품을 생성하면 판매중(ON_SALE) 상태가 된다")
        void createOnSale() {
            // given // when
            Product product = Product.create("키보드", 10_000, 5);

            // then
            assertThat(product.getName()).isEqualTo("키보드");
            assertThat(product.getPrice()).isEqualTo(10_000);
            assertThat(product.getStock()).isEqualTo(5);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
            assertThat(product.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("재고 0으로 생성하면 품절(SOLD_OUT) 상태가 된다")
        void createSoldOutWhenStockZero() {
            // given // when
            Product product = Product.create("키보드", 10_000, 0);

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        }

        @ParameterizedTest(name = "가격 {0}")
        @ValueSource(longs = {0, -1, -10_000})
        @DisplayName("가격이 0 이하이면 생성할 수 없다")
        void createFailsWhenPriceNotPositive(long price) {
            // given // when // then
            assertThatThrownBy(() -> Product.create("키보드", price, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("가격");
        }

        @Test
        @DisplayName("재고가 음수이면 생성할 수 없다")
        void createFailsWhenStockNegative() {
            // given // when // then
            assertThatThrownBy(() -> Product.create("키보드", 10_000, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("재고");
        }

        @ParameterizedTest(name = "상품명 [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("상품명이 비어 있으면 생성할 수 없다")
        void createFailsWhenNameBlank(String name) {
            // given // when // then
            assertThatThrownBy(() -> Product.create(name, 10_000, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("상품명");
        }

        @Test
        @DisplayName("상품명이 null이거나 100자를 넘으면 생성할 수 없다")
        void createFailsWhenNameNullOrTooLong() {
            // given
            String tooLong = "a".repeat(101);

            // when // then
            assertThatThrownBy(() -> Product.create(null, 10_000, 5))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Product.create(tooLong, 10_000, 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Update {

        @Test
        @DisplayName("이름·가격·재고를 수정하면 값이 반영된다")
        void update() {
            // given
            Product product = Product.create("키보드", 10_000, 5);

            // when
            product.update("마우스", 20_000, 7);

            // then
            assertThat(product.getName()).isEqualTo("마우스");
            assertThat(product.getPrice()).isEqualTo(20_000);
            assertThat(product.getStock()).isEqualTo(7);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        }

        @Test
        @DisplayName("재고를 0으로 수정하면 품절 상태가 된다")
        void updateToSoldOut() {
            // given
            Product product = Product.create("키보드", 10_000, 5);

            // when
            product.update("키보드", 10_000, 0);

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        }

        @Test
        @DisplayName("품절 상품의 재고를 채우면 판매중으로 돌아온다")
        void updateRestock() {
            // given
            Product product = Product.create("키보드", 10_000, 0);

            // when
            product.update("키보드", 10_000, 3);

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        }

        @Test
        @DisplayName("판매중지 상품은 재고를 수정해도 판매중지 상태가 유지된다")
        void updateKeepsStopped() {
            // given
            Product product = Product.create("키보드", 10_000, 5);
            product.changeStatus(ProductStatus.STOPPED);

            // when
            product.update("키보드", 10_000, 9);

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.STOPPED);
        }

        @Test
        @DisplayName("잘못된 값으로 수정하면 예외가 나고 기존 값이 바뀌지 않는다")
        void updateFailsKeepsOldValues() {
            // given
            Product product = Product.create("키보드", 10_000, 5);

            // when // then
            assertThatThrownBy(() -> product.update("마우스", 0, 7))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(product.getName()).isEqualTo("키보드");
            assertThat(product.getPrice()).isEqualTo(10_000);
            assertThat(product.getStock()).isEqualTo(5);
        }
    }

    @Nested
    class ChangeStatus {

        @Test
        @DisplayName("재고가 있는 상품을 판매중지로 바꿀 수 있고, 다시 판매중으로 되돌릴 수 있다")
        void stopAndResume() {
            // given
            Product product = Product.create("키보드", 10_000, 5);

            // when
            product.changeStatus(ProductStatus.STOPPED);
            ProductStatus stopped = product.getStatus();
            product.changeStatus(ProductStatus.ON_SALE);

            // then
            assertThat(stopped).isEqualTo(ProductStatus.STOPPED);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        }

        @Test
        @DisplayName("재고가 0인 상품은 판매중으로 바꿀 수 없다")
        void onSaleFailsWhenNoStock() {
            // given
            Product product = Product.create("키보드", 10_000, 0);

            // when // then
            assertThatThrownBy(() -> product.changeStatus(ProductStatus.ON_SALE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("재고가 남은 상품은 품절로 바꿀 수 없다")
        void soldOutFailsWhenHasStock() {
            // given
            Product product = Product.create("키보드", 10_000, 5);

            // when // then
            assertThatThrownBy(() -> product.changeStatus(ProductStatus.SOLD_OUT))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("상태가 null이면 변경할 수 없다")
        void nullStatus() {
            // given
            Product product = Product.create("키보드", 10_000, 5);

            // when // then
            assertThatThrownBy(() -> product.changeStatus(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Delete {

        @Test
        @DisplayName("삭제하면 deletedAt이 기록되고 삭제된 상태가 된다")
        void delete() {
            // given
            Product product = Product.create("키보드", 10_000, 5);

            // when
            product.delete();

            // then
            assertThat(product.isDeleted()).isTrue();
            assertThat(product.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("삭제된 상품은 수정·상태변경·재삭제할 수 없다")
        void deletedProductIsImmutable() {
            // given
            Product product = Product.create("키보드", 10_000, 5);
            product.delete();

            // when // then
            assertThatThrownBy(() -> product.update("마우스", 1_000, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> product.changeStatus(ProductStatus.STOPPED))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(product::delete)
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
