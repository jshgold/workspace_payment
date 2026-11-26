package com.payment.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgCancelResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 토스 취소 응답을 "돈이 돌아갔는지 아는가"로 분류하는 규칙을 검증한다. 실제 PG는 호출하지 않는다.
 */
class TossPaymentClientCancelTest {

    private static final String PAYMENT_KEY = "payment-key-1";
    private static final String CANCEL_URL = "https://pg.test/v1/payments/" + PAYMENT_KEY + "/cancel";

    private MockRestServiceServer server;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://pg.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentClient(builder.build());
    }

    private void respondWith(int status, String body) {
        server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private static String error(String code) {
        return """
                {"code":"%s","message":"메시지"}
                """.formatted(code);
    }

    @Test
    @DisplayName("취소 요청은 paymentKey를 경로에, 사유를 cancelReason에, 받은 PG 멱등키를 Idempotency-Key 헤더에 담아 POST하고 금액은 보내지 않는다(전액 취소)")
    void sendsCancelRequest() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "cancel-1"))
                .andExpect(jsonPath("$.cancelReason").value("단순 변심"))
                .andExpect(jsonPath("$.cancelAmount").doesNotExist())
                .andRespond(withSuccess("""
                        {"paymentKey":"payment-key-1","status":"CANCELED"}
                        """, MediaType.APPLICATION_JSON));

        // when
        PgCancelResult result = client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1");

        // then
        assertThat(result.alreadyCanceled()).isFalse();
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT"})
    @DisplayName("이미 취소·환불된 결제(400)는 전액 환불이 끝난 것이므로 실패가 아니라 취소 성공이다")
    void alreadyCanceledIsSuccess(String code) {
        // given
        respondWith(400, error(code));

        // when
        PgCancelResult result = client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1");

        // then
        assertThat(result.alreadyCanceled()).isTrue();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "403, NOT_CANCELABLE_PAYMENT",
            "403, NOT_CANCELABLE_AMOUNT",
            "404, NOT_FOUND_PAYMENT",
            "400, INVALID_REQUEST",
            "403, FORBIDDEN_REQUEST"
    })
    @DisplayName("그 밖의 4xx는 명확한 거절(PaymentCancelDeclinedException)이다. 결제는 살아 있다")
    void declined(int status, String code) {
        // given
        respondWith(status, error(code));

        // when // then
        assertThatThrownBy(() -> client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1"))
                .isInstanceOf(PaymentCancelDeclinedException.class)
                .hasMessageContaining(code);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "500, FAILED_INTERNAL_SYSTEM_PROCESSING",
            "500, FAILED_METHOD_HANDLING_CANCEL",
            "500, COMMON_ERROR",
            "409, IDEMPOTENT_REQUEST_PROCESSING",
            "400, PROVIDER_ERROR",
            "403, FORBIDDEN_CONSECUTIVE_REQUEST"
    })
    @DisplayName("5xx와, 4xx지만 앞선 요청이 처리됐는지 알 수 없는 코드는 결과 모름(PaymentTimeoutException)이다")
    void unknownResult(int status, String code) {
        // given
        respondWith(status, error(code));

        // when // then
        assertThatThrownBy(() -> client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1")).isInstanceOf(PaymentTimeoutException.class);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"401, UNAUTHORIZED_KEY", "400, INVALID_API_KEY"})
    @DisplayName("키 오류는 요청이 PG에서 처리되지 않은 연동 문제(PaymentGatewayException)다")
    void gatewayError(int status, String code) {
        // given
        respondWith(status, error(code));

        // when // then
        assertThatThrownBy(() -> client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1")).isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("2xx인데 status가 CANCELED가 아니거나 본문이 비면 취소됐는지 모르므로 결과 모름이다")
    void abnormalSuccessBodyIsUnknown() {
        // given
        respondWith(200, """
                {"paymentKey":"payment-key-1","status":"DONE"}
                """);

        // when // then
        assertThatThrownBy(() -> client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1")).isInstanceOf(PaymentTimeoutException.class);

        // given
        server.reset();
        respondWith(200, "");

        // when // then
        assertThatThrownBy(() -> client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1")).isInstanceOf(PaymentTimeoutException.class);
    }

    @Test
    @DisplayName("연결 거부는 요청이 PG에 닿지 않은 것이 확실하므로 PaymentGatewayException이다")
    void connectionRefusedIsGatewayError() {
        // given
        server.expect(requestTo(CANCEL_URL)).andRespond(httpRequest -> {
            throw new ConnectException("Connection refused");
        });

        // when // then
        assertThatThrownBy(() -> client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining(TossPaymentClient.CONNECTION_FAILED);
    }

    @Test
    @DisplayName("읽기 타임아웃은 취소 요청이 처리됐는지 모르므로 결과 모름이다")
    void readTimeoutIsUnknown() {
        // given
        server.expect(requestTo(CANCEL_URL)).andRespond(httpRequest -> {
            throw new SocketTimeoutException("Read timed out");
        });

        // when // then
        assertThatThrownBy(() -> client.cancel(PAYMENT_KEY, "단순 변심", "cancel-1")).isInstanceOf(PaymentTimeoutException.class);
    }
}
