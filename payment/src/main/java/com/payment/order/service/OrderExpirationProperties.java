package com.payment.order.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 만료 설정. (phase-04 4-4, {@code intent.md} 8절) <b>운영 근거 없는 초기값이다.</b>
 *
 * @param threshold        주문 생성 후 이만큼 지난 PENDING이 대상. 토스 결제 유효 30분을 위젯 표시부터로 본다
 * @param interval         회차 사이 간격. 뒷정리라 늦어도 손해가 거의 없다
 * @param batchSize        한 번에 고르는 대상 수
 * @param maxRoundDuration 한 회차 최대 시간
 */
@Validated
@ConfigurationProperties(prefix = "app.expiration")
public record OrderExpirationProperties(
        @NotNull @DefaultValue("30m") Duration threshold,
        @NotNull @DefaultValue("5m") Duration interval,
        @Positive @DefaultValue("500") int batchSize,
        @NotNull @DefaultValue("50s") Duration maxRoundDuration) {
}
