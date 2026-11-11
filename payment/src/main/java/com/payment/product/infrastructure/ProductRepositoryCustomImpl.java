package com.payment.product.infrastructure;

import static com.payment.product.domain.QProduct.product;

import java.util.List;

import com.payment.product.domain.ProductRepositoryCustom;
import com.payment.product.domain.ProductStatus;
import com.payment.product.dto.ProductListResponse;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductRepositoryCustomImpl implements ProductRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<ProductListResponse> search(String keyword, ProductStatus status, Pageable pageable) {
        List<ProductListResponse> content = queryFactory
                .select(Projections.constructor(ProductListResponse.class,
                        product.id,
                        product.name,
                        product.price,
                        product.stock,
                        product.status))
                .from(product)
                .where(
                        notDeleted(),
                        listable(),
                        nameContains(keyword),
                        statusEq(status))
                .orderBy(product.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count 쿼리는 목록 쿼리와 분리한다. (정렬·프로젝션이 필요 없다)
        JPAQuery<Long> countQuery = queryFactory
                .select(product.count())
                .from(product)
                .where(
                        notDeleted(),
                        listable(),
                        nameContains(keyword),
                        statusEq(status));

        // 첫 페이지인데 조회된 건수가 페이지 크기보다 작거나, 마지막 페이지라면 count 쿼리를 생략한다.
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    /** 소프트 삭제된 상품 제외. 삭제 조건은 이 메서드 한 곳에서만 정의한다. */
    private BooleanExpression notDeleted() {
        return product.deletedAt.isNull();
    }

    /** 목록에는 판매중지(STOPPED) 상품을 노출하지 않는다. */
    private BooleanExpression listable() {
        return product.status.ne(ProductStatus.STOPPED);
    }

    private BooleanExpression nameContains(String keyword) {
        return (keyword == null || keyword.isBlank()) ? null : product.name.contains(keyword.trim());
    }

    private BooleanExpression statusEq(ProductStatus status) {
        return status == null ? null : product.status.eq(status);
    }
}
