package com.payment.payment.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;

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
 *   <li>{@link #willFailCancel()}: 취소 호출이 실패하는 시나리오 (보상 작업 실패)</li>
 *   <li>{@link #willApproveWithAmount(long)}: 요청과 다른 금액으로 승인 (승인 후 보상 취소 경로 재현)</li>
 *   <li>{@link #approveCallCount()}: PG가 실제로 몇 번 호출됐는지. 멱등성 검증(3-4)의 핵심 증거</li>
 * </ul>
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

    private final Duration initialDelay;

    private volatile Scenario scenario = Scenario.APPROVE;
    private volatile String declineReason = "한도 초과";
    private volatile Duration delay;
    private volatile boolean cancelFails = false;
    private volatile Long approvedAmountOverride = null;

    private final AtomicInteger approveCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final List<String> canceledTransactionIds = new CopyOnWriteArrayList<>();

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

    public void willFailCancel() {
        this.cancelFails = true;
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
        this.cancelFails = false;
        this.approvedAmountOverride = null;
        approveCalls.set(0);
        cancelCalls.set(0);
        canceledTransactionIds.clear();
    }

    @Override
    public PgApproveResult approve(PgApproveRequest request) {
        approveCalls.incrementAndGet();
        simulateLatency();
        return switch (scenario) {
            case APPROVE -> new PgApproveResult("fake-" + UUID.randomUUID(),
                    approvedAmountOverride != null ? approvedAmountOverride : request.amount());
            case DECLINE -> throw new PaymentDeclinedException(declineReason);
            case TIMEOUT -> throw new PaymentTimeoutException();
        };
    }

    @Override
    public void cancel(String pgTransactionId, String reason) {
        cancelCalls.incrementAndGet();
        simulateLatency();
        if (cancelFails) {
            throw new PaymentCancelFailedException(pgTransactionId);
        }
        canceledTransactionIds.add(pgTransactionId);
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
