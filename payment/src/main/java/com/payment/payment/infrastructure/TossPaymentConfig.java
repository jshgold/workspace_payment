package com.payment.payment.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@code toss} 일 때만 프로퍼티 바인딩·검증을 켠다. (fake 모드에서는 키가 없어도 기동되어야 한다) */
@Configuration
@ConditionalOnProperty(name = "app.pg.provider", havingValue = "toss")
@EnableConfigurationProperties(TossPaymentProperties.class)
public class TossPaymentConfig {
}
