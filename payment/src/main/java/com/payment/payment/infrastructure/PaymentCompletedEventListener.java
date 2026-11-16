package com.payment.payment.infrastructure;

import com.payment.payment.domain.PaymentCompletedEvent;
import com.payment.payment.domain.PaymentNotifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 완료 후처리. (ARCHITECTURE 5-1)
 * <ul>
 *   <li>{@code AFTER_COMMIT}: 결제 트랜잭션이 롤백되면 이 리스너는 실행되지 않는다. 취소된 결제를 알리지 않기 위해서다.</li>
 *   <li>{@code @Async}: 리스너를 별도 스레드에서 실행한다. AFTER_COMMIT 시점에는 발행 스레드에 기존 트랜잭션 리소스가
 *       아직 묶여 있어, 여기서 DB에 쓰면 커밋되지 않고 조용히 유실된다. 후처리에서 DB 쓰기가 필요해져도 안전하고,
 *       후처리가 느려도 결제 응답을 붙잡지 않는다.</li>
 * </ul>
 * 후처리 실패는 이미 커밋된 결제에 영향을 주지 않는다. 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedEventListener {

    private final PaymentNotifier paymentNotifier;

    @Async("paymentEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PaymentCompletedEvent event) {
        try {
            paymentNotifier.notifyPaid(event);
        } catch (RuntimeException e) {
            log.error("결제 완료 후처리 실패. 결제는 이미 커밋됐다. orderId={}", event.orderId(), e);
        }
    }
}
