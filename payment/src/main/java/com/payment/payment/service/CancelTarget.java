package com.payment.payment.service;

/**
 * 취소 선점에 성공한 요청의 PG 취소 호출과 결과 반영에 필요한 값.
 *
 * @param cancelId   이번 취소 시도 기록({@code payment_cancel})의 ID
 * @param paymentKey PG가 결제를 식별하는 값
 */
public record CancelTarget(Long cancelId, Long orderId, String paymentKey) {
}
