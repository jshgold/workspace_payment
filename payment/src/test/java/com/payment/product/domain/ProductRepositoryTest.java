package com.payment.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.payment.product.dto.ProductListResponse;
import com.payment.support.IntegrationTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@ExtendWith(OutputCaptureExtension.class)
class ProductRepositoryTest extends IntegrationTestSupport {

    @Autowired
    ProductRepository productRepository;

    @Autowired
    ProductRepositoryCustom productRepositoryCustom;

    private Product save(String name, int stock) {
        return productRepository.save(Product.create(name, 10_000, stock));
    }

    private Product saveStopped(String name) {
        Product product = Product.create(name, 10_000, 5);
        product.changeStatus(ProductStatus.STOPPED);
        return productRepository.save(product);
    }

    private Product saveDeleted(String name) {
        Product product = Product.create(name, 10_000, 5);
        product.delete();
        return productRepository.save(product);
    }

    private List<String> names(Page<ProductListResponse> page) {
        return page.getContent().stream().map(ProductListResponse::name).toList();
    }

    /** format_sql 로 여러 줄이 된 SQL 을 비교하기 쉽게 한 줄로 만든다. */
    private String normalize(String log) {
        return log.toLowerCase().replaceAll("\\s+", " ");
    }

    private int countQueries(String log) {
        return normalize(log).split("select count\\(", -1).length - 1;
    }

    @Test
    @DisplayName("삭제된 상품과 판매중지(STOPPED) 상품은 목록에서 제외된다")
    void excludesDeletedAndStopped() {
        // given
        save("판매중 키보드", 5);
        save("품절 마우스", 0);
        saveStopped("중지된 모니터");
        saveDeleted("삭제된 노트북");

        // when
        Page<ProductListResponse> result = productRepositoryCustom.search(null, null, PageRequest.of(0, 10));

        // then
        assertThat(names(result)).containsExactlyInAnyOrder("판매중 키보드", "품절 마우스");
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("keyword로 상품명을 부분 일치 검색한다")
    void searchByKeyword() {
        // given
        save("기계식 키보드", 5);
        save("무선 키보드", 5);
        save("마우스", 5);

        // when
        Page<ProductListResponse> result = productRepositoryCustom.search("키보드", null, PageRequest.of(0, 10));

        // then
        assertThat(names(result)).containsExactlyInAnyOrder("기계식 키보드", "무선 키보드");
    }

    @Test
    @DisplayName("공백뿐인 keyword는 조건 없음으로 취급한다")
    void blankKeywordIsIgnored() {
        // given
        save("키보드", 5);
        save("마우스", 5);

        // when
        Page<ProductListResponse> result = productRepositoryCustom.search("   ", null, PageRequest.of(0, 10));

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("status로 필터링한다")
    void searchByStatus() {
        // given
        save("판매중 키보드", 5);
        save("품절 마우스", 0);

        // when
        Page<ProductListResponse> onSale = productRepositoryCustom.search(null, ProductStatus.ON_SALE, PageRequest.of(0, 10));
        Page<ProductListResponse> soldOut = productRepositoryCustom.search(null, ProductStatus.SOLD_OUT, PageRequest.of(0, 10));

        // then
        assertThat(names(onSale)).containsExactly("판매중 키보드");
        assertThat(names(soldOut)).containsExactly("품절 마우스");
    }

    @Test
    @DisplayName("STOPPED 상태로 검색해도 판매중지 상품은 노출되지 않는다")
    void stoppedStatusFilterReturnsNothing() {
        // given
        saveStopped("중지된 모니터");

        // when
        Page<ProductListResponse> result = productRepositoryCustom.search(null, ProductStatus.STOPPED, PageRequest.of(0, 10));

        // then
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("keyword와 status를 함께 주면 둘 다 만족하는 상품만 조회된다")
    void searchByKeywordAndStatus() {
        // given
        save("키보드 A", 5);
        save("키보드 B", 0);
        save("마우스", 5);

        // when
        Page<ProductListResponse> result =
                productRepositoryCustom.search("키보드", ProductStatus.ON_SALE, PageRequest.of(0, 10));

        // then
        assertThat(names(result)).containsExactly("키보드 A");
    }

    @Test
    @DisplayName("최신 등록순(id 내림차순)으로 정렬하고 페이징 정보가 정확하다")
    void pagingAndOrder() {
        // given
        for (int i = 1; i <= 5; i++) {
            save("상품" + i, 5);
        }

        // when
        Page<ProductListResponse> page0 = productRepositoryCustom.search(null, null, PageRequest.of(0, 2));
        Page<ProductListResponse> page1 = productRepositoryCustom.search(null, null, PageRequest.of(1, 2));
        Page<ProductListResponse> page2 = productRepositoryCustom.search(null, null, PageRequest.of(2, 2));

        // then
        assertThat(names(page0)).containsExactly("상품5", "상품4");
        assertThat(names(page1)).containsExactly("상품3", "상품2");
        assertThat(names(page2)).containsExactly("상품1");
        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("DTO로 직접 프로젝션하여 필요한 필드가 채워진다")
    void projectionFields() {
        // given
        Product saved = save("키보드", 5);

        // when
        ProductListResponse row = productRepositoryCustom.search(null, null, PageRequest.of(0, 10)).getContent().get(0);

        // then
        assertThat(row.id()).isEqualTo(saved.getId());
        assertThat(row.name()).isEqualTo("키보드");
        assertThat(row.price()).isEqualTo(10_000L);
        assertThat(row.stock()).isEqualTo(5);
        assertThat(row.status()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("목록 쿼리에 삭제·판매중지 조건이 명시적으로 붙는다 (SQL 로그)")
    void sqlContainsExplicitConditions(CapturedOutput output) {
        // given
        save("키보드", 5);

        // when
        productRepositoryCustom.search(null, null, PageRequest.of(0, 10));

        // then
        String sql = normalize(output.getAll());
        assertThat(sql).contains("deleted_at is null");
        assertThat(sql).contains("status<>?");
        assertThat(sql).contains("order by p1_0.id desc");
    }

    @Test
    @DisplayName("전체 건수가 페이지 크기보다 적은 첫 페이지는 count 쿼리를 생략한다")
    void skipCountQueryOnSmallFirstPage(CapturedOutput output) {
        // given
        save("키보드", 5);
        save("마우스", 5);
        int before = countQueries(output.getAll());

        // when
        productRepositoryCustom.search(null, null, PageRequest.of(0, 10));

        // then
        assertThat(countQueries(output.getAll())).isEqualTo(before);
    }

    @Test
    @DisplayName("결과가 페이지 크기를 꽉 채우면 전체 건수를 알기 위해 count 쿼리가 실행된다")
    void runCountQueryWhenPageIsFull(CapturedOutput output) {
        // given
        for (int i = 1; i <= 3; i++) {
            save("상품" + i, 5);
        }
        int before = countQueries(output.getAll());

        // when
        productRepositoryCustom.search(null, null, PageRequest.of(0, 2));

        // then
        assertThat(countQueries(output.getAll())).isEqualTo(before + 1);
    }
}
