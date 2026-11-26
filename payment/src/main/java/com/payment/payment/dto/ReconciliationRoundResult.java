package com.payment.payment.dto;

/**
 * 대사 한 회차의 결과. 회차 로그와 수동 호출 API 응답에 쓴다. 대사 기준·주기 같은 초기값을 조정할 운영 근거가 된다.
 *
 * @param job          승인 대사 / 취소 대사
 * @param targets      이번 회차에 처리를 시도한 대상 수
 * @param applied      상태를 확정한 건수
 * @param skipped      확정하지 않은 건수 (다른 경로가 먼저 확정함, 조회 결과가 예상 밖, 취소 대사의 NOT_FOUND_PAYMENT)
 * @param pgFailures   PG 호출(조회, 재취소)이 실패한 건수. 다음 회차에 다시 본다
 * @param errors       DB 반영 중 예외가 난 건수
 * @param hasMore      시간 상한이나 PG 연속 실패로 회차를 일찍 멈춰 남은 대상이 있을 수 있는지
 * @param elapsedMs    소요 시간
 */
public record ReconciliationRoundResult(String job, int targets, int applied, int skipped, int pgFailures,
                                        int errors, boolean hasMore, long elapsedMs) {
}
