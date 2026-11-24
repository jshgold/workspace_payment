package com.payment.payment.controller;

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
import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentCancelFacade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private static final String KEY = "cancel-key-1";
    private static final String BODY = """
            {"reason": "단순 변심"}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentCancelFacade paymentCancelFacade;

    @Test
    @DisplayName("올바른 요청이면 200과 취소 결과를 응답하고 멱등키와 사유를 Facade에 넘긴다")
    void cancelSuccess() throws Exception {
        // given
        given(paymentCancelFacade.cancel(1L, KEY, "단순 변심"))
                .willReturn(new PaymentResponse(1L, 10L, "payment-key-1", 20_000L, OrderStatus.CANCELED, PaymentStatus.CANCELED));

        // when // then
        mockMvc.perform(post(URL).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("CANCELED"));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 400이고 Facade를 호출하지 않는다")
    void missingIdempotencyKey() throws Exception {
        // when // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verify(paymentCancelFacade, never()).cancel(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("Idempotency-Key가 공백이거나 100자를 넘으면 400이고 Facade를 호출하지 않는다")
    void invalidIdempotencyKey() throws Exception {
        // when // then
        mockMvc.perform(post(URL).header("Idempotency-Key", " ").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL).header("Idempotency-Key", "k".repeat(101)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verify(paymentCancelFacade, never()).cancel(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("취소 사유가 비어 있으면 400이고 Facade를 호출하지 않는다")
    void blankReason() throws Exception {
        // when // then
        mockMvc.perform(post(URL).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": " "}
                                """))
                .andExpect(status().isBadRequest());
        verify(paymentCancelFacade, never()).cancel(anyLong(), anyString(), anyString());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "ORDER_ALREADY_CANCELED, 409",
            "ORDER_NOT_CANCELABLE, 409",
            "PAYMENT_IN_PROGRESS, 409",
            "IDEMPOTENCY_KEY_REUSED, 422",
            "PAYMENT_CANCEL_UNKNOWN, 504",
            "PAYMENT_CANCEL_GATEWAY_ERROR, 502"
    })
    @DisplayName("취소 흐름의 에러 코드는 정해진 HTTP 상태와 코드로 응답한다")
    void errorCodes(ErrorCode errorCode, int httpStatus) throws Exception {
        // given
        given(paymentCancelFacade.cancel(1L, KEY, "단순 변심")).willThrow(new BusinessException(errorCode));

        // when // then
        mockMvc.perform(post(URL).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    @Test
    @DisplayName("PG가 취소를 거절하면 409 PAYMENT_CANCEL_DECLINED로 응답한다")
    void declined() throws Exception {
        // given
        given(paymentCancelFacade.cancel(1L, KEY, "단순 변심"))
                .willThrow(new PaymentCancelDeclinedException("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."));

        // when // then
        mockMvc.perform(post(URL).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_CANCEL_DECLINED.getCode()));
    }
}
