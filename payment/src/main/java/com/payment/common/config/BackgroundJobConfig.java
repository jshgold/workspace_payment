package com.payment.common.config;

import com.payment.order.service.OrderExpirationProperties;
import com.payment.order.service.OrderExpirationService;
import com.payment.payment.facade.PaymentReconciliationFacade;
import com.payment.payment.service.ReconciliationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 대사·만료 설정값 등록. 설정값은 스케줄러가 꺼져 있어도 수동 호출 API가 쓰므로 항상 등록한다.
 */
@Configuration
@EnableConfigurationProperties({ReconciliationProperties.class, OrderExpirationProperties.class})
public class BackgroundJobConfig {

    /**
     * 대사·만료 스케줄러. {@code app.scheduling.enabled=true}일 때만 켠다. (통합 테스트는 끈다. 테스트 데이터를 백그라운드에서 바꾸면 안 되므로)
     * <ul>
     *   <li>주기는 앞 회차가 끝난 뒤부터 센다(fixed delay). 회차가 길어져도 같은 작업이 겹쳐 돌지 않는다.</li>
     *   <li>스레드는 2개({@code spring.task.scheduling.pool.size})라 대사와 만료가 서로 기다리지 않는다.</li>
     *   <li>회차 예외는 로그만 남기고 다음 회차는 그대로 돈다.</li>
     * </ul>
     */
    @Slf4j
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true")
    @RequiredArgsConstructor
    static class Scheduling implements SchedulingConfigurer {

        private final PaymentReconciliationFacade reconciliationFacade;
        private final OrderExpirationService expirationService;
        private final ReconciliationProperties reconciliationProperties;
        private final OrderExpirationProperties expirationProperties;

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(new FixedDelayTask(this::reconcile,
                    reconciliationProperties.interval(), reconciliationProperties.interval()));
            registrar.addFixedDelayTask(new FixedDelayTask(this::expire,
                    expirationProperties.interval(), expirationProperties.interval()));
        }

        private void reconcile() {
            runSafely("승인 대사", reconciliationFacade::reconcilePayments);
            runSafely("취소 대사", reconciliationFacade::reconcileCancels);
        }

        private void expire() {
            runSafely("만료", expirationService::expire);
        }

        private void runSafely(String job, Runnable task) {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("[{}] 회차가 예외로 끝났다. 다음 회차에 다시 돈다.", job, e);
            }
        }
    }
}
