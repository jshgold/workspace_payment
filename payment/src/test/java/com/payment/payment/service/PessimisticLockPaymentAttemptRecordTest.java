package com.payment.payment.service;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 비관적 락 구현체의 4-2+ 재현 테스트. 시나리오는 {@link PaymentAttemptRecordTestSupport}에 있다.
 */
class PessimisticLockPaymentAttemptRecordTest extends PaymentAttemptRecordTestSupport {

    @Autowired
    private PessimisticLockPaymentStateService pessimisticLockPaymentStateService;

    @Override
    protected PaymentStateService stateService() {
        return pessimisticLockPaymentStateService;
    }
}
