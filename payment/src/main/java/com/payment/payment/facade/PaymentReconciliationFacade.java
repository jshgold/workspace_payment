package com.payment.payment.facade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.payment.common.exception.ErrorCode;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentLookupRejectedException;
import com.payment.payment.domain.PgPaymentLookup;
import com.payment.payment.dto.ReconciliationRoundResult;
import com.payment.payment.service.CancelReconcileTarget;
import com.payment.payment.service.PaymentReconcileTarget;
import com.payment.payment.service.PaymentReconciliationStateService;
import com.payment.payment.service.ReconcileBatch;
import com.payment.payment.service.ReconciliationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제·취소 대사. 이 클래스에는 {@code @Transactional}이 없다. (phase-04 4-4)
 * <pre>
 * 1. 대상 id 조회 (짧은 읽기 트랜잭션, 락 없음)  상태가 된 지(updated_at) 기준 시간이 지난 건, id 순서로 묶음씩
 * 2. PG 결제 조회 (트랜잭션 밖)                 DB 커넥션·락을 쥐지 않는다
 * 3. 주문마다 짧은 트랜잭션                      FOR UPDATE → 현재 상태 재확인 → 도메인 메서드로 전이
 * </pre>
 * <b>대사는 조회만 한다.</b> 승인 요청은 다시 보내지 않는다. 고객이 동의한 그 순간의 결제만 유효하기 때문이다.
 * PG에 쓰기를 보내는 경우는 돈을 돌려주는 방향(금액이 다른 승인의 재취소)뿐이다. (intent 8절 "대사의 역할")
 * <p>
 * 운영 장치: 묶음 반복, 회차 시간 상한, PG 연속 실패 시 회차 중단, PG 호출 속도 제한, 회차 로그. (값은 {@link ReconciliationProperties})
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationFacade {

    private static final String PAYMENT_JOB = "승인 대사";
    private static final String CANCEL_JOB = "취소 대사";

    private final PaymentReconciliationStateService stateService;
    private final PaymentClient paymentClient;
    private final ReconciliationProperties properties;

    /** 대상 하나를 처리한 결과. 회차 집계와 연속 실패 판정에 쓴다. */
    private enum Outcome { APPLIED, SKIPPED, PG_FAILED, ERROR }

    /** 승인 대사. 대상: {@code PAYMENT_UNKNOWN}, {@code PAYING} (상태가 된 지 기준 시간 경과) */
    public ReconciliationRoundResult reconcilePayments() {
        return runRound(PAYMENT_JOB, stateService::findPaymentTargets, this::reconcilePayment);
    }

    /** 취소 대사. 대상: {@code CANCEL_UNKNOWN}, {@code CANCELING} (상태가 된 지 기준 시간 경과) */
    public ReconciliationRoundResult reconcileCancels() {
        return runRound(CANCEL_JOB, stateService::findCancelTargets, this::reconcileCancel);
    }

    // ---- 승인 대사: 건 하나 ----

    /**
     * 분류는 intent 8절 "승인 대사 분류"를 따른다.
     * <pre>
     * DONE + 금액 = 서버 금액              → PAID (재고 그대로)
     * DONE + 금액 ≠ 서버 금액              → 다시 취소 → 성공하면 FAILED(PAYMENT_AMOUNT_MISMATCH) + 재고 복구
     * CANCELED                             → FAILED + 재고 복구 (앞선 보상 취소가 실제로는 성공했거나, 돈이 돌아감)
     * ABORTED, EXPIRED, READY, IN_PROGRESS → FAILED + 재고 복구 (승인 요청을 다시 보내지 않으므로 승인될 길이 없다)
     * NOT_FOUND_PAYMENT                    → FAILED + 재고 복구 + log.error (결제 쪽 장애)
     * 조회 실패                            → 바꾸지 않음, 다음 회차
     * 조회 거절 (일반 404, 400 등)           → 바꾸지 않음 + log.error, 연속 실패에 세지 않음
     * 그 밖의 상태 (가상계좌, 부분 취소)     → 바꾸지 않음 + log.error (이 서비스에서 생길 수 없음)
     * </pre>
     */
    private Outcome reconcilePayment(PaymentReconcileTarget target) {
        Optional<PgPaymentLookup> found;
        try {
            found = paymentClient.lookup(target.paymentKey());
        } catch (PaymentLookupRejectedException e) {
            return lookupRejected(PAYMENT_JOB, target.orderId(), target.paymentKey(), e);
        } catch (RuntimeException e) {
            log.warn("[{}] PG 조회 실패. 다음 회차에 다시 조회한다. orderId={}, cause={}",
                    PAYMENT_JOB, target.orderId(), e.getClass().getSimpleName());
            return Outcome.PG_FAILED;
        }

        if (found.isEmpty()) {
            log.error("[{}] PG에 결제가 없다(NOT_FOUND_PAYMENT). 결제 쪽 장애로 보고 실패로 확정한다. orderId={}, paymentKey={}",
                    PAYMENT_JOB, target.orderId(), target.paymentKey());
            return apply(() -> stateService.confirmFailed(target.orderId(), ErrorCode.PAYMENT_NOT_APPROVED, null), target.orderId());
        }

        PgPaymentLookup lookup = found.get();
        return switch (lookup.status()) {
            case DONE -> lookup.totalAmount() == target.amount()
                    ? apply(() -> stateService.confirmPaid(target.orderId(), lookup.totalAmount()), target.orderId())
                    : cancelMismatchedApproval(target, lookup.totalAmount());
            case CANCELED -> apply(() -> stateService.confirmFailed(
                    target.orderId(), canceledFailureCode(target), null), target.orderId());
            case ABORTED, EXPIRED, READY, IN_PROGRESS -> apply(() -> stateService.confirmFailed(
                    target.orderId(), ErrorCode.PAYMENT_NOT_APPROVED, null), target.orderId());
            case WAITING_FOR_DEPOSIT, PARTIAL_CANCELED -> {
                log.error("[{}] 예상하지 못한 PG 결제 상태다. 바꾸지 않는다. orderId={}, status={}",
                        PAYMENT_JOB, target.orderId(), lookup.status());
                yield Outcome.SKIPPED;
            }
        };
    }

    /** 받기로 한 금액이 아니므로 돌려준다. 재취소가 실패하면 결과 모름(PAYMENT_CANCEL_FAILED)으로 두고 다음 회차에 다시 본다. */
    private Outcome cancelMismatchedApproval(PaymentReconcileTarget target, long pgAmount) {
        log.error("[{}] PG 승인 금액이 서버 금액과 다르다. 다시 취소한다. orderId={}, 서버 금액={}, PG 금액={}",
                PAYMENT_JOB, target.orderId(), target.amount(), pgAmount);
        try {
            paymentClient.cancel(target.paymentKey(), "승인 금액 불일치");
        } catch (RuntimeException e) {
            log.error("[{}] 재취소 실패. 돈이 묶여 있을 수 있어 결과 모름으로 둔다. orderId={}, cause={}",
                    PAYMENT_JOB, target.orderId(), e.getClass().getSimpleName());
            apply(() -> stateService.keepUnknownAfterCancelFailure(target.orderId(), pgAmount), target.orderId());
            return Outcome.PG_FAILED;
        }
        return apply(() -> stateService.confirmFailed(target.orderId(), ErrorCode.PAYMENT_AMOUNT_MISMATCH, pgAmount),
                target.orderId());
    }

    /**
     * PG에서 취소된 건의 실패 사유. 보상 취소 실패 건은 원래 보상하려던 이유(금액 불일치 / 반영 실패)를 승인 금액으로 되살린다.
     * 그 밖의 건은 승인되지 않은 것으로 본다.
     */
    private ErrorCode canceledFailureCode(PaymentReconcileTarget target) {
        if (!target.compensationFailed()) {
            return ErrorCode.PAYMENT_NOT_APPROVED;
        }
        if (target.approvedAmount() != null && target.approvedAmount() != target.amount()) {
            return ErrorCode.PAYMENT_AMOUNT_MISMATCH;
        }
        return ErrorCode.INTERNAL_SERVER_ERROR;
    }

    // ---- 취소 대사: 건 하나 ----

    /**
     * <pre>
     * CANCELED           → 취소 확정: CANCELED + 재고 복구
     * DONE               → 취소되지 않음: PAID로 돌아감 (취소 시도는 FAILED로 남음)
     * NOT_FOUND_PAYMENT  → CANCEL_REVIEW + log.error (이미 결제된 건이 없다는 것은 시스템 장애. 대사 대상에서 빼고 사람이 확인한다)
     * 조회 실패           → 바꾸지 않음, 다음 회차
     * 그 밖의 상태         → 바꾸지 않음 + log.error
     * </pre>
     */
    private Outcome reconcileCancel(CancelReconcileTarget target) {
        Optional<PgPaymentLookup> found;
        try {
            found = paymentClient.lookup(target.paymentKey());
        } catch (PaymentLookupRejectedException e) {
            return lookupRejected(CANCEL_JOB, target.orderId(), target.paymentKey(), e);
        } catch (RuntimeException e) {
            log.warn("[{}] PG 조회 실패. 다음 회차에 다시 조회한다. orderId={}, cause={}",
                    CANCEL_JOB, target.orderId(), e.getClass().getSimpleName());
            return Outcome.PG_FAILED;
        }

        if (found.isEmpty()) {
            log.error("[{}] 이미 결제된 건이 PG에 없다(NOT_FOUND_PAYMENT). 시스템 장애로 보고 CANCEL_REVIEW로 빼 둔다. 확인이 필요하다. orderId={}, paymentKey={}",
                    CANCEL_JOB, target.orderId(), target.paymentKey());
            return apply(() -> stateService.markCancelReview(target), target.orderId());
        }

        PgPaymentLookup lookup = found.get();
        return switch (lookup.status()) {
            case CANCELED -> apply(() -> stateService.confirmCanceled(target), target.orderId());
            case DONE -> apply(() -> stateService.confirmCancelNotApplied(target), target.orderId());
            default -> {
                log.error("[{}] 예상하지 못한 PG 결제 상태다. 바꾸지 않는다. orderId={}, status={}",
                        CANCEL_JOB, target.orderId(), lookup.status());
                yield Outcome.SKIPPED;
            }
        };
    }

    /**
     * 조회가 우리 요청을 거절했다 (일반 404, 400 등). 정상 흐름에서는 생기지 않으므로 사람이 본다.
     * 다시 보내도 같은 응답이라 일시 실패로 세지 않는다. 세면 이런 건 몇 개가 매 회차 회차를 멈춘다. (intent 8절 대사 구현 세부 ⑤)
     */
    private Outcome lookupRejected(String job, Long orderId, String paymentKey, PaymentLookupRejectedException e) {
        log.error("[{}] PG 조회가 요청을 거절했다. 바꾸지 않고 건너뛴다. 확인이 필요하다. orderId={}, paymentKey={}, cause={}",
                job, orderId, paymentKey, e.getMessage());
        return Outcome.SKIPPED;
    }

    // ---- 회차 ----

    @FunctionalInterface
    private interface BatchLoader<T> {
        ReconcileBatch<T> load(LocalDateTime before, long afterId, int size);
    }

    /** DB 반영 예외는 그 건만 실패로 세고 회차는 계속한다. (한 건의 문제로 나머지 대상이 밀리지 않게) */
    private Outcome apply(BooleanSupplier transition, Long orderId) {
        try {
            return transition.getAsBoolean() ? Outcome.APPLIED : Outcome.SKIPPED;
        } catch (RuntimeException e) {
            log.error("대사 결과 반영 실패. 다음 회차에 다시 본다. orderId={}", orderId, e);
            return Outcome.ERROR;
        }
    }

    private <T> ReconciliationRoundResult runRound(String job, BatchLoader<T> loader, Function<T, Outcome> handler) {
        long startedAt = System.nanoTime();
        long deadline = startedAt + properties.maxRoundDuration().toNanos();
        // 기준 시각은 회차 시작에 한 번 정한다. 회차 도중 기준을 넘긴 건은 다음 회차에 본다.
        LocalDateTime before = LocalDateTime.now().minus(properties.threshold());
        Pacer pacer = new Pacer(properties.pgCallsPerSecond());

        int targets = 0;
        int applied = 0;
        int skipped = 0;
        int pgFailures = 0;
        int errors = 0;
        int consecutivePgFailures = 0;
        boolean hasMore = false;
        String stopReason = null;
        long afterId = 0L;

        round:
        while (true) {
            ReconcileBatch<T> batch = loader.load(before, afterId, properties.batchSize());
            if (batch.lastId() == null) {
                break;
            }
            afterId = batch.lastId();

            for (T target : batch.targets()) {
                if (System.nanoTime() > deadline) {
                    hasMore = true;
                    stopReason = "회차 시간 상한";
                    break round;
                }
                if (!pacer.await()) {
                    hasMore = true;
                    stopReason = "인터럽트";
                    break round;
                }
                targets++;
                Outcome outcome = handler.apply(target);
                switch (outcome) {
                    case APPLIED -> applied++;
                    case SKIPPED -> skipped++;
                    case ERROR -> errors++;
                    case PG_FAILED -> pgFailures++;
                }
                consecutivePgFailures = outcome == Outcome.PG_FAILED ? consecutivePgFailures + 1 : 0;
                if (consecutivePgFailures >= properties.maxConsecutivePgFailures()) {
                    hasMore = true;
                    stopReason = "PG 연속 실패 " + consecutivePgFailures + "번";
                    break round;
                }
            }
            if (batch.selectedCount() < properties.batchSize()) {
                break;
            }
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        ReconciliationRoundResult result =
                new ReconciliationRoundResult(job, targets, applied, skipped, pgFailures, errors, hasMore, elapsedMs);
        if (stopReason != null) {
            log.warn("[{}] 회차를 일찍 멈춘다. 남은 대상은 다음 회차에 본다. 사유={}", job, stopReason);
        }
        log.info("[{}] 회차 끝. 대상={}, 확정={}, 건너뜀={}, PG 실패={}, 반영 오류={}, 남은 대상 있음={}, 소요={}ms",
                job, targets, applied, skipped, pgFailures, errors, hasMore, elapsedMs);
        return result;
    }

    /** 회차 안에서 PG 호출 간격을 일정하게 벌린다. 회차마다 새로 만들므로 스레드 사이에 공유하지 않는다. */
    private static final class Pacer {

        private final long intervalNanos;
        private long nextAllowedAt = 0L;

        private Pacer(int callsPerSecond) {
            this.intervalNanos = 1_000_000_000L / callsPerSecond;
        }

        /** @return 인터럽트되면 false */
        private boolean await() {
            long now = System.nanoTime();
            if (nextAllowedAt > now) {
                try {
                    Thread.sleep(Duration.ofNanos(nextAllowedAt - now));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            nextAllowedAt = Math.max(now, nextAllowedAt) + intervalNanos;
            return true;
        }
    }
}
