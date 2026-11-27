package com.payment.payment.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;

/**
 * 우리 서버 → 토스 요청 길에서 생기는 네트워크 장애를 <b>실제 소켓</b>으로 만들어, {@link TossPaymentClient}가 올바르게 분류하는지 본다.
 * (phase-04 4-6)
 * <p>
 * {@code TossPaymentClientTest} 등은 {@code MockRestServiceServer}로 예외를 우리가 직접 던진다. 그래서 "실제로 끊기면 이 예외가
 * 올라온다"는 가정이 검증되지 않는다. 여기서는 운영과 같은 {@link TossPaymentConfig#tossRestClient}(JDK HttpClient, 타임아웃 설정)로
 * WireMock(localhost)에 요청을 보내고, WireMock이 연결을 끊거나 응답을 늦춘다.
 * <pre>
 * 연결 거부 (요청이 나가지 않음)            → PaymentGatewayException(CONNECTION_FAILED). 돈이 나갈 수 없다 → FAILED
 * 읽기 타임아웃, 연결 리셋, 응답 없이 끊김,
 * 응답 도중 끊김 (요청은 나갔을 수 있음)      → PaymentTimeoutException. 결과 모름 → PAYMENT_UNKNOWN / CANCEL_UNKNOWN / 다음 회차
 * </pre>
 * 뒤쪽을 연결 실패로 잘못 분류하면, 돈이 나갔을 수 있는 결제를 FAILED로 확정하고 재고를 복구하게 된다.
 * <p>
 * 실측한 예외 (JDK HttpClient, 2026-11-27):
 * <ul>
 *   <li>연결 거부: {@code ResourceAccessException} ← {@code ConnectException}</li>
 *   <li>읽기 타임아웃: {@code ResourceAccessException} ← {@code HttpTimeoutException("Request cancelled")}.
 *       연결 타임아웃({@code HttpConnectTimeoutException})의 <b>부모 타입</b>이다. {@code isNotDelivered}가 부모 타입으로 검사하면
 *       읽기 타임아웃이 연결 실패로 분류된다.</li>
 *   <li>연결 리셋·응답 없이 끊김: {@code IOException("HTTP/1.1 header parser received no bytes")} ← {@code SocketException}/{@code EOFException}</li>
 *   <li>본문 도중 끊김: {@code IOException("Illegal character in chunk size")}</li>
 * </ul>
 * <p>
 * 연결 타임아웃은 응답하지 않는 IP가 필요해 환경마다 결과가 흔들리므로 여기서 다루지 않는다. ({@code TossPaymentClientTest}에서 예외로 확인)
 */
class TossPaymentClientNetworkFaultTest {

    private static final String ORDER_NO = "0190aaaa-bbbb-7ccc-8ddd-eeeeffff0001";
    private static final String PAYMENT_KEY = "payment-key-1";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private WireMockServer wireMock;

    /** 토스 API 세 가지. 장애는 API와 상관없이 같은 분류를 받아야 한다. */
    enum Call {
        APPROVE(client -> client.approve(new PgApproveRequest(ORDER_NO, PAYMENT_KEY, 30_000L))),
        CANCEL(client -> client.cancel(PAYMENT_KEY, "고객 변심", "cancel-1")),
        LOOKUP(client -> client.lookup(PAYMENT_KEY));

        private final Function<TossPaymentClient, Object> invoke;

        Call(Function<TossPaymentClient, Object> invoke) {
            this.invoke = invoke;
        }

        Object on(TossPaymentClient client) {
            return invoke.apply(client);
        }
    }

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    private static TossPaymentClient clientFor(String baseUrl) {
        TossPaymentProperties properties = new TossPaymentProperties("test-secret", baseUrl, Duration.ofSeconds(1), READ_TIMEOUT);
        return new TossPaymentClient(new TossPaymentConfig().tossRestClient(properties));
    }

    private TossPaymentClient client() {
        return clientFor(wireMock.baseUrl());
    }

    private int receivedRequests() {
        return wireMock.findAll(anyRequestedFor(anyUrl())).size();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    @DisplayName("연결 거부(아무도 듣지 않는 포트): 요청이 PG에 닿지 않았으므로 연결 실패(PaymentGatewayException, CONNECTION_FAILED)다")
    void connectionRefusedIsNotDelivered(Call call) throws IOException {
        // given: 빈 포트를 잡았다가 바로 닫아, 그 포트로는 연결이 거부되게 한다
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        TossPaymentClient client = clientFor("http://127.0.0.1:" + closedPort);

        // when & then
        assertThatThrownBy(() -> call.on(client))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining(TossPaymentClient.CONNECTION_FAILED);
    }

    static Stream<Arguments> unknownResultFaults() {
        return Stream.of(Call.values()).flatMap(call -> Stream.of(
                Arguments.of(call, "읽기 타임아웃 (응답이 1.5초 늦음)", aResponse().withStatus(200).withFixedDelay(1_500)),
                Arguments.of(call, "연결 리셋", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
                Arguments.of(call, "응답 없이 끊김", aResponse().withFault(Fault.EMPTY_RESPONSE)),
                Arguments.of(call, "응답 헤더 뒤 본문 도중 끊김", aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK))));
    }

    @ParameterizedTest(name = "{0} - {1}")
    @MethodSource("unknownResultFaults")
    @DisplayName("요청이 나간 뒤 생긴 네트워크 장애: PG가 처리했을 수 있으므로 결과 모름(PaymentTimeoutException)이다. 연결 실패로 분류하지 않는다")
    void faultAfterRequestSentIsUnknown(Call call, String fault, ResponseDefinitionBuilder response) {
        // given
        wireMock.stubFor(any(anyUrl()).willReturn(response));
        TossPaymentClient client = client();

        // when
        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> call.on(client)).isInstanceOf(PaymentTimeoutException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        // then: 요청은 실제로 PG(WireMock)에 닿았고, 읽기 타임아웃이 응답 지연(1.5초)보다 먼저 끊었다
        assertThat(elapsedMillis).isLessThan(1_200);
        if (call == Call.LOOKUP) {
            // JDK HttpClient는 GET이 연결 리셋·빈 응답으로 끊기면 스스로 1번 다시 보낸다(실측). 조회는 PG 상태를 바꾸지 않아 무해하다.
            assertThat(receivedRequests()).isBetween(1, 2);
        } else {
            // 승인·취소(POST)는 HTTP 클라이언트가 몰래 다시 보내지 않는다. 다시 보낼지는 PgRetry만 정한다.
            assertThat(receivedRequests()).isEqualTo(1);
        }
    }
}
