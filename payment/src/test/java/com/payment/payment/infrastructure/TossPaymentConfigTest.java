package com.payment.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제로 HTTP를 주고받아 {@link TossPaymentConfig}가 만든 클라이언트의 인증 헤더와 읽기 타임아웃을 확인한다.
 * JDK 내장 HTTP 서버를 쓰므로 외부 PG도, 추가 의존성도 필요 없다. (응답 시나리오 전반은 4-6에서 WireMock으로 다룬다)
 */
class TossPaymentConfigTest {

    private static final String ORDER_NO = "0190aaaa-bbbb-7ccc-8ddd-eeeeffff0001";

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private TossPaymentClient clientWithReadTimeout(Duration readTimeout) {
        TossPaymentProperties properties = new TossPaymentProperties(
                "test-secret", "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1), readTimeout);
        return new TossPaymentClient(new TossPaymentConfig().tossRestClient(properties));
    }

    private PgApproveRequest request() {
        return new PgApproveRequest(ORDER_NO, "payment-key-1", 30_000L);
    }

    @Test
    @DisplayName("Authorization 헤더는 Basic base64(시크릿키:) 형식이다. 키 뒤 콜론이 빠지면 안 된다")
    void sendsBasicAuthorization() {
        // given
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/payments/confirm", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("""
                    {"paymentKey":"payment-key-1","orderId":"%s","status":"DONE","totalAmount":30000}
                    """.formatted(ORDER_NO)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        // when
        PgApproveResult result = clientWithReadTimeout(Duration.ofSeconds(2)).approve(request());

        // then
        String expected = "Basic " + Base64.getEncoder().encodeToString("test-secret:".getBytes(StandardCharsets.UTF_8));
        assertThat(authorization.get()).isEqualTo(expected);
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("읽기 타임아웃 안에 응답이 없으면 PaymentTimeoutException이다. 호출 스레드가 무기한 묶이지 않는다")
    void readTimeout() {
        // given
        server.createContext("/v1/payments/confirm", exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        TossPaymentClient client = clientWithReadTimeout(Duration.ofMillis(300));

        // when
        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        // then
        assertThat(elapsedMillis).isLessThan(1_200);
    }
}
