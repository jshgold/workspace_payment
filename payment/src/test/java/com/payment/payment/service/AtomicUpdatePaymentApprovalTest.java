package com.payment.payment.service;

import org.springframework.beans.factory.annotation.Autowired;

/** 원자적 쿼리 구현체로 PAYING을 선점하는 경우의 멱등키·중복 결제 방지 테스트. 시나리오는 {@link PaymentApprovalTestSupport}에 있다. */
class AtomicUpdatePaymentApprovalTest extends PaymentApprovalTestSupport {

    @Autowired
    private AtomicUpdatePaymentStateService atomicUpdatePaymentStateService;

    @Override
    protected PaymentStateService stateService() {
        return atomicUpdatePaymentStateService;
    }

    @Override
    protected String strategyName() {
        return "원자적 쿼리";
    }
}
