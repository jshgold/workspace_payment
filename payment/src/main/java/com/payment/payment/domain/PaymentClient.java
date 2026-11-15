package com.payment.payment.domain;

/**
 * 외부 PG와의 통신 추상화. 실제 PG(토스페이먼츠, 포트원 등)가 정해지면 구현체만 추가한다.
 * <p>
 * 이 인터페이스의 메서드는 네트워크 I/O라 수백 ms~수 초가 걸릴 수 있다.
 * 따라서 {@code @Transactional} 안에서 호출하지 않는다. (ARCHITECTURE 5-2: 선 외부 I/O → 후 DB 트랜잭션)
 */
public interface PaymentClient {

    /**
     * 결제를 승인한다.
     *
     * @throws PaymentDeclinedException PG가 명확히 거절함. 돈은 빠져나가지 않았다.
     * @throws PaymentTimeoutException  응답을 받지 못함. PG에서 승인됐는지 <b>알 수 없다</b>. (실패와 구분해야 한다)
     */
    PgApproveResult approve(PgApproveRequest request);

    /**
     * 승인된 결제를 취소한다. (보상 작업, 결제 취소)
     *
     * @throws PaymentCancelFailedException 취소에 실패함. 돈이 묶여 있을 수 있어 재시도나 대사가 필요하다.
     */
    void cancel(String pgTransactionId, String reason);
}
