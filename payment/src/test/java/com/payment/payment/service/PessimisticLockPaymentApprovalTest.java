package com.payment.payment.service;

import org.springframework.beans.factory.annotation.Autowired;

/** 비관적 락 구현체로 PAYING을 선점하는 경우의 멱등키·중복 결제 방지 테스트. 시나리오는 {@link PaymentApprovalTestSupport}에 있다. */
class PessimisticLockPaymentApprovalTest extends PaymentApprovalTestSupport {

    @Autowired
    private PessimisticLockPaymentStateService pessimisticLockPaymentStateService;

    @Override
    protected PaymentStateService stateService() {
        return pessimisticLockPaymentStateService;
    }

    @Override
    protected String strategyName() {
        return "비관적 락";
    }
}
