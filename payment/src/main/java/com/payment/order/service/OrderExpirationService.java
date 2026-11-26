package com.payment.order.service;

import java.time.LocalDateTime;
import java.util.List;

import com.payment.order.dto.ExpirationRoundResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제를 시작하지 않은 채 버려진 주문을 만료한다. PG를 부르지 않고 DB만 바꾼다. (phase-04 4-4)
 * <p>
 * 이 클래스에는 {@code @Transactional}이 없다. 대상 id를 락 없이 고른 뒤 주문마다 짧은 트랜잭션
 * ({@link OrderService#expireIfPending})으로 {@code FOR UPDATE} → PENDING 재확인 → {@code Order.expire()}를 한다.
 * 주문마다 끊는 이유는 묶음 전체를 한 트랜잭션으로 잠그면 그동안 그 주문들의 결제 시작이 모두 기다리기 때문이다.
 * <p>
 * 범위 조건 UPDATE를 쓰지 않는 이유는 intent 8절 "대사·만료의 상태 전이 방식"에 있다. (갭 락, 상태 규칙 중복)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpirationService {

    private final OrderService orderService;
    private final OrderExpirationProperties properties;

    public ExpirationRoundResult expire() {
        long startedAt = System.nanoTime();
        long deadline = startedAt + properties.maxRoundDuration().toNanos();
        LocalDateTime before = LocalDateTime.now().minus(properties.threshold());

        int targets = 0;
        int expired = 0;
        int skipped = 0;
        int errors = 0;
        boolean hasMore = false;
        long afterId = 0L;

        round:
        while (true) {
            List<Long> ids = orderService.findExpirationTargetIds(before, afterId, properties.batchSize());
            if (ids.isEmpty()) {
                break;
            }
            afterId = ids.getLast();

            for (Long id : ids) {
                if (System.nanoTime() > deadline) {
                    hasMore = true;
                    break round;
                }
                targets++;
                try {
                    if (orderService.expireIfPending(id)) {
                        expired++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    // 락 대기 초과 등. 다음 회차에 다시 고른다.
                    log.error("[만료] 반영 실패. 다음 회차에 다시 본다. orderId={}", id, e);
                    errors++;
                }
            }
            if (ids.size() < properties.batchSize()) {
                break;
            }
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[만료] 회차 끝. 대상={}, 만료={}, 건너뜀={}, 오류={}, 남은 대상 있음={}, 소요={}ms",
                targets, expired, skipped, errors, hasMore, elapsedMs);
        return new ExpirationRoundResult(targets, expired, skipped, errors, hasMore, elapsedMs);
    }
}
