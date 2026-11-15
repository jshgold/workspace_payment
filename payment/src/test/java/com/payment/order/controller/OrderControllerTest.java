package com.payment.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import com.payment.common.exception.GlobalExceptionHandler;
import com.payment.order.domain.OrderStatus;
import com.payment.order.dto.CreateOrderRequest;
import com.payment.order.dto.OrderResponse;
import com.payment.order.service.OrderService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    @Test
    @DisplayName("올바른 요청이면 201과 Location 헤더, PENDING 주문을 응답한다")
    void createSuccess() throws Exception {
        // given
        given(orderService.create(any(CreateOrderRequest.class)))
                .willReturn(new OrderResponse(1L, 100L, 1L, 2, 20_000L, OrderStatus.PENDING, LocalDateTime.now()));

        // when // then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberId": 100, "productId": 1, "quantity": 2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalPrice").value(20000));
    }

    @Test
    @DisplayName("필수값 누락이나 0 이하 수량은 400이고 서비스를 호출하지 않는다")
    void createInvalid() throws Exception {
        // when // then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 1, "quantity": 0}
                                """))
                .andExpect(status().isBadRequest());
        verify(orderService, never()).create(any());
    }

    @Test
    @DisplayName("재고 부족으로 도메인이 IllegalStateException을 던지면 409를 응답한다")
    void createConflict() throws Exception {
        // given
        given(orderService.create(any(CreateOrderRequest.class)))
                .willThrow(new IllegalStateException("재고가 부족합니다."));

        // when // then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberId": 100, "productId": 1, "quantity": 99}
                                """))
                .andExpect(status().isConflict());
    }
}
