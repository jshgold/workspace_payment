package com.payment.payment.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 토스페이먼츠 연동 설정. 실제 키는 코드·git에 두지 않고 환경변수({@code TOSS_SECRET_KEY})로만 주입한다.
 * 값이 비어 있으면 {@code toss} 프로필 기동 시점에 실패한다. (잘못된 설정으로 조용히 떠서 운영 중 터지는 것보다 낫다)
 */
@Validated
@ConfigurationProperties(prefix = "app.pg.toss")
public record TossPaymentProperties(
        @NotBlank String secretKey,
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    /** 로그·예외 메시지에 시크릿 키가 그대로 찍히지 않게 마스킹한다. */
    @Override
    public String toString() {
        return "TossPaymentProperties[secretKey=****, baseUrl=" + baseUrl
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
