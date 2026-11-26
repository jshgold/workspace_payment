package com.payment.payment.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 결제·취소 대사 설정. (phase-04 4-4, {@code intent.md} 8절)
 * <b>모든 값은 운영 근거 없는 초기값이다.</b> 회차 로그를 보고 조정한다.
 *
 * @param threshold                    상태가 된 지({@code updated_at}) 이만큼 지난 건만 대상. 짧게 잡았다가 틀리면 늦게 처리된 승인을 FAILED로 확정한다
 * @param interval                     회차 사이 간격 (앞 회차가 끝난 뒤부터)
 * @param batchSize                    한 번에 고르는 대상 수. 대상이 없을 때까지 묶음을 반복한다
 * @param maxRoundDuration             한 회차 최대 시간. 넘으면 남은 대상은 다음 회차로 넘긴다
 * @param maxConsecutivePgFailures     PG 호출이 이만큼 연속 실패하면 그 회차를 멈춘다 (PG 장애 중 헛호출 방지)
 * @param pgCallsPerSecond             대사가 PG를 부르는 초당 상한 (토스 한도 미확인)
 */
@Validated
@ConfigurationProperties(prefix = "app.reconciliation")
public record ReconciliationProperties(
        @NotNull @DefaultValue("5m") Duration threshold,
        @NotNull @DefaultValue("1m") Duration interval,
        @Positive @DefaultValue("500") int batchSize,
        @NotNull @DefaultValue("50s") Duration maxRoundDuration,
        @Positive @DefaultValue("3") int maxConsecutivePgFailures,
        @Positive @DefaultValue("5") int pgCallsPerSecond) {
}
