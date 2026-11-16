package com.payment.payment.domain;

/** 결제 완료 후처리(알림 등)의 출구. 지금은 로그만 남기고, 나중에 실제 발송 수단으로 바꿀 수 있다. */
public interface PaymentNotifier {

    void notifyPaid(PaymentCompletedEvent event);
}
