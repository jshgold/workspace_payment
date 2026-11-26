package com.payment.order.dto;

/**
 * 만료 한 회차의 결과. 회차 로그와 수동 호출 API 응답에 쓴다.
 *
 * @param targets   고른 대상 수
 * @param expired   만료한 건수
 * @param skipped   그사이 결제가 시작돼 만료하지 않은 건수
 * @param errors    반영 중 예외가 난 건수
 * @param hasMore   시간 상한으로 일찍 멈춰 남은 대상이 있을 수 있는지
 * @param elapsedMs 소요 시간
 */
public record ExpirationRoundResult(int targets, int expired, int skipped, int errors, boolean hasMore, long elapsedMs) {
}
