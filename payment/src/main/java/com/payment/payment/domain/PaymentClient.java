package com.payment.payment.domain;

import java.util.Optional;

/**
 * 외부 PG와의 통신 추상화. 실제 PG(토스페이먼츠, 포트원 등)가 정해지면 구현체만 추가한다.
 * <p>
 * 이 인터페이스의 메서드는 네트워크 I/O라 수백 ms~수 초가 걸릴 수 있다.
 * 따라서 {@code @Transactional} 안에서 호출하지 않는다. (ARCHITECTURE 5-2: 선 외부 I/O → 후 DB 트랜잭션)
 */
public interface PaymentClient {

    /**
     * 결제를 승인한다. PG 멱등키는 {@link PgApproveRequest#idempotencyKey()}를 쓴다.
     *
     * @throws PaymentDeclinedException PG가 명확히 거절함. 돈은 빠져나가지 않았다.
     * @throws PaymentTimeoutException  응답을 받지 못함. PG에서 승인됐는지 <b>알 수 없다</b>. (실패와 구분해야 한다)
     */
    PgApproveResult approve(PgApproveRequest request);

    /**
     * 승인된 결제를 전액 취소한다. (보상 작업, 결제 취소)
     * PG가 "이미 취소된 결제"라고 답하면 성공으로 돌려준다. 부분 취소가 없으므로 전액 환불이 끝난 것이다.
     *
     * @param idempotencyKey PG에 보내는 멱등키. 취소 시도마다 달라야 한다. 같은 키로 다시 보내면 PG가 첫 응답(거절 포함)을
     *                       그대로 돌려주므로, 응답을 놓친 같은 시도를 다시 물을 때만 같은 키를 쓴다. (phase-04 4-5)
     * @throws PaymentCancelDeclinedException PG가 취소를 명확히 거절함. 결제는 그대로 살아 있다.
     * @throws PaymentGatewayException        요청이 PG에 닿지 않았음이 확실함(연결 실패, 키 오류). 결제는 그대로 살아 있다.
     * @throws PaymentTimeoutException        응답을 받지 못함. PG에서 취소됐는지 <b>알 수 없다</b>.
     */
    PgCancelResult cancel(String paymentKey, String reason, String idempotencyKey);

    /**
     * 결제를 조회한다. 대사 전용이며 PG에 쓰기를 하지 않는다. (phase-04 4-4)
     *
     * @return PG가 그 결제를 모르면({@code NOT_FOUND_PAYMENT}) 빈 값
     * @throws PaymentTimeoutException 조회 응답을 받지 못함(타임아웃, 5xx). 상태를 바꾸지 않고 다음 회차에 다시 조회한다.
     * @throws PaymentGatewayException 요청이 PG에 닿지 않음(연결 실패, 키 오류). 마찬가지로 다음 회차에 다시 조회한다.
     */
    Optional<PgPaymentLookup> lookup(String paymentKey);
}
