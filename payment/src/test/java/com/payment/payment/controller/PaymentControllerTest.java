package com.payment.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.common.exception.GlobalExceptionHandler;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentApprovalFacade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
@Import(GlobalExceptionHandler.class)
class PaymentControllerTest {

    private static final String URL = "/api/orders/1/payments/approve";
    private static final String BODY = """
            {"amount": 20000}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentApprovalFacade paymentApprovalFacade;

    @Test
    @DisplayName("올바른 요청이면 200과 결제 결과를 응답하고 헤더의 키와 본문의 금액을 Facade에 넘긴다")
    void approveSuccess() throws Exception {
        // given
        given(paymentApprovalFacade.approve(1L, "key-1", 20_000L))
                .willReturn(new PaymentResponse(1L, 10L, "pg-tx-1", 20_000L, OrderStatus.PAID));

        // when // then
        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.amount").value(20000));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 400이고 Facade를 호출하지 않는다")
    void missingKeyHeader() throws Exception {
        // when // then
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
        verify(paymentApprovalFacade, never()).approve(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Idempotency-Key가 공백이거나 100자를 넘으면 400이다")
    void invalidKeyHeader() throws Exception {
        // when // then
        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", "k".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
        verify(paymentApprovalFacade, never()).approve(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("금액이 없거나 0 이하이면 400이고 Facade를 호출하지 않는다")
    void invalidAmount() throws Exception {
        // when // then
        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 0}
                                """))
                .andExpect(status().isBadRequest());
        verify(paymentApprovalFacade, never()).approve(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("같은 키의 첫 요청이 처리 중이면 409와 PAYMENT_IN_PROGRESS 코드를 응답한다")
    void inProgress() throws Exception {
        // given
        given(paymentApprovalFacade.approve(any(), any(), anyLong()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS));

        // when // then
        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_006"));
    }
}
