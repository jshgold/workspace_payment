package com.payment.product.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.common.exception.GlobalExceptionHandler;
import com.payment.product.domain.ProductStatus;
import com.payment.product.dto.CreateProductRequest;
import com.payment.product.dto.ProductResponse;
import com.payment.product.service.ProductService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(GlobalExceptionHandler.class)
class ProductControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProductService productService;

    private String json(String name, String price, String stock) {
        return """
                {"name": %s, "price": %s, "stock": %s}
                """.formatted(name, price, stock);
    }

    @Test
    @DisplayName("올바른 요청이면 201과 Location 헤더, 생성된 상품 정보를 응답한다")
    void createSuccess() throws Exception {
        // given
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 10, 0);
        given(productService.create(any(CreateProductRequest.class)))
                .willReturn(new ProductResponse(1L, "키보드", 10_000, 5, ProductStatus.ON_SALE, now, now));

        // when // then
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("\"키보드\"", "10000", "5")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("키보드"))
                .andExpect(jsonPath("$.status").value("ON_SALE"));
    }

    @Test
    @DisplayName("가격이 0이면 400과 price 필드 오류를 응답하고 서비스를 호출하지 않는다")
    void priceZero() throws Exception {
        // given // when // then
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("\"키보드\"", "0", "5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("price")));
        verify(productService, never()).create(any());
    }

    @Test
    @DisplayName("재고가 음수이면 400과 stock 필드 오류를 응답한다")
    void stockNegative() throws Exception {
        // given // when // then
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("\"키보드\"", "10000", "-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("stock")));
    }

    @Test
    @DisplayName("상품명이 공백이면 400과 name 필드 오류를 응답한다")
    void nameBlank() throws Exception {
        // given // when // then
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("\"  \"", "10000", "5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")));
    }

    @Test
    @DisplayName("상품명이 100자를 넘으면 400을 응답한다")
    void nameTooLong() throws Exception {
        // given
        String longName = "\"" + "a".repeat(101) + "\"";

        // when // then
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(longName, "10000", "5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")));
    }

    @Test
    @DisplayName("필수 필드가 누락되면 400을 응답한다 (재고 누락을 0으로 착각하지 않는다)")
    void missingFields() throws Exception {
        // given // when // then
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("price")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("stock")));
    }

    @Test
    @DisplayName("가격이 숫자가 아니면 400을 응답한다")
    void priceNotNumber() throws Exception {
        // given // when // then
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("\"키보드\"", "\"abc\"", "5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("상품을 단건 조회하면 200과 상품 정보를 응답한다")
    void getProduct() throws Exception {
        // given
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 10, 0);
        given(productService.getProduct(1L))
                .willReturn(new ProductResponse(1L, "키보드", 10_000, 5, ProductStatus.ON_SALE, now, now));

        // when // then
        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("키보드"))
                .andExpect(jsonPath("$.price").value(10000))
                .andExpect(jsonPath("$.status").value("ON_SALE"));
    }

    @Test
    @DisplayName("없거나 삭제된 상품이면 404와 PRODUCT_NOT_FOUND 코드를 응답한다")
    void getProductNotFound() throws Exception {
        // given
        given(productService.getProduct(999L)).willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        // when // then
        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_001"))
                .andExpect(jsonPath("$.message").value("상품을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("id가 숫자가 아니면 400을 응답한다")
    void getProductInvalidId() throws Exception {
        // given // when // then
        mockMvc.perform(get("/api/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }
}
