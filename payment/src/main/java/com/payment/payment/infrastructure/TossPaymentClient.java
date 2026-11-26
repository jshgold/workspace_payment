package com.payment.payment.infrastructure;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentLookupRejectedException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.domain.PgCancelResult;
import com.payment.payment.domain.PgPaymentLookup;
import com.payment.payment.domain.PgPaymentStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 토스페이먼츠 실제 연동 구현체. {@code app.pg.provider=toss}(= {@code toss} 프로필)일 때만 빈으로 등록된다.
 * <p>
 * 승인은 {@code POST /v1/payments/confirm}, 취소는 {@code POST /v1/payments/{paymentKey}/cancel} 이다. (취소 분류는 {@link #cancel})
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
    private static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String LOOKUP_PATH = "/v1/payments/{paymentKey}";
    /** 결제 조회 404. PG가 그 결제를 모른다. (공식 에러 코드 문서 "결제 조회" 표) */
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";

    /** 전액 환불이 이미 끝났다는 뜻의 취소 응답 코드. 실패가 아니라 성공으로 본다. (intent 8절) */
    private static final Set<String> ALREADY_CANCELED_CODES = Set.of("ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT");

    /** 4xx지만 앞선 요청이 처리됐는지 알 수 없는 취소 응답 코드. 일시 오류, 반복 요청 차단, 멱등 요청 처리 중. */
    private static final Set<String> CANCEL_UNKNOWN_RESULT_CODES =
            Set.of("PROVIDER_ERROR", "FORBIDDEN_CONSECUTIVE_REQUEST", "IDEMPOTENT_REQUEST_PROCESSING");

    /**
     * 요청이 승인 단계까지 가지 못한 우리 쪽 연동 문제(키 오류). 구매자 거절이 아니다.
     * <p>
     * FORBIDDEN_REQUEST(403 "허용되지 않은 요청")는 여기에 넣지 않는다. 범용 코드라서 키 문제로 단정할 수 없고,
     * 실제 테스트에서 같은 키로 정상 승인된 직후 결제창 금액과 다른 금액으로 승인했을 때 이 코드가 돌아왔다.
     */
    private static final Set<String> CONFIG_ERROR_CODES = Set.of("UNAUTHORIZED_KEY", "INVALID_API_KEY");

    /** 4xx지만 이미 승인됐거나 승인 중일 수 있는 코드. 거절로 확정하면 안 된다. */
    private static final Set<String> UNKNOWN_RESULT_CODES = Set.of("ALREADY_PROCESSED_PAYMENT", "IDEMPOTENT_REQUEST_PROCESSING");

    /** 4xx지만 다시 조회하면 될 수 있는 조회 응답 코드. 일시 오류, 반복 요청 차단. (429는 코드와 상관없이 같다) */
    private static final Set<String> LOOKUP_TRANSIENT_CODES = Set.of("PROVIDER_ERROR", "FORBIDDEN_CONSECUTIVE_REQUEST");

    /**
     * PG 멱등키 헤더. 승인·취소(POST)에만 붙이고 조회(GET)에는 붙이지 않는다. 공식 문서상 GET에서는 무시된다.
     * 토스는 키 + API 키 + URL + 메서드가 같으면 실제로 처리하지 않고 첫 응답을 그대로 돌려준다. (15일 유효, 최대 300자)
     */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

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
                    .header(IDEMPOTENCY_KEY_HEADER, request.idempotencyKey())
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

    /**
     * {@code POST /v1/payments/{paymentKey}/cancel}로 전액 취소한다. ({@code cancelAmount}를 보내지 않으면 전액 취소)
     * 분류는 승인과 같은 원칙("돈이 돌아갔는지 아는가")을 따른다.
     * <pre>
     * 2xx + status=CANCELED                          → 성공
     * ALREADY_CANCELED_PAYMENT, ALREADY_REFUND_PAYMENT → 성공 (전액 취소뿐이라 "이미 취소됨" = 환불 끝남)
     * 401, 키 오류, 연결 실패                          → 요청이 닿지 않음 (PaymentGatewayException). 결제 살아 있음
     * 5xx, 409, 일시 오류·반복 요청 코드, 읽기 타임아웃 → 결과 모름 (PaymentTimeoutException)
     * 2xx인데 본문이 이상함                            → 결과 모름. 취소됐을 수 있다
     * 그 밖의 4xx                                     → 명확한 거절 (PaymentCancelDeclinedException). 결제 살아 있음
     * </pre>
     * "이미 취소됨"을 거절로 보면, 응답 유실 뒤 다시 취소했을 때 환불이 끝난 주문을 PAID로 되돌리게 된다.
     */
    @Override
    public PgCancelResult cancel(String paymentKey, String reason, String idempotencyKey) {
        try {
            return tossRestClient.post()
                    .uri(CANCEL_PATH, paymentKey)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CancelBody(reason))
                    .exchange((httpRequest, response) -> toCancelResult(paymentKey, response));
        } catch (ResourceAccessException e) {
            if (isNotDelivered(e)) {
                log.warn("토스 취소 호출 연결 실패. 요청이 PG에 닿지 않았다. paymentKey={}", paymentKey, e);
                throw new PaymentGatewayException(CONNECTION_FAILED);
            }
            log.warn("토스 취소 호출 중 네트워크 오류. 취소 여부를 알 수 없다. paymentKey={}", paymentKey, e);
            throw new PaymentTimeoutException();
        } catch (RestClientException e) {
            log.warn("토스 취소 응답 처리 중 오류. 취소 여부를 알 수 없다. paymentKey={}", paymentKey, e);
            throw new PaymentTimeoutException();
        }
    }

    /**
     * {@code GET /v1/payments/{paymentKey}}로 결제를 조회한다. 대사 전용이며 PG 상태를 바꾸지 않는다.
     * <pre>
     * 2xx + 알려진 status + totalAmount   → 조회 결과
     * 404 NOT_FOUND_PAYMENT               → 빈 값 (PG가 그 결제를 모름. 판단은 대사가 한다)
     * 401, 키 오류, 연결 실패               → PaymentGatewayException (조회 실패, 다음 회차)
     * 5xx, 429, 일시 오류·반복 요청 차단,
     * 타임아웃, 이상한 본문                  → PaymentTimeoutException (조회 실패, 다음 회차)
     * 그 밖의 4xx (일반 404 NOT_FOUND, 400 등) → PaymentLookupRejectedException (조회 거절. 사람이 본다)
     * </pre>
     * {@code NOT_FOUND}(404, "존재하지 않는 정보")는 결제가 없다는 뜻으로 단정할 수 없어 "없음"으로 보지 않는다.
     * "없음"으로 잘못 분류하면 승인된 결제를 FAILED로 확정하게 된다. 그렇다고 일시 실패도 아니다. 같은 요청엔 같은 응답이
     * 오므로, 일시 실패로 세면 이런 건 몇 개가 매 회차 연속 실패를 채워 대사 전체를 멈춘다. (2026-11-26 변경)
     */
    @Override
    public Optional<PgPaymentLookup> lookup(String paymentKey) {
        try {
            return tossRestClient.get()
                    .uri(LOOKUP_PATH, paymentKey)
                    .exchange((httpRequest, response) -> toLookup(paymentKey, response));
        } catch (ResourceAccessException e) {
            if (isNotDelivered(e)) {
                log.warn("토스 결제 조회 연결 실패. paymentKey={}", paymentKey, e);
                throw new PaymentGatewayException(CONNECTION_FAILED);
            }
            log.warn("토스 결제 조회 중 네트워크 오류. paymentKey={}", paymentKey, e);
            throw new PaymentTimeoutException();
        } catch (RestClientException e) {
            log.warn("토스 결제 조회 응답 처리 중 오류. paymentKey={}", paymentKey, e);
            throw new PaymentTimeoutException();
        }
    }

    private Optional<PgPaymentLookup> toLookup(String paymentKey,
                                               RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws java.io.IOException {
        int status = response.getStatusCode().value();

        if (response.getStatusCode().is2xxSuccessful()) {
            TossLookupPayment body = readBody(response, TossLookupPayment.class);
            PgPaymentStatus pgStatus = body == null ? null : parseStatus(body.status());
            if (pgStatus == null || body.totalAmount() == null) {
                log.error("토스 결제 조회 응답이 비정상이다. paymentKey={}, status={}, body.status={}",
                        paymentKey, status, body == null ? null : body.status());
                throw new PaymentTimeoutException();
            }
            return Optional.of(new PgPaymentLookup(pgStatus, body.totalAmount()));
        }

        TossError error = readBody(response, TossError.class);
        String code = error == null ? null : error.code();
        if (NOT_FOUND_PAYMENT.equals(code)) {
            log.warn("토스가 존재하지 않는 결제라고 답했다. paymentKey={}", paymentKey);
            return Optional.empty();
        }

        log.warn("토스 결제 조회 실패 응답. paymentKey={}, status={}, code={}", paymentKey, status, code);
        if (status == 401 || (code != null && CONFIG_ERROR_CODES.contains(code))) {
            throw new PaymentGatewayException(code == null ? "HTTP " + status : code);
        }
        if (response.getStatusCode().is4xxClientError() && status != 429
                && (code == null || !LOOKUP_TRANSIENT_CODES.contains(code))) {
            throw new PaymentLookupRejectedException(status, code);
        }
        throw new PaymentTimeoutException();
    }

    private static PgPaymentStatus parseStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return PgPaymentStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PgCancelResult toCancelResult(String paymentKey, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws java.io.IOException {
        int status = response.getStatusCode().value();

        if (response.getStatusCode().is2xxSuccessful()) {
            TossCancelPayment body = readBody(response, TossCancelPayment.class);
            if (body == null || !STATUS_CANCELED.equals(body.status())) {
                log.error("토스 취소 응답이 비정상이다. 취소 여부를 알 수 없다. paymentKey={}, status={}, body.status={}",
                        paymentKey, status, body == null ? null : body.status());
                throw new PaymentTimeoutException();
            }
            return PgCancelResult.canceled();
        }

        TossError error = readBody(response, TossError.class);
        String code = error == null ? null : error.code();
        String message = error == null || error.message() == null ? "사유를 알 수 없습니다." : error.message();

        if (code != null && ALREADY_CANCELED_CODES.contains(code)) {
            log.info("토스가 이미 취소된 결제라고 답했다. 취소 성공으로 본다. paymentKey={}, code={}", paymentKey, code);
            return PgCancelResult.wasAlreadyCanceled();
        }

        log.warn("토스 취소 실패 응답. paymentKey={}, status={}, code={}, message={}", paymentKey, status, code, message);
        if (status >= 500 || status == 409 || (code != null && CANCEL_UNKNOWN_RESULT_CODES.contains(code))) {
            throw new PaymentTimeoutException();
        }
        if (status == 401 || (code != null && CONFIG_ERROR_CODES.contains(code))) {
            throw new PaymentGatewayException(code == null ? "HTTP " + status : code);
        }
        if (status >= 400) {
            throw new PaymentCancelDeclinedException(code == null ? "HTTP " + status : code, message);
        }
        throw new PaymentTimeoutException();
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

    private record CancelBody(String cancelReason) {
    }

    /** 취소 성공 응답(Payment 객체) 중 상태만 읽는다. */
    private record TossCancelPayment(String paymentKey, String status) {
    }

    /** 승인 성공 응답(Payment 객체) 중 필요한 필드만 읽는다. 새 필드가 추가되어도 영향받지 않는다. */
    private record TossPayment(String paymentKey, String orderId, String status, long totalAmount) {
    }

    /** 결제 조회 응답(Payment 객체) 중 대사에 필요한 필드만 읽는다. */
    private record TossLookupPayment(String paymentKey, String status, Long totalAmount) {
    }

    private record TossError(String code, String message) {
    }
}
