package com.payment.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.payment.payment.domain.PaymentClient;

class PaymentClientSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FakePaymentClient.class, TossPaymentClient.class, TossPaymentConfig.class);

    @Test
    @DisplayName("provider 설정이 없으면 FakePaymentClient만 주입되고, 키가 없어도 기동된다")
    void defaultsToFake() {
        // given // when // then
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaymentClient.class);
            assertThat(context.getBean(PaymentClient.class)).isInstanceOf(FakePaymentClient.class);
        });
    }

    @Test
    @DisplayName("provider=toss 이고 키가 있으면 TossPaymentClient만 주입된다")
    void tossWhenConfigured() {
        // given
        var configured = runner.withPropertyValues(
                "app.pg.provider=toss",
                "app.pg.toss.secret-key=dummy-key-for-test",
                "app.pg.toss.base-url=http://localhost:9999",
                "app.pg.toss.connect-timeout=2s",
                "app.pg.toss.read-timeout=5s");

        // when // then
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaymentClient.class);
            assertThat(context.getBean(PaymentClient.class)).isInstanceOf(TossPaymentClient.class);
        });
    }

    @Test
    @DisplayName("provider=toss 인데 시크릿 키가 비어 있으면 기동이 실패한다")
    void tossWithoutKeyFailsFast() {
        // given
        var misconfigured = runner.withPropertyValues(
                "app.pg.provider=toss",
                "app.pg.toss.secret-key=",
                "app.pg.toss.base-url=http://localhost:9999",
                "app.pg.toss.connect-timeout=2s",
                "app.pg.toss.read-timeout=5s");

        // when // then
        misconfigured.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("프로퍼티 toString은 시크릿 키를 노출하지 않는다")
    void toStringMasksSecret() {
        // given
        var properties = new TossPaymentProperties(
                "super-secret-value", "http://localhost:9999",
                java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5));

        // when
        String printed = properties.toString();

        // then
        assertThat(printed).doesNotContain("super-secret-value").contains("****");
    }
}
