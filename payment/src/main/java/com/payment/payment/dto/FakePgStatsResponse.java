package com.payment.payment.dto;

/** 가짜 PG가 서버 기동(또는 마지막 초기화) 이후 받은 호출 횟수. 부하 테스트에서 "PG가 실제로 몇 번 불렸나"를 확인하는 용도. */
public record FakePgStatsResponse(
        int approveCalls,
        int cancelCalls
) {
}
