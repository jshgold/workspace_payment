package com.payment.payment.service;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 원자적 쿼리 구현체의 4-2+ 재현 테스트. 시나리오는 {@link PaymentAttemptRecordTestSupport}에 있다.
 */
class AtomicUpdatePaymentAttemptRecordTest extends PaymentAttemptRecordTestSupport {

    @Autowired
    private AtomicUpdatePaymentStateService atomicUpdatePaymentStateService;

    @Override
    protected PaymentStateService stateService() {
        return atomicUpdatePaymentStateService;
    }
}
