package com.payment.payment.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.common.exception.GlobalExceptionHandler;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentCancelFacade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentCancelController.class)
@Import(GlobalExceptionHandler.class)
class PaymentCancelControllerTest {

    private static final String URL = "/api/orders/1/payments/cancel";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentCancelFacade paymentCancelFacade;

    @Test
    @DisplayName("올바른 요청이면 200과 취소 결과를 응답하고 사유를 Facade에 넘긴다")
    void cancelSuccess() throws Exception {
        // given
        given(paymentCancelFacade.cancel(1L, "단순 변심"))
                .willReturn(new PaymentResponse(1L, 10L, "pg-tx-1", 20_000L, OrderStatus.CANCELED));

        // when // then
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "단순 변심"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("CANCELED"));
    }

    @Test
    @DisplayName("취소 사유가 비어 있으면 400이고 Facade를 호출하지 않는다")
    void blankReason() throws Exception {
        // when // then
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": " "}
                                """))
                .andExpect(status().isBadRequest());
        verify(paymentCancelFacade, never()).cancel(anyLong(), anyString());
    }

    @Test
    @DisplayName("이미 취소된 주문 등 상태가 맞지 않으면 409를 응답한다")
    void stateConflict() throws Exception {
        // given
        given(paymentCancelFacade.cancel(1L, "단순 변심"))
                .willThrow(new IllegalStateException("결제 취소를 시작할 수 없는 주문 상태입니다. 현재 상태: CANCELED"));

        // when // then
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "단순 변심"}
                                """))
                .andExpect(status().isConflict());
    }
}
