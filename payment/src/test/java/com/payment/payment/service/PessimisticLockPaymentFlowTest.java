package com.payment.payment.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 비관적 락 구현체의 결제 승인 시나리오 테스트.
 * 시나리오는 {@link PaymentFlowTestSupport}에 있다. SQL 로그에서 {@code for update} 순서(orders → product)를 확인한다.
 */
@TestPropertySource(properties = "logging.level.org.hibernate.SQL=debug")
class PessimisticLockPaymentFlowTest extends PaymentFlowTestSupport {

    @Autowired
    private PessimisticLockPaymentStateService pessimisticLockPaymentStateService;

    @Override
    protected PaymentStateService stateService() {
        return pessimisticLockPaymentStateService;
    }
}
