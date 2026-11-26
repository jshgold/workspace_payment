package com.payment.payment.service;

/**
 * 승인 대사 대상 하나. 트랜잭션 밖에서 PG를 조회하는 데 필요한 값만 담는다.
 *
 * @param amount             서버가 주문에서 계산한 금액. PG 금액과 비교한다
 * @param compensationFailed 보상 취소가 실패해 결과 모름이 된 건인지
 * @param approvedAmount     PG 승인 응답을 받았던 금액 (보상 취소 실패 건만)
 */
public record PaymentReconcileTarget(Long orderId, String paymentKey, long amount,
                                     boolean compensationFailed, Long approvedAmount) {
}
