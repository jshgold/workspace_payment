package com.payment.payment.service;

import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;

/**
 * 결제 흐름에서 DB를 건드리는 짧은 트랜잭션들의 계약.
 * <p>
 * PG 호출은 여기에 넣지 않는다. 각 메서드는 자체 {@code @Transactional}로 짧게 끝나고,
 * 외부 I/O는 트랜잭션이 없는 {@code PaymentFacade}가 메서드 사이에서 수행한다. (ARCHITECTURE 5-2)
 * <p>
 * 동시성 제어 방식(비관적 락, 원자적 쿼리 등)만 구현체마다 다르고, Facade와 테스트는 공유한다.
 */
public interface PaymentStateService {

    /**
     * PENDING → PAYING으로 주문을 선점한다. 선점에 성공한 요청만 PG를 호출할 수 있다.
     *
     * @throws IllegalStateException 이미 결제가 진행 중이거나 끝난 주문
     */
    PaymentTarget startPaying(Long orderId);

    /**
     * PG 승인 결과를 반영한다. 한 트랜잭션에서 Order PAID, Payment 저장, 재고 차감을 함께 처리한다.
     * 이미 PAID인 주문이면(중복 메시지) 예외 없이 기존 결제 결과를 돌려준다.
     */
    PaymentResponse complete(Long orderId, PgApproveResult approveResult);

    /** PAYING → PENDING. 돈이 나가지 않았거나 이미 환불된 것이 확실할 때만 호출한다. */
    void revertToPending(Long orderId);
}
