package com.payment.payment.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

/**
 * 실제 PG가 정해지기 전까지 쓰는 가짜 PG. 시나리오를 코드로 조작할 수 있다.
 * <ul>
 *   <li>{@link #willApprove()} / {@link #willDecline(String)} / {@link #willTimeout()}: 다음 승인 호출의 결과</li>
 *   <li>{@link #delay(Duration)}: 응답 지연. 승인·취소 모두에 적용된다. (DB 커넥션 점유 실험용)</li>
 *   <li>{@link #willFailCancel()} / {@link #willTimeoutCancel()} / {@link #willFailCancelConnection()} /
 *       {@link #willAlreadyCanceled()}: 다음 취소 호출의 결과 (거절 / 결과 모름 / 연결 실패 / 이미 취소됨)</li>
 *   <li>{@link #willApproveWithAmount(long)}: 요청과 다른 금액으로 승인 (승인 후 보상 취소 경로 재현)</li>
 *   <li>{@link #approveCallCount()}: PG가 실제로 몇 번 호출됐는지. 멱등성 검증(3-4)의 핵심 증거</li>
 *   <li>{@link #willLookup} / {@link #willLookupNotFound()} / {@link #willTimeoutLookup()}: 다음 조회의 결과 (대사, 4-4)</li>
 *   <li>{@link #willLoseApproveResponseOnce()} / {@link #willLoseCancelResponseOnce()}: 다음 호출은 처리하지만 응답을 잃는다 (4-5)</li>
 * </ul>
 * <h3>PG 멱등키 (phase-04 4-5)</h3>
 * 토스처럼 멱등키별로 첫 응답을 저장해 두고, 같은 키로 다시 오면 처리하지 않고 그 응답을 돌려준다.
 * 저장하는 것은 PG가 실제로 처리한 결과(승인, 거절, 취소, 이미 취소됨)뿐이다. 타임아웃·연결 실패는 저장하지 않는다.
 * 호출 횟수({@link #approveCallCount()})와 실제 처리 횟수({@link #approveProcessedCount()})를 따로 센다.
 * 동시성 테스트에서 여러 스레드가 호출하므로 상태는 스레드 안전하게 관리한다.
 * 시나리오 설정은 테스트 시작 전에 한 번 하고, 테스트 사이에는 {@link #reset()}으로 초기화한다.
 * <p>
 * 서버로 띄워 부하 테스트(k6)를 할 때는 코드로 지연을 걸 수 없으므로 {@code app.pg.fake.delay}(기본 0)로 초기 지연을 정한다.
 * {@link #reset()}은 이 초기 지연으로 되돌린다.
 */
@Component
@ConditionalOnProperty(name = "app.pg.provider", havingValue = "fake", matchIfMissing = true)
public class FakePaymentClient implements PaymentClient {

    private enum Scenario { APPROVE, DECLINE, TIMEOUT }

    private enum CancelScenario { CANCEL, DECLINE, TIMEOUT, CONNECTION_FAILED, ALREADY_CANCELED }

    private enum LookupScenario { NOT_SET, FOUND, NOT_FOUND, TIMEOUT, REJECTED }

    private final Duration initialDelay;

    private volatile Scenario scenario = Scenario.APPROVE;
    private volatile String declineReason = "한도 초과";
    private volatile Duration delay;
    private volatile CancelScenario cancelScenario = CancelScenario.CANCEL;
    private volatile Long approvedAmountOverride = null;
    private volatile LookupScenario lookupScenario = LookupScenario.NOT_SET;
    private volatile PgPaymentLookup lookupResult = null;

    private final AtomicInteger approveCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final AtomicInteger lookupCalls = new AtomicInteger();
    private final List<String> canceledTransactionIds = new CopyOnWriteArrayList<>();

    private final Map<String, Supplier<PgApproveResult>> approveReplies = new ConcurrentHashMap<>();
    private final Map<String, Supplier<PgCancelResult>> cancelReplies = new ConcurrentHashMap<>();
    private final AtomicInteger approveProcessed = new AtomicInteger();
    private final AtomicInteger cancelProcessed = new AtomicInteger();
    private final List<String> approveIdempotencyKeys = new CopyOnWriteArrayList<>();
    private final List<String> cancelIdempotencyKeys = new CopyOnWriteArrayList<>();
    private volatile boolean loseApproveResponse = false;
    private volatile boolean loseCancelResponse = false;

    /** 단위 테스트용. 지연 없이 시작한다. */
    public FakePaymentClient() {
        this(Duration.ZERO);
    }

    /**
     * 지연을 {@code Duration}이 아니라 문자열로 받아 직접 파싱한다.
     * {@code "300ms"} 같은 표기는 Spring Boot 변환기가 있어야 {@code Duration}으로 바뀌는데,
     * {@code ApplicationContextRunner} 같은 최소 컨텍스트에는 그 변환기가 없어 기동이 실패하기 때문이다.
     */
    @Autowired
    public FakePaymentClient(@Value("${app.pg.fake.delay:0ms}") String initialDelay) {
        this(DurationStyle.detectAndParse(initialDelay));
    }

    private FakePaymentClient(Duration initialDelay) {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("가짜 PG 지연은 0 이상이어야 합니다.");
        }
        this.initialDelay = initialDelay;
        this.delay = initialDelay;
    }

    public void willApprove() {
        this.scenario = Scenario.APPROVE;
    }

    public void willDecline(String reason) {
        this.declineReason = reason;
        this.scenario = Scenario.DECLINE;
    }

    /** 지연 시간만큼 기다린 뒤 타임아웃을 던진다. 실제 PG의 "응답 대기 후 포기"를 흉내 낸다. */
    public void willTimeout() {
        this.scenario = Scenario.TIMEOUT;
    }

    /** 승인은 하되 요청과 다른 금액으로 승인한다. PG 승인 후 보상 취소 경로를 재현하는 데 쓴다. */
    public void willApproveWithAmount(long approvedAmount) {
        this.approvedAmountOverride = approvedAmount;
        this.scenario = Scenario.APPROVE;
    }

    public void delay(Duration delay) {
        this.delay = delay;
    }

    /** PG가 취소를 처리한다. (기본값) */
    public void willCancel() {
        this.cancelScenario = CancelScenario.CANCEL;
    }

    /** PG가 취소를 명확히 거절한다. 결제는 살아 있다. */
    public void willFailCancel() {
        this.cancelScenario = CancelScenario.DECLINE;
    }

    /** 취소 응답을 받지 못한다. PG에서는 취소됐을 수도 있다. */
    public void willTimeoutCancel() {
        this.cancelScenario = CancelScenario.TIMEOUT;
    }

    /** 연결이 맺어지지 않아 취소 요청이 PG에 닿지 않는다. */
    public void willFailCancelConnection() {
        this.cancelScenario = CancelScenario.CONNECTION_FAILED;
    }

    /** PG가 "이미 취소된 결제"라고 답한다. (우리가 모르는 경로로 이미 취소됨) */
    public void willAlreadyCanceled() {
        this.cancelScenario = CancelScenario.ALREADY_CANCELED;
    }

    /** 다음 조회가 이 상태와 금액을 돌려준다. (대사 시나리오, 4-4) */
    public void willLookup(PgPaymentStatus status, long totalAmount) {
        this.lookupResult = new PgPaymentLookup(status, totalAmount);
        this.lookupScenario = LookupScenario.FOUND;
    }

    /** PG가 그 결제를 모른다. ({@code NOT_FOUND_PAYMENT}) */
    public void willLookupNotFound() {
        this.lookupScenario = LookupScenario.NOT_FOUND;
    }

    /** 조회 응답을 받지 못한다. */
    /** 조회가 우리 요청을 거절한다 (일반 404 등). 같은 요청엔 계속 같은 응답 */
    public void willRejectLookup() {
        this.lookupScenario = LookupScenario.REJECTED;
    }

    public void willTimeoutLookup() {
        this.lookupScenario = LookupScenario.TIMEOUT;
    }

    /** 다음 승인 호출은 PG에서 처리되지만 응답이 오는 길에 끊긴다. (호출자는 타임아웃을 받는다) */
    public void willLoseApproveResponseOnce() {
        this.loseApproveResponse = true;
    }

    /** 다음 취소 호출은 PG에서 처리되지만 응답이 오는 길에 끊긴다. */
    public void willLoseCancelResponseOnce() {
        this.loseCancelResponse = true;
    }

    /** PG가 실제로 승인·거절을 처리한 횟수. 같은 키의 재요청(replay)은 세지 않는다. */
    public int approveProcessedCount() {
        return approveProcessed.get();
    }

    /** PG가 실제로 취소·거절을 처리한 횟수. 같은 키의 재요청(replay)은 세지 않는다. */
    public int cancelProcessedCount() {
        return cancelProcessed.get();
    }

    /** 승인 호출마다 받은 PG 멱등키 (호출 순서) */
    public List<String> approveIdempotencyKeys() {
        return List.copyOf(approveIdempotencyKeys);
    }

    /** 취소 호출마다 받은 PG 멱등키 (호출 순서) */
    public List<String> cancelIdempotencyKeys() {
        return List.copyOf(cancelIdempotencyKeys);
    }

    public int lookupCallCount() {
        return lookupCalls.get();
    }

    public int approveCallCount() {
        return approveCalls.get();
    }

    public int cancelCallCount() {
        return cancelCalls.get();
    }

    public List<String> canceledTransactionIds() {
        return List.copyOf(canceledTransactionIds);
    }

    public void reset() {
        this.scenario = Scenario.APPROVE;
        this.declineReason = "한도 초과";
        this.delay = initialDelay;
        this.cancelScenario = CancelScenario.CANCEL;
        this.approvedAmountOverride = null;
        this.lookupScenario = LookupScenario.NOT_SET;
        this.lookupResult = null;
        approveCalls.set(0);
        cancelCalls.set(0);
        lookupCalls.set(0);
        canceledTransactionIds.clear();
        approveReplies.clear();
        cancelReplies.clear();
        approveProcessed.set(0);
        cancelProcessed.set(0);
        approveIdempotencyKeys.clear();
        cancelIdempotencyKeys.clear();
        this.loseApproveResponse = false;
        this.loseCancelResponse = false;
    }

    @Override
    public PgApproveResult approve(PgApproveRequest request) {
        approveCalls.incrementAndGet();
        approveIdempotencyKeys.add(request.idempotencyKey());
        simulateLatency();

        Supplier<PgApproveResult> replay = approveReplies.get(request.idempotencyKey());
        if (replay != null) {
            return replay.get();
        }

        Supplier<PgApproveResult> reply = switch (scenario) {
            case APPROVE -> {
                PgApproveResult result = new PgApproveResult("fake-" + UUID.randomUUID(),
                        approvedAmountOverride != null ? approvedAmountOverride : request.amount());
                yield () -> result;
            }
            case DECLINE -> {
                String reason = declineReason;
                yield () -> {
                    throw new PaymentDeclinedException(reason);
                };
            }
            case TIMEOUT -> throw new PaymentTimeoutException();
        };
        Supplier<PgApproveResult> first = approveReplies.putIfAbsent(request.idempotencyKey(), reply);
        if (first != null) {
            return first.get();
        }
        approveProcessed.incrementAndGet();

        if (loseApproveResponse) {
            loseApproveResponse = false;
            throw new PaymentTimeoutException();
        }
        return reply.get();
    }

    @Override
    public PgCancelResult cancel(String paymentKey, String reason, String idempotencyKey) {
        cancelCalls.incrementAndGet();
        cancelIdempotencyKeys.add(idempotencyKey);
        simulateLatency();

        Supplier<PgCancelResult> replay = cancelReplies.get(idempotencyKey);
        if (replay != null) {
            return replay.get();
        }

        Supplier<PgCancelResult> reply = switch (cancelScenario) {
            case CANCEL -> () -> PgCancelResult.canceled();
            case ALREADY_CANCELED -> () -> PgCancelResult.wasAlreadyCanceled();
            case DECLINE -> () -> {
                throw new PaymentCancelDeclinedException("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다.");
            };
            case TIMEOUT -> throw new PaymentTimeoutException();
            case CONNECTION_FAILED -> throw new PaymentGatewayException(TossPaymentClient.CONNECTION_FAILED);
        };
        Supplier<PgCancelResult> first = cancelReplies.putIfAbsent(idempotencyKey, reply);
        if (first != null) {
            return first.get();
        }
        cancelProcessed.incrementAndGet();
        if (cancelScenario == CancelScenario.CANCEL) {
            canceledTransactionIds.add(paymentKey);
        }

        if (loseCancelResponse) {
            loseCancelResponse = false;
            throw new PaymentTimeoutException();
        }
        return reply.get();
    }

    /** 시나리오를 정하지 않고 조회하면 테스트 준비가 빠진 것이므로 바로 알린다. (기본 결과를 지어내지 않는다) */
    @Override
    public Optional<PgPaymentLookup> lookup(String paymentKey) {
        lookupCalls.incrementAndGet();
        simulateLatency();
        return switch (lookupScenario) {
            case FOUND -> Optional.of(lookupResult);
            case NOT_FOUND -> Optional.empty();
            case TIMEOUT -> throw new PaymentTimeoutException();
            case REJECTED -> throw new PaymentLookupRejectedException(404, "NOT_FOUND");
            case NOT_SET -> throw new IllegalStateException("가짜 PG 조회 시나리오가 정해지지 않았습니다. paymentKey=" + paymentKey);
        };
    }

    private void simulateLatency() {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentTimeoutException();
        }
    }
}
