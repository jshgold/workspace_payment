package com.payment.payment.service;

import com.payment.common.exception.ErrorCode;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;

/**
 * 결제 승인 흐름에서 DB를 건드리는 짧은 트랜잭션들. PG 호출은 여기에 넣지 않고
 * 트랜잭션이 없는 {@code PaymentFacade}가 메서드 사이에서 수행한다. (ARCHITECTURE 5-2)
 * <p>
 * 요청 1건이 여는 트랜잭션은 {@link #startPaying} → (PG) → 결과 반영 1개, 모두 2번이다. (트러블슈팅 개선 B)
 * 구현체마다 상태 선점 방식(비관적 락 / 조건부 UPDATE)이 다르고, 같은 시나리오 테스트로 비교한다.
 */
public interface PaymentStateService {

    /**
     * 결제를 시작한다. 한 트랜잭션에서 아래를 처리한다.
     * <ol>
     *   <li>같은 멱등키의 재전송이면 저장된 결과로 답한다. (승인 → {@code Replayed}, 실패 → 같은 예외, 진행 중 → 처리 중)</li>
     *   <li>주문이 PENDING이 아니면 거절한다. ({@code PAYMENT_IN_PROGRESS} / {@code ORDER_NOT_PAYABLE})</li>
     *   <li>요청 금액 불일치·재고 부족이면 주문 FAILED, Payment FAILED를 기록하고 커밋한다. ({@code Rejected}, PG 호출 없음)</li>
     *   <li>주문 PENDING → PAYING, 재고 차감, Payment REQUESTED 기록. ({@code Proceed})</li>
     * </ol>
     *
     * @throws com.payment.common.exception.BusinessException 기록 없이 거절하는 경우 (처리 중, 결제 불가 주문, 키 재사용, 재전송된 실패)
     */
    StartPayingResult startPaying(StartPayingCommand command);

    /**
     * PG 승인 결과를 반영한다. 주문 PAYING → PAID, Payment REQUESTED → APPROVED. (재고는 startPaying에서 차감됨)
     * 이미 PAID인 주문이면(중복 반영) 예외 없이 기존 결과를 돌려준다.
     */
    PaymentResponse complete(Long orderId, PgApproveResult approveResult);

    /**
     * 돈이 나가지 않았음이 확정된 실패를 기록한다. 주문 PAYING → FAILED, Payment → FAILED, 재고 복구를 한 트랜잭션에서 한다.
     *
     * @param approvedAmount PG가 승인했다가 보상 취소한 경우 그 승인 금액, 승인 응답이 없었으면 null
     */
    void fail(Long orderId, ErrorCode failureCode, Long approvedAmount);

    /**
     * 승인 여부를 모른다. 주문 PAYING → PAYMENT_UNKNOWN, Payment → UNKNOWN. 재고는 쥔 채로 둔다. (대사의 몫)
     *
     * @param reason         보상 취소 실패처럼 따로 남길 사유가 있으면 그 코드, 단순 타임아웃이면 null
     * @param approvedAmount PG 승인 응답을 받았으면 그 금액, 아니면 null
     */
    void markUnknown(Long orderId, ErrorCode reason, Long approvedAmount);
}
