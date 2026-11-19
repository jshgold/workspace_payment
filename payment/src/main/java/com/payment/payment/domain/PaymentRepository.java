package com.payment.payment.domain;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    /** 같은 키의 재전송인지 확인한다. 락 없이 읽는 빠른 경로다. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * 공유 락({@code FOR SHARE})으로 주문의 결제 시도를 읽는다. 잠금 읽기라 트랜잭션 스냅샷이 아니라 최신 커밋 값을 본다.
     * <p>
     * 결제 시작에서 키로 먼저 조회(스냅샷 생성)한 뒤 주문 락을 기다리는 사이에 다른 요청이 같은 키로 시도를 커밋했을 수 있다.
     * 일반 SELECT는 그 행을 보지 못하므로, 주문이 PENDING이 아닐 때 이 메서드로 다시 확인한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForShare(@Param("orderId") Long orderId);
}
