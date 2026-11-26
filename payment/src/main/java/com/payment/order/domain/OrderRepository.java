package com.payment.order.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 비관적 락(SELECT ... FOR UPDATE)으로 주문을 조회한다. 같은 주문에 대한 상태 전이를 직렬화한다.
     * 락 대기는 3초이며 초과하면 락 예외가 발생한다. 반드시 @Transactional 안에서 호출해야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /**
     * 대사·만료 대상 id를 락 없이 고른다. (4-4) 인덱스 {@code (status, updated_at)}를 쓴다. (V11)
     * <p>
     * {@code id > afterId}로 앞 묶음 다음부터 읽는다. 처리하지 못하고 남는 건(조회 실패 등)이 있어도
     * 같은 회차 안에서 같은 건을 다시 고르지 않게 하기 위해서다.
     */
    @Query("""
            select o.id from Order o
             where o.status in :statuses
               and o.updatedAt < :before
               and o.id > :afterId
             order by o.id
            """)
    List<Long> findIdsForBatch(@Param("statuses") Collection<OrderStatus> statuses,
                               @Param("before") LocalDateTime before,
                               @Param("afterId") long afterId,
                               Limit limit);

    /**
     * [원자적 쿼리 전략] 주문 상태를 {@code expected}일 때만 {@code next}로 바꾼다. (상태 전이가 곧 관문)
     * <ul>
     *   <li>조회 없이 UPDATE 한 문장으로 확인과 변경을 처리하므로, 동시에 와도 한 요청만 1행을 얻는다.</li>
     *   <li>Order.startPaying 등의 상태 전이 규칙이 SQL로 한 번 더 표현된 것이다. (규칙이 두 곳에 있음)</li>
     *   <li>벌크 연산은 @UpdateTimestamp와 영속성 컨텍스트를 거치지 않아 updatedAt을 직접 갱신하고 1차 캐시를 비운다.</li>
     * </ul>
     *
     * @return 영향받은 행 수. 1이면 전이 성공, 0이면 주문이 없거나 기대한 상태가 아님
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o
               set o.status = :next,
                   o.updatedAt = current_timestamp
             where o.id = :id
               and o.status = :expected
            """)
    int transitionStatus(@Param("id") Long id,
                         @Param("expected") OrderStatus expected,
                         @Param("next") OrderStatus next);
}
