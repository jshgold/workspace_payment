package com.payment.product.service;

import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.dto.CreateProductRequest;
import com.payment.product.dto.ProductResponse;

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
}
