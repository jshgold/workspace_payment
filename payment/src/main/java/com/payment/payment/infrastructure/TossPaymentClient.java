package com.payment.payment.infrastructure;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 토스페이먼츠 실제 연동 구현체. {@code app.pg.provider=toss}(= {@code toss} 프로필)일 때만 빈으로 등록된다.
 * <p>
 * 승인은 {@code POST /v1/payments/confirm} 이다. 취소는 4-3에서 채운다.
 *
 * <h3>응답 분류: "돈이 나갔는지 아는가"</h3>
 * <pre>
 * 2xx + DONE + 주문번호 일치   → 승인 성공
 * 4xx (아래 예외 제외)          → 명확한 거절 (PaymentDeclinedException). 돈 안 나감 → 호출자가 PENDING으로 복구
 * 401, UNAUTHORIZED_KEY 등      → 우리 연동 문제 (PaymentGatewayException). 돈 안 나감 → PENDING 복구
 * 5xx, 타임아웃, I/O 오류        → 결과 모름 (PaymentTimeoutException). 호출자가 PAYING 유지
 * ALREADY_PROCESSED_PAYMENT, 409 → 결과 모름. 이미 승인됐을 수 있어 되돌리면 안 된다
 * 2xx인데 본문이 이상함           → 결과 모름. 돈이 나갔을 수 있다
 * </pre>
 * 4xx와 5xx를 같게 다루면 둘 중 하나가 사고가 난다. 5xx를 거절로 보면 승인된 결제를 PENDING으로 되돌려 이중 결제가 되고,
 * 4xx를 모름으로 보면 확정된 거절이 PAYING으로 남아 사용자가 재시도하지 못한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.pg.provider", havingValue = "toss")
@RequiredArgsConstructor
public class TossPaymentClient implements PaymentClient {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private static final String STATUS_DONE = "DONE";

    /**
     * 요청이 승인 단계까지 가지 못한 우리 쪽 연동 문제(키 오류). 구매자 거절이 아니다.
     * <p>
     * FORBIDDEN_REQUEST(403 "허용되지 않은 요청")는 여기에 넣지 않는다. 범용 코드라서 키 문제로 단정할 수 없고,
     * 실제 테스트에서 같은 키로 정상 승인된 직후 결제창 금액과 다른 금액으로 승인했을 때 이 코드가 돌아왔다.
     */
    private static final Set<String> CONFIG_ERROR_CODES = Set.of("UNAUTHORIZED_KEY", "INVALID_API_KEY");

    /** 4xx지만 이미 승인됐거나 승인 중일 수 있는 코드. 거절로 확정하면 안 된다. */
    private static final Set<String> UNKNOWN_RESULT_CODES = Set.of("ALREADY_PROCESSED_PAYMENT", "IDEMPOTENT_REQUEST_PROCESSING");

    /** 연결 실패를 {@link PaymentGatewayException}으로 알릴 때 쓰는 우리 쪽 코드. (PG가 준 코드가 아니다) */
    static final String CONNECTION_FAILED = "CONNECTION_FAILED";

    private final RestClient tossRestClient;

    /**
     * 연결이 맺어지기 전에 실패했는지 본다. 이때는 요청 바이트가 PG로 나가지 않았으므로 승인이 일어날 수 없다.
     * 연결 거부, 호스트를 찾지 못함, 연결 타임아웃이 해당한다. 읽기 타임아웃은 요청이 이미 나갔을 수 있어 해당하지 않는다.
     */
    private static boolean isNotDelivered(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PgApproveResult approve(PgApproveRequest request) {
        try {
            return tossRestClient.post()
                    .uri(CONFIRM_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ConfirmBody(request.paymentKey(), request.orderNo(), request.amount()))
                    .exchange((httpRequest, response) -> toResult(request, response));
        } catch (ResourceAccessException e) {
            if (isNotDelivered(e)) {
                // 연결 자체가 맺어지지 않았다. 요청이 PG에 닿지 않았으므로 승인될 수 없다. (명확한 실패)
                log.warn("토스 승인 호출 연결 실패. 요청이 PG에 닿지 않았다. orderNo={}", request.orderNo(), e);
                throw new PaymentGatewayException(CONNECTION_FAILED);
            }
            // 읽기 타임아웃, 응답 도중 끊김 등. 요청이 PG에 닿았는지, 승인됐는지 알 수 없다.
            log.warn("토스 승인 호출 중 네트워크 오류. 승인 여부를 알 수 없다. orderNo={}", request.orderNo(), e);
            throw new PaymentTimeoutException();
        } catch (RestClientException e) {
            log.warn("토스 승인 응답 처리 중 오류. 승인 여부를 알 수 없다. orderNo={}", request.orderNo(), e);
            throw new PaymentTimeoutException();
        }
    }

    @Override
    public void cancel(String pgTransactionId, String reason) {
        throw new UnsupportedOperationException("토스 취소 연동은 phase-04 4-3에서 구현한다.");
    }

    private PgApproveResult toResult(PgApproveRequest request, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws java.io.IOException {
        int status = response.getStatusCode().value();

        if (response.getStatusCode().is2xxSuccessful()) {
            return toApproved(request, readBody(response, TossPayment.class), status);
        }

        TossError error = readBody(response, TossError.class);
        String code = error == null ? null : error.code();
        String message = error == null || error.message() == null ? "사유를 알 수 없습니다." : error.message();
        log.warn("토스 승인 실패 응답. orderNo={}, status={}, code={}, message={}", request.orderNo(), status, code, message);

        if (status >= 500 || status == 409 || (code != null && UNKNOWN_RESULT_CODES.contains(code))) {
            throw new PaymentTimeoutException();
        }
        if (status == 401 || (code != null && CONFIG_ERROR_CODES.contains(code))) {
            throw new PaymentGatewayException(code == null ? "HTTP " + status : code);
        }
        if (status >= 400) {
            throw new PaymentDeclinedException(message);
        }
        // 3xx 등 예상하지 못한 응답은 결과를 알 수 없다.
        throw new PaymentTimeoutException();
    }

    private PgApproveResult toApproved(PgApproveRequest request, TossPayment body, int status) {
        // 2xx인데 본문을 믿을 수 없으면 돈이 나갔을 수 있다. 거절로 되돌리지 않고 "모름"으로 둔다.
        if (body == null || body.paymentKey() == null || body.paymentKey().isBlank()) {
            log.error("토스 승인 응답 본문이 비정상이다. 승인 여부를 알 수 없다. orderNo={}, status={}", request.orderNo(), status);
            throw new PaymentTimeoutException();
        }
        if (!STATUS_DONE.equals(body.status())) {
            log.error("토스 승인 응답 상태가 DONE이 아니다. orderNo={}, status={}", request.orderNo(), body.status());
            throw new PaymentTimeoutException();
        }
        if (!request.orderNo().equals(body.orderId())) {
            log.error("토스 승인 응답의 주문번호가 요청과 다르다. 요청={}, 응답={}", request.orderNo(), body.orderId());
            throw new PaymentTimeoutException();
        }
        return new PgApproveResult(body.paymentKey(), body.totalAmount());
    }

    /** 본문이 비었거나 JSON이 아니어도 예외를 던지지 않고 null을 돌려준다. 상태 코드만으로 분류할 수 있어야 한다. */
    private <T> T readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, Class<T> type) {
        try {
            return response.bodyTo(type);
        } catch (RuntimeException e) {
            log.warn("토스 응답 본문을 읽지 못했다. type={}", type.getSimpleName(), e);
            return null;
        }
    }

    private record ConfirmBody(String paymentKey, String orderId, long amount) {
    }

    /** 승인 성공 응답(Payment 객체) 중 필요한 필드만 읽는다. 새 필드가 추가되어도 영향받지 않는다. */
    private record TossPayment(String paymentKey, String orderId, String status, long totalAmount) {
    }

    private record TossError(String code, String message) {
    }
}
