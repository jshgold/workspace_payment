package com.payment.payment.domain;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancel, Long> {

    /** 같은 키의 재전송인지 확인한다. 락 없이 읽는 빠른 경로다. */
    Optional<PaymentCancel> findByIdempotencyKey(String idempotencyKey);

    /**
     * 공유 락({@code FOR SHARE})으로 읽는다. 잠금 읽기라 트랜잭션 스냅샷이 아니라 최신 커밋 값을 본다.
     * <p>
     * 키로 먼저 조회(스냅샷 생성)한 뒤 주문 락을 기다리는 사이에 같은 키의 시도가 커밋됐을 수 있다.
     * 주문이 PAID가 아닐 때만 이 메서드로 다시 확인한다. 이 트랜잭션은 뒤이어 INSERT를 하지 않으므로,
     * 키가 없을 때 잡히는 갭 락이 다른 요청의 INSERT와 엇갈려 데드락을 만들지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from PaymentCancel c where c.idempotencyKey = :idempotencyKey")
    Optional<PaymentCancel> findByIdempotencyKeyForShare(@Param("idempotencyKey") String idempotencyKey);

    List<PaymentCancel> findAllByOrderIdOrderByIdAsc(Long orderId);
}
