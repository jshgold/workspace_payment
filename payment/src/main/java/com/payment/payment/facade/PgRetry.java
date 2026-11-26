package com.payment.payment.facade;

import java.time.Duration;
import java.util.function.Supplier;

import com.payment.payment.domain.PaymentTimeoutException;

import lombok.extern.slf4j.Slf4j;

/**
 * PG 호출이 결과 모름({@link PaymentTimeoutException}: 읽기 타임아웃, 5xx, 처리 중 응답)이면 잠깐 기다린 뒤 <b>같은 PG 멱등키로 1번</b>
 * 다시 묻는다. (phase-04 4-5, intent 8절 "PG 호출 재시도")
 * <ul>
 *   <li>같은 키라서 PG는 다시 처리하지 않고 첫 응답을 돌려준다. 응답만 유실된 경우 그 자리에서 결과가 확정된다.</li>
 *   <li>거절과 연결 실패·키 오류는 결과가 확정된 것이라 다시 묻지 않는다.</li>
 *   <li>재시도도 결과 모름이면 그 예외를 그대로 던진다. 호출자는 지금처럼 결과 모름으로 기록하고 대사에 맡긴다.</li>
 *   <li>고객이 기다리는 요청 안에서만 쓴다. 트랜잭션 밖에서 부르므로 락이나 커넥션을 쥐지 않는다.</li>
 * </ul>
 * 고객 대기는 최대 (읽기 타임아웃 × 2 + 대기 시간)이다. 긴 장애에서는 대기만 늘리므로 서킷 브레이커가 따로 필요하다.
 */
@Slf4j
final class PgRetry {

    private PgRetry() {
    }

    static <T> T onceOnUnknown(Duration delay, String action, Object id, Supplier<T> call) {
        try {
            return call.get();
        } catch (PaymentTimeoutException first) {
            log.warn("PG {} 결과 모름. {}ms 뒤 같은 PG 멱등키로 1번 다시 묻는다. id={}", action, delay.toMillis(), id);
            pause(delay, first);
            return call.get();
        }
    }

    private static void pause(Duration delay, PaymentTimeoutException first) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw first;
        }
    }
}
