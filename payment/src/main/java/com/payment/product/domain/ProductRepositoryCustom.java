package com.payment.product.domain;

import com.payment.product.dto.ProductListResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * QueryDSL 기반 조회 계약. 구현체는 infrastructure 패키지의 {@code ProductRepositoryCustomImpl}.
 * <p>
 * ProductRepository 가 이 인터페이스를 상속(fragment)하지 않는 이유: Spring Data 는 fragment 구현체를
 * "fragment 인터페이스가 있는 패키지 하위"에서만 찾는다. 구현체를 infrastructure 에 두려는 아키텍처 규칙과
 * 충돌하므로, 구현체를 일반 빈으로 등록하고 Service 가 이 인터페이스를 직접 주입받는다.
 */
public interface ProductRepositoryCustom {

    /**
     * 판매 중인 상품 목록을 검색한다. 삭제된 상품과 판매중지(STOPPED) 상품은 항상 제외한다.
     *
     * @param keyword 상품명 부분 일치 (null/공백이면 조건 없음)
     * @param status  판매 상태 (null이면 조건 없음)
     */
    Page<ProductListResponse> search(String keyword, ProductStatus status, Pageable pageable);
}
