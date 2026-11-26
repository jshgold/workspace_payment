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
import java.net.http.HttpConnectTimeoutException;

import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;

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
 * PG 응답을 "돈이 나갔는지 아는가"로 분류하는 규칙을 검증한다. 실제 PG는 호출하지 않는다.
 * (HTTP 수준의 타임아웃과 인증 헤더는 {@link TossPaymentConfigTest}에서 확인한다)
 */
class TossPaymentClientTest {

    private static final String ORDER_NO = "0190aaaa-bbbb-7ccc-8ddd-eeeeffff0001";
    private static final String CONFIRM_URL = "https://pg.test/v1/payments/confirm";

    private MockRestServiceServer server;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://pg.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentClient(builder.build());
    }

    private PgApproveRequest request() {
        return new PgApproveRequest(ORDER_NO, "payment-key-1", 30_000L);
    }

    private static String payment(String status, String orderId, long totalAmount) {
        return """
                {"paymentKey":"payment-key-1","orderId":"%s","status":"%s","totalAmount":%d,"method":"카드","version":"2022-11-16"}
                """.formatted(orderId, status, totalAmount);
    }

    private static String error(String code, String message) {
        return """
                {"code":"%s","message":"%s"}
                """.formatted(code, message);
    }

    private void respondWith(int status, String body) {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @Test
    @DisplayName("승인 요청은 paymentKey, 주문번호(orderNo), 금액을 body에 담고 PG 멱등키(confirm-주문번호)를 Idempotency-Key 헤더에 담아 confirm 경로로 POST한다")
    void sendsConfirmRequest() {
        // given
        server.expect(requestTo(CONFIRM_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "confirm-" + ORDER_NO))
                .andExpect(jsonPath("$.paymentKey").value("payment-key-1"))
                .andExpect(jsonPath("$.orderId").value(ORDER_NO))
                .andExpect(jsonPath("$.amount").value(30_000))
                .andRespond(withSuccess(payment("DONE", ORDER_NO, 30_000), MediaType.APPLICATION_JSON));

        // when
        client.approve(request());

        // then
        server.verify();
    }

    @Test
    @DisplayName("2xx이고 DONE이며 주문번호가 같으면 paymentKey를 거래 ID로, totalAmount를 승인 금액으로 돌려준다")
    void approveSuccess() {
        // given
        respondWith(200, payment("DONE", ORDER_NO, 30_000));

        // when
        PgApproveResult result = client.approve(request());

        // then
        assertThat(result.pgTransactionId()).isEqualTo("payment-key-1");
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("승인 금액이 요청과 달라도 클라이언트는 응답 금액을 그대로 돌려준다. 불일치 판단과 보상은 Facade의 몫이다")
    void returnsApprovedAmountAsIs() {
        // given
        respondWith(200, payment("DONE", ORDER_NO, 29_000));

        // when
        PgApproveResult result = client.approve(request());

        // then
        assertThat(result.approvedAmount()).isEqualTo(29_000L);
    }

    @ParameterizedTest(name = "[{index}] HTTP {0} {1}")
    @CsvSource({
            "403, REJECT_CARD_COMPANY",
            "403, FORBIDDEN_REQUEST",
            "400, INVALID_REJECT_CARD",
            "400, EXCEED_MAX_AMOUNT",
            "400, INVALID_CARD_EXPIRATION",
            "404, NOT_FOUND_PAYMENT_SESSION"
    })
    @DisplayName("4xx 거절 코드는 PaymentDeclinedException이다. 돈이 나가지 않았다")
    void declined(int status, String code) {
        // given
        respondWith(status, error(code, "카드사 거절 사유"));

        // when // then
        assertThatThrownBy(() -> client.approve(request()))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("카드사 거절 사유");
    }

    @ParameterizedTest(name = "[{index}] HTTP {0} {1}")
    @CsvSource({
            "500, FAILED_INTERNAL_SYSTEM_PROCESSING",
            "500, FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",
            "400, ALREADY_PROCESSED_PAYMENT",
            "409, IDEMPOTENT_REQUEST_PROCESSING"
    })
    @DisplayName("5xx와, 4xx지만 이미 승인됐을 수 있는 코드는 결과를 모르므로 PaymentTimeoutException이다")
    void unknownResult(int status, String code) {
        // given
        respondWith(status, error(code, "처리 결과를 알 수 없음"));

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
    }

    @Test
    @DisplayName("본문이 JSON이 아닌 5xx(게이트웨이 HTML 오류 페이지 등)도 결과를 모르는 것으로 본다")
    void nonJsonServerError() {
        // given
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatusCode.valueOf(502)).contentType(MediaType.TEXT_HTML).body("<html>Bad Gateway</html>"));

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
    }

    @ParameterizedTest(name = "[{index}] HTTP {0} {1}")
    @CsvSource({
            "401, UNAUTHORIZED_KEY",
            "400, INVALID_API_KEY"
    })
    @DisplayName("키 오류는 구매자 거절이 아니라 우리 연동 문제(PaymentGatewayException)다")
    void gatewayError(int status, String code) {
        // given
        respondWith(status, error(code, "인증 실패"));

        // when // then
        assertThatThrownBy(() -> client.approve(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining(code);
    }

    @Test
    @DisplayName("2xx인데 상태가 DONE이 아니면 돈이 나갔을 수 있으므로 결과를 모르는 것으로 본다")
    void nonDoneStatusIsUnknown() {
        // given
        respondWith(200, payment("WAITING_FOR_DEPOSIT", ORDER_NO, 30_000));

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
    }

    @Test
    @DisplayName("2xx인데 응답의 주문번호가 요청과 다르면 결과를 모르는 것으로 본다")
    void orderNoMismatchIsUnknown() {
        // given
        respondWith(200, payment("DONE", "another-order-no", 30_000));

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
    }

    @Test
    @DisplayName("2xx인데 본문이 비어 있으면 결과를 모르는 것으로 본다")
    void emptySuccessBodyIsUnknown() {
        // given
        respondWith(200, "");

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
    }

    @Test
    @DisplayName("연결 거부는 요청이 PG에 닿지 않은 것이 확실하므로 결과 모름이 아니라 연동 오류(PaymentGatewayException)다")
    void connectionRefusedIsGatewayError() {
        // given
        server.expect(requestTo(CONFIRM_URL)).andRespond(httpRequest -> {
            throw new ConnectException("Connection refused");
        });

        // when // then
        assertThatThrownBy(() -> client.approve(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining(TossPaymentClient.CONNECTION_FAILED);
    }

    @Test
    @DisplayName("연결 타임아웃은 연결이 맺어지기 전이라 요청이 나가지 않았으므로 연동 오류(PaymentGatewayException)다")
    void connectTimeoutIsGatewayError() {
        // given
        server.expect(requestTo(CONFIRM_URL)).andRespond(httpRequest -> {
            throw new HttpConnectTimeoutException("HTTP connect timed out");
        });

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("읽기 타임아웃은 요청이 닿았는지 모르므로 PaymentTimeoutException이다")
    void networkErrorIsUnknown() {
        // given
        server.expect(requestTo(CONFIRM_URL)).andRespond(httpRequest -> {
            throw new SocketTimeoutException("Read timed out");
        });

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
    }
}
