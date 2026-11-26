package com.payment.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Optional;

import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentLookupRejectedException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgPaymentLookup;
import com.payment.payment.domain.PgPaymentStatus;

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
 * 토스 결제 조회({@code GET /v1/payments/{paymentKey}}) 응답 분류를 검증한다. 실제 PG는 호출하지 않는다.
 * 핵심은 "조회 실패"를 "결제 없음"으로 잘못 분류하지 않는 것이다. 그러면 대사가 승인된 결제를 FAILED로 확정한다.
 */
class TossPaymentClientLookupTest {

    private static final String PAYMENT_KEY = "payment-key-1";
    private static final String LOOKUP_URL = "https://pg.test/v1/payments/" + PAYMENT_KEY;

    private MockRestServiceServer server;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://pg.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentClient(builder.build());
    }

    /** 모든 조회 테스트에서 PG 멱등키 헤더가 붙지 않는지도 함께 본다. 조회(GET)에는 키를 붙이지 않는다. (phase-04 4-5) */
    private void respondWith(int status, String body) {
        server.expect(requestTo(LOOKUP_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private static String error(String code) {
        return """
                {"code":"%s","message":"메시지"}
                """.formatted(code);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"DONE", "CANCELED", "ABORTED", "EXPIRED", "READY", "IN_PROGRESS"})
    @DisplayName("200 응답은 paymentKey로 GET하고 status와 totalAmount를 그대로 돌려준다")
    void returnsStatusAndAmount(String status) {
        // given
        respondWith(200, """
                {"paymentKey":"payment-key-1","orderId":"order-1","status":"%s","totalAmount":20000,"balanceAmount":20000}
                """.formatted(status));

        // when
        Optional<PgPaymentLookup> result = client.lookup(PAYMENT_KEY);

        // then
        assertThat(result).contains(new PgPaymentLookup(PgPaymentStatus.valueOf(status), 20_000L));
        server.verify();
    }

    @Test
    @DisplayName("404 NOT_FOUND_PAYMENT는 PG가 그 결제를 모른다는 뜻이므로 빈 값이다 (판단은 대사가 한다)")
    void notFoundPaymentIsEmpty() {
        // given
        respondWith(404, error("NOT_FOUND_PAYMENT"));

        // when
        Optional<PgPaymentLookup> result = client.lookup(PAYMENT_KEY);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("404 NOT_FOUND(존재하지 않는 정보)는 결제가 없다고 단정할 수 없고, 다시 보내도 같은 응답이므로 일시 실패도 아니다 → 조회 거절(PaymentLookupRejectedException)")
    void genericNotFoundIsRejected() {
        // given
        respondWith(404, error("NOT_FOUND"));

        // when // then
        assertThatThrownBy(() -> client.lookup(PAYMENT_KEY)).isInstanceOf(PaymentLookupRejectedException.class);
    }

    @Test
    @DisplayName("예상 밖 4xx(400)는 우리 요청 문제라 조회 거절(PaymentLookupRejectedException)이고 빈 값이 아니다")
    void unexpected4xxIsRejected() {
        // given
        respondWith(400, error("SOMETHING_UNEXPECTED"));

        // when // then
        assertThatThrownBy(() -> client.lookup(PAYMENT_KEY)).isInstanceOf(PaymentLookupRejectedException.class);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "500, FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",
            "403, FORBIDDEN_CONSECUTIVE_REQUEST",
            "400, PROVIDER_ERROR",
            "429, TOO_MANY_REQUESTS"})
    @DisplayName("5xx·반복 요청 차단·일시 오류·429는 다시 하면 될 수 있는 조회 실패(PaymentTimeoutException)이고 빈 값이 아니다")
    void otherErrorsAreLookupFailure(int status, String code) {
        // given
        respondWith(status, error(code));

        // when // then
        assertThatThrownBy(() -> client.lookup(PAYMENT_KEY)).isInstanceOf(PaymentTimeoutException.class);
    }

    @Test
    @DisplayName("401 UNAUTHORIZED_KEY는 우리 쪽 키 문제라 연동 오류(PaymentGatewayException)다")
    void unauthorizedIsGatewayError() {
        // given
        respondWith(401, error("UNAUTHORIZED_KEY"));

        // when // then
        assertThatThrownBy(() -> client.lookup(PAYMENT_KEY))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("UNAUTHORIZED_KEY");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'{\"paymentKey\":\"payment-key-1\",\"status\":\"SOMETHING_NEW\",\"totalAmount\":20000}'",
            "'{\"paymentKey\":\"payment-key-1\",\"status\":\"DONE\"}'",
            "'not json'"})
    @DisplayName("200인데 상태를 모르거나 금액이 없거나 본문을 읽을 수 없으면 조회 실패다")
    void abnormalSuccessBodyIsLookupFailure(String body) {
        // given
        respondWith(200, body);

        // when // then
        assertThatThrownBy(() -> client.lookup(PAYMENT_KEY)).isInstanceOf(PaymentTimeoutException.class);
    }

    @Test
    @DisplayName("연결 거부는 요청이 닿지 않은 연동 오류다")
    void connectionRefusedIsGatewayError() {
        // given
        server.expect(requestTo(LOOKUP_URL)).andRespond(httpRequest -> {
            throw new ConnectException("Connection refused");
        });

        // when // then
        assertThatThrownBy(() -> client.lookup(PAYMENT_KEY))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining(TossPaymentClient.CONNECTION_FAILED);
    }

    @Test
    @DisplayName("읽기 타임아웃은 조회 실패다")
    void readTimeoutIsLookupFailure() {
        // given
        server.expect(requestTo(LOOKUP_URL)).andRespond(httpRequest -> {
            throw new SocketTimeoutException("Read timed out");
        });

        // when // then
        assertThatThrownBy(() -> client.lookup(PAYMENT_KEY)).isInstanceOf(PaymentTimeoutException.class);
    }
}
