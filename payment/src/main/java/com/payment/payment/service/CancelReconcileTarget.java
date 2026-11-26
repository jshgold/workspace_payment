package com.payment.payment.service;

/**
 * 취소 대사 대상 하나.
 *
 * @param cancelId 확정할 취소 시도. 락을 잡은 뒤 이 시도가 아직 미확정인지 다시 본다.
 *                 (고른 뒤 다른 대사가 확정하고 새 취소가 시작됐다면 그 새 시도를 이 조회 결과로 확정하면 안 되기 때문)
 */
public record CancelReconcileTarget(Long orderId, Long cancelId, String paymentKey) {
}
