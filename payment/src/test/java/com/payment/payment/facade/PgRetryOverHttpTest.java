package com.payment.payment.facade;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.domain.PgCancelResult;
import com.payment.payment.infrastructure.TossPaymentClient;
import com.payment.payment.infrastructure.TossPaymentConfig;
import com.payment.payment.infrastructure.TossPaymentProperties;

/**
 * 결과 모름 뒤 {@link PgRetry}가 다시 보낼 때, 실제 HTTP 요청 두 번에 <b>같은 PG 멱등키</b>가 실리는지 본다. (phase-04 4-5 → 4-6)
 * <p>
 * 4-5의 {@code PgIdempotencyKeyTest}는 가짜 PG(HTTP 없음)로 확인했다. 여기서는 실제 {@link TossPaymentClient}가 WireMock(localhost)에
 * 요청을 보내고, 첫 요청은 장애로 끊고 두 번째 요청에는 정상 응답한다. 토스는 같은 키면 처리하지 않고 첫 응답을 돌려주므로,
 * 키가 두 번 같아야 재시도가 이중 승인·이중 취소가 되지 않는다. (토스가 실제로 그렇게 하는지는 수동 E2E에서 확인)
 */
class PgRetryOverHttpTest {

    private static final String ORDER_NO = "0190aaaa-bbbb-7ccc-8ddd-eeeeffff0001";
    private static final String PAYMENT_KEY = "payment-key-1";
    private static final String CONFIRM_URL = "/v1/payments/confirm";
    private static final String CANCEL_URL = "/v1/payments/" + PAYMENT_KEY + "/cancel";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String SECOND = "second";

    private WireMockServer wireMock;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        TossPaymentProperties properties =
                new TossPaymentProperties("test-secret", wireMock.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(300));
        client = new TossPaymentClient(new TossPaymentConfig().tossRestClient(properties));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    /** 첫 요청에는 {@code first}, 두 번째 요청부터는 {@code second}로 응답하게 한다. */
    private void stubFirstThen(String url, ResponseDefinitionBuilder first, ResponseDefinitionBuilder second) {
        wireMock.stubFor(post(urlEqualTo(url)).inScenario(url)
                .whenScenarioStateIs(Scenario.STARTED).willReturn(first).willSetStateTo(SECOND));
        wireMock.stubFor(post(urlEqualTo(url)).inScenario(url)
                .whenScenarioStateIs(SECOND).willReturn(second));
    }

    private List<String> sentKeys(String url) {
        return wireMock.findAll(postRequestedFor(urlEqualTo(url))).stream()
                .map((LoggedRequest request) -> request.getHeader(IDEMPOTENCY_KEY))
                .toList();
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    @Test
    @DisplayName("승인: 첫 요청이 읽기 타임아웃 → 1번 재시도해 승인을 받는다. 두 요청 모두 Idempotency-Key가 confirm-{주문번호}로 같다")
    void approveRetryCarriesSameKey() {
        // given
        stubFirstThen(CONFIRM_URL,
                aResponse().withStatus(200).withFixedDelay(1_500),
                json("""
                        {"paymentKey":"%s","orderId":"%s","status":"DONE","totalAmount":30000}
                        """.formatted(PAYMENT_KEY, ORDER_NO)));
        PgApproveRequest request = new PgApproveRequest(ORDER_NO, PAYMENT_KEY, 30_000L);

        // when
        PgApproveResult result = PgRetry.onceOnUnknown(Duration.ZERO, "승인", ORDER_NO, () -> client.approve(request));

        // then
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
        assertThat(sentKeys(CONFIRM_URL)).containsExactly("confirm-" + ORDER_NO, "confirm-" + ORDER_NO);
    }

    @Test
    @DisplayName("취소: 첫 요청이 연결 리셋 → 1번 재시도해 취소를 받는다. 두 요청 모두 Idempotency-Key가 같은 취소 시도 키다")
    void cancelRetryCarriesSameKey() {
        // given
        stubFirstThen(CANCEL_URL,
                aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                json("""
                        {"paymentKey":"%s","status":"CANCELED"}
                        """.formatted(PAYMENT_KEY)));

        // when
        PgCancelResult result = PgRetry.onceOnUnknown(Duration.ZERO, "취소", ORDER_NO,
                () -> client.cancel(PAYMENT_KEY, "고객 변심", "cancel-7"));

        // then
        assertThat(result).isEqualTo(PgCancelResult.canceled());
        assertThat(sentKeys(CANCEL_URL)).containsExactly("cancel-7", "cancel-7");
    }

    @Test
    @DisplayName("승인: 재시도도 결과 모름이면 더 보내지 않고 PaymentTimeoutException을 던진다(→ PAYMENT_UNKNOWN). 요청은 같은 키로 딱 2번")
    void approveStopsAfterOneRetry() {
        // given
        stubFirstThen(CONFIRM_URL,
                aResponse().withFault(Fault.EMPTY_RESPONSE),
                aResponse().withFault(Fault.EMPTY_RESPONSE));
        PgApproveRequest request = new PgApproveRequest(ORDER_NO, PAYMENT_KEY, 30_000L);

        // when & then
        assertThatThrownBy(() -> PgRetry.onceOnUnknown(Duration.ZERO, "승인", ORDER_NO, () -> client.approve(request)))
                .isInstanceOf(PaymentTimeoutException.class);
        assertThat(sentKeys(CONFIRM_URL)).containsExactly("confirm-" + ORDER_NO, "confirm-" + ORDER_NO);
    }
}
