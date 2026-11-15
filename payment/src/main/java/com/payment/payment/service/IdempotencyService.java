package com.payment.payment.service;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.payment.domain.IdempotencyKey;
import com.payment.payment.domain.IdempotencyKeyRepository;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.dto.PaymentResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인 요청의 멱등키를 DB 한 곳에서 관리한다. 각 메서드는 짧은 트랜잭션이며 PG 호출은 포함하지 않는다.
 * <p>
 * {@link #begin}은 <b>주문 행을 {@code FOR UPDATE}로 잠근 채</b> 키를 조회하고 없으면 INSERT한다.
 * 같은 주문에 대한 요청이 이 구간에 한 줄로 서므로, 먼저 온 요청이 커밋한 키를 뒤 요청이 반드시 보게 된다.
 * (키 행은 아직 없어서 잠글 수 없으므로, 이미 존재하는 주문 행을 출입문으로 쓴다. S락은 서로 막지 못해 쓸 수 없다.)
 * 락은 이 트랜잭션이 커밋될 때 풀리며, 이후 PG 호출 구간에는 쥐고 있지 않다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    /**
     * 키를 선점하거나, 이미 있는 키라면 상태에 따라 응답한다.
     *
     * @return 비어 있으면 이 요청이 처음이라 PG 승인 흐름을 진행해야 한다. 값이 있으면 첫 성공 결과의 재전송이다.
     * @throws BusinessException PAYMENT_IN_PROGRESS(처리 중), IDEMPOTENCY_KEY_REUSED(다른 주문의 키),
     *                           첫 요청이 확정 실패였다면 그 실패, PAYMENT_REQUEST_AMOUNT_MISMATCH(요청 금액 불일치)
     */
    @Transactional
    public Optional<PaymentResponse> begin(String idempotencyKey, Long orderId, long requestedAmount) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return Optional.of(replay(existing.get(), order));
        }

        // 클라이언트가 보낸 금액은 믿지 않고 서버가 계산해 둔 금액과 비교한다. 다르면 키도 남기지 않고 PG도 부르지 않는다.
        if (order.getTotalPrice() != requestedAmount) {
            throw new BusinessException(ErrorCode.PAYMENT_REQUEST_AMOUNT_MISMATCH);
        }
        idempotencyKeyRepository.save(IdempotencyKey.create(idempotencyKey, orderId));
        return Optional.empty();
    }

    /** 결제가 완료됐음을 기록한다. */
    @Transactional
    public void succeed(String idempotencyKey) {
        find(idempotencyKey).succeed();
    }

    /** 돈이 나가지 않은 것이 확정된 실패를 기록한다. 같은 키의 재요청은 같은 실패를 받는다. */
    @Transactional
    public void fail(String idempotencyKey, ErrorCode errorCode) {
        find(idempotencyKey).fail(errorCode.name());
    }

    /** 이미 있는 키의 재요청을 처리한다. 첫 성공 결과를 돌려주거나, 상태에 맞는 예외를 던진다. */
    private PaymentResponse replay(IdempotencyKey key, Order order) {
        if (!key.isOwnedBy(order.getId())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return switch (key.getStatus()) {
            case IN_PROGRESS -> throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
            case FAILED -> throw new BusinessException(ErrorCode.valueOf(key.getFailureCode()));
            case SUCCEEDED -> {
                Payment payment = paymentRepository.findByOrderId(order.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "성공으로 기록된 멱등키에 결제 기록이 없습니다. orderId=" + order.getId()));
                yield PaymentResponse.of(order, payment);
            }
        };
    }

    private IdempotencyKey find(String idempotencyKey) {
        return idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("선점되지 않은 멱등키입니다. key=" + idempotencyKey));
    }
}
