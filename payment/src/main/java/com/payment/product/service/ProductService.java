package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.dto.CreateProductRequest;
import com.payment.product.dto.ProductResponse;
import com.payment.product.dto.UpdateProductRequest;
import com.payment.product.dto.UpdateProductStatusRequest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product product = Product.create(request.name(), request.price(), request.stock());
        Product saved = productRepository.save(product);
        // OSIV가 꺼져 있으므로 Entity -> DTO 변환은 트랜잭션 안에서 끝낸다.
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long id) {
        return ProductResponse.from(findActiveProduct(id));
    }

    /**
     * save()를 호출하지 않는다. findById로 조회한 엔티티는 영속 상태이므로,
     * 도메인 메서드로 값을 바꾸면 flush 시점에 더티 체킹이 UPDATE를 날린다.
     * 응답에 @UpdateTimestamp 값(updatedAt)을 정확히 담으려고 DTO 변환 전에 flush()를 호출한다.
     */
    @Transactional
    public ProductResponse update(Long id, UpdateProductRequest request) {
        Product product = findActiveProduct(id);
        product.update(request.name(), request.price(), request.stock());
        productRepository.flush();
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse changeStatus(Long id, UpdateProductStatusRequest request) {
        Product product = findActiveProduct(id);
        product.changeStatus(request.status());
        productRepository.flush();
        return ProductResponse.from(product);
    }

    private Product findActiveProduct(Long id) {
        return productRepository.findById(id)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
