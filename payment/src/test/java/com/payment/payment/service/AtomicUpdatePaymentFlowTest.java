package com.payment.payment.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 원자적 쿼리 구현체의 결제 승인 시나리오 테스트.
 * 시나리오는 {@link PaymentFlowTestSupport}에 있고 비관적 락 구현체와 완전히 같다.
 * SQL 로그에서 조건부 UPDATE({@code where ... status=?})가 찍히고 {@code for update}는 없는지 확인한다.
 */
@TestPropertySource(properties = "logging.level.org.hibernate.SQL=debug")
class AtomicUpdatePaymentFlowTest extends PaymentFlowTestSupport {

    @Autowired
    private AtomicUpdatePaymentStateService atomicUpdatePaymentStateService;

    @Override
    protected PaymentStateService stateService() {
        return atomicUpdatePaymentStateService;
    }
}
